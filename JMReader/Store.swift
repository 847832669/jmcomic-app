import Foundation
import SwiftUI
import Security

enum Keychain {
    private static let service = "com.denia.jmreader.auth"

    static func set(_ key: String, _ value: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: key]
        let attrs: [String: Any] = [kSecValueData as String: data]
        if SecItemUpdate(query as CFDictionary, attrs as CFDictionary) != errSecSuccess {
            var add = query
            add[kSecValueData as String] = data
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    static func get(_ key: String) -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: key,
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func remove(_ key: String) {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: key]
        SecItemDelete(query as CFDictionary)
    }
}

/// Main-actor isolated: this store's @Published state must only ever be
/// mutated on the main thread. Its async helpers used to run on the
/// cooperative pool (non-isolated async funcs don't inherit the caller's
/// actor), so entering the profile page at cold start published from a
/// background thread while SwiftUI's view graph waited on the main
/// thread and the main thread waited on the publisher lock — a
/// three-way deadlock the FRONTBOARD watchdog killed (0x8BADF00D,
/// logs 2026-11-03 16:16 & 16:55).
@MainActor
final class AccountStore: ObservableObject {
    @Published private(set) var username: String?
    @Published private(set) var stats: MemberStats?

    struct MemberStats: Codable {
        var exp: Int = 0
        var nextLevelExp: Int = 0
        var level: Int = 0
        var coin: Int = 0
        var favoritesCount: Int = 0
        var favoritesMax: Int = 0
    }

    private let statsKey = "jm.memberStats.v1"

    struct Auth { let jwt: String; let avs: String }

    /// Read by the API client on every request; nil when signed out.
    /// nonisolated: it only reads the keychain, and the API client calls
    /// it from background queues on every request.
    nonisolated static var currentAuth: Auth? {
        guard let jwt = Keychain.get("jwttoken"), !jwt.isEmpty else { return nil }
        return Auth(jwt: jwt, avs: Keychain.get("avs") ?? "")
    }

    var isLoggedIn: Bool { username != nil }

    /// The member id, decoded from the stored login token (SESSION.uid).
    nonisolated static var currentUserId: Int? {
        guard let jwt = Keychain.get("jwttoken") else { return nil }
        let parts = jwt.split(separator: ".")
        guard parts.count == 3 else { return nil }
        var b64 = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let session = obj["SESSION"] as? [String: Any] else { return nil }
        return (session["uid"] as? NSNumber)?.intValue
    }

    init() {
        username = Keychain.get("username")
        if username != nil,
           let data = UserDefaults.standard.data(forKey: statsKey),
           let st = try? JSONDecoder().decode(MemberStats.self, from: data) {
            stats = st
        }
        if let saved = UserDefaults.standard.dictionary(forKey: tombstoneKey) as? [String: TimeInterval] {
            deletedFolderTombstones = saved
        }
    }

    private func saveStats() {
        if let st = stats, let data = try? JSONEncoder().encode(st) {
            UserDefaults.standard.set(data, forKey: statsKey)
        }
    }

    /// The favorites endpoint reports the live total on every load; fold it
    /// into the cached stats so the profile strip's 收藏 count never goes stale
    /// (exp / level / coin only exist in the login response).
    func syncFavoritesCount(_ total: Int) {
        guard var st = stats, total > 0, st.favoritesCount != total else { return }
        st.favoritesCount = total
        stats = st
        saveStats()
    }

    /// After a successful check-in the server reports the delta ("Jcoin:2 EXP:2");
    /// fold it into the cached stats so the profile strip stays current.
    func applyCheckinReward(_ msg: String) {
        guard var st = stats else { return }
        if let r = msg.range(of: "Jcoin:(\\d+)", options: .regularExpression) {
            st.coin += Int(msg[r].dropFirst(6)) ?? 0
        }
        if let r = msg.range(of: "EXP:(\\d+)", options: .regularExpression) {
            st.exp += Int(msg[r].dropFirst(4)) ?? 0
        }
        stats = st
        saveStats()
    }

    func signIn(username: String, password: String) async throws {
        let info = try await JMClient.shared.login(username: username, password: password)
        let jwt = anyString(info["jwttoken"])
        guard !jwt.isEmpty else { throw JMError.badResponse }
        Keychain.set("jwttoken", jwt)
        Keychain.set("avs", anyString(info["s"]))
        let name = anyString(info["username"]).isEmpty ? username : anyString(info["username"])
        Keychain.set("username", name)
        self.username = name
        var st = MemberStats()
        st.exp = anyInt(info["exp"])
        st.nextLevelExp = anyInt(info["nextLevelExp"])
        st.level = anyInt(info["level"])
        st.coin = anyInt(info["coin"])
        st.favoritesCount = anyInt(info["album_favorites"])
        st.favoritesMax = anyInt(info["album_favorites_max"])
        stats = st
        saveStats()
    }

    // MARK: - cloud favorites cache (memory + disk snapshot, survives relaunch)
    @Published private(set) var cloudFavorites: [Comic] = []
    @Published private(set) var cloudTotal = 0
    @Published private(set) var cloudLoading = false
    private var cloudBulkLoading = false
    private var cloudPage = 0
    private var cloudPageSize = 0
    private var cloudOwner: String?

    private struct CloudFavSnapshot: Codable {
        var owner: String
        var total: Int
        var pages: Int
        var pageSize: Int
        var items: [Comic]
    }
    private let cloudKey = "jm.cloudFav.v1"

    var cloudFullyLoaded: Bool { cloudTotal > 0 && cloudFavorites.count >= cloudTotal }

    // MARK: - cloud favorite folders

    @Published private(set) var cloudFolders: [FavFolder] = []
    @Published private(set) var cloudFolderId: String = "0"
    /// Non-default folders are cached in memory only (the disk snapshot and
    /// the reconcile machinery stay scoped to the default folder).
    private var folderItems: [String: [Comic]] = [:]
    private var folderTotals: [String: Int] = [:]
    private var folderPages: [String: Int] = [:]
    private var folderLoading = false
    /// Ids of folders deleted on this device, mapped to deletion time.
    /// The favorites endpoint is raced across several API hosts whose
    /// caches don't go stale in sync, so a just-deleted folder can keep
    /// reappearing in folder_list for a while and noteFolders would
    /// resurrect it. Tombstoned ids are filtered out of every server
    /// list for a fixed lifetime (24h) — never lifted early, because
    /// the hosts disagree for far too long for any single response
    /// to prove the folder is gone everywhere. Persisted so an app
    /// relaunch inside the stale window doesn't resurrect it either.
    private var deletedFolderTombstones: [String: TimeInterval] = [:]
    private let tombstoneKey = "jm.deletedFolders.v1"
    private static let tombstoneLifetime: TimeInterval = 86_400

    private func saveTombstones() {
        UserDefaults.standard.set(deletedFolderTombstones, forKey: tombstoneKey)
    }

    private func clearTombstones() {
        guard !deletedFolderTombstones.isEmpty else { return }
        deletedFolderTombstones = [:]
        UserDefaults.standard.removeObject(forKey: tombstoneKey)
    }

    /// Folders as shown in pickers: the default folder always comes first.
    var displayFolders: [FavFolder] {
        if cloudFolders.contains(where: { $0.id == "0" }) { return cloudFolders }
        return [FavFolder(id: "0", name: "默认收藏")] + cloudFolders
    }

    var displayedCloud: [Comic] {
        cloudFolderId == "0" ? cloudFavorites : (folderItems[cloudFolderId] ?? [])
    }
    var displayedCloudTotal: Int {
        cloudFolderId == "0" ? cloudTotal : (folderTotals[cloudFolderId] ?? 0)
    }
    var displayedCloudFullyLoaded: Bool {
        displayedCloudTotal > 0 && displayedCloud.count >= displayedCloudTotal
    }

    private func noteFolders(_ folders: [FavFolder]) {
        if !deletedFolderTombstones.isEmpty {
            let now = Date().timeIntervalSince1970
            let countBefore = deletedFolderTombstones.count
            // Purely age-based pruning — a tombstone is never lifted
            // early. Measured against the live API (2026-11-04): the
            // hosts kept disagreeing about a deleted folder for 12+
            // hours, flip-flopping between responses, so one host's
            // list missing the id proves nothing; the next refresh
            // may hit a host that still serves it (build 36 dropped
            // the tombstone on first absence and the folder came
            // straight back).
            deletedFolderTombstones = deletedFolderTombstones.filter { _, ts in
                now - ts < Self.tombstoneLifetime
            }
            if deletedFolderTombstones.count != countBefore { saveTombstones() }
        }
        let visible = folders.filter { deletedFolderTombstones[$0.id] == nil }
        if !visible.isEmpty { cloudFolders = visible }
    }

    func switchFolder(_ id: String) async {
        guard id != cloudFolderId else { return }
        cloudFolderId = id
        if id != "0", folderItems[id] == nil { await loadFolderPage() }
    }

    func loadFolderPage(reset: Bool = false) async {
        let fid = cloudFolderId
        guard fid != "0", !folderLoading, isLoggedIn else { return }
        if reset { folderItems[fid] = []; folderPages[fid] = 0; folderTotals[fid] = 0 }
        let loaded = folderItems[fid] ?? []
        if !loaded.isEmpty, let t = folderTotals[fid], loaded.count >= t { return }
        folderLoading = true
        cloudLoading = true
        defer { folderLoading = false; cloudLoading = false }
        do {
            let page = (folderPages[fid] ?? 0) + 1
            let res = try await JMClient.shared.favorites(page: page, folderId: fid)
            noteFolders(res.folders)
            folderTotals[fid] = res.total
            folderPages[fid] = page
            let known = Set(loaded.map(\.id))
            folderItems[fid] = loaded + res.items.filter { !known.contains($0.id) }
        } catch { /* keep what we have */ }
    }

    func createFolder(name: String) async {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        // Optimistic: show the folder right away under a pending id. The
        // favorites endpoint can serve a stale folder_list for a few
        // seconds after a write (a created folder only showed up after
        // leaving and re-entering the page), so poll until the real
        // folder (with its server id) shows up and swap the placeholder.
        let pendingId = "pending-" + UUID().uuidString
        cloudFolders.append(FavFolder(id: pendingId, name: name))
        do {
            try await JMClient.shared.favoriteFolderAdd(name: name)
        } catch {
            cloudFolders.removeAll { $0.id == pendingId }
            return
        }
        for _ in 0..<5 {
            if let res = try? await JMClient.shared.favorites(page: 1, folderId: "0"),
               res.folders.contains(where: { $0.name == name }) {
                noteFolders(res.folders)
                return
            }
            try? await Task.sleep(nanoseconds: 1_200_000_000)
        }
        cloudFolders.removeAll { $0.id == pendingId }
        await refreshFolderList()
    }

    func deleteFolder(id: String) async {
        guard id != "0" else { return }
        // Optimistic removal, same stale-read problem as create. The
        // tombstone keeps later (stale) server lists from bringing
        // the folder back — see noteFolders.
        deletedFolderTombstones[id] = Date().timeIntervalSince1970
        saveTombstones()
        let backup = cloudFolders
        cloudFolders.removeAll { $0.id == id }
        folderItems[id] = nil; folderTotals[id] = nil; folderPages[id] = nil
        if cloudFolderId == id { cloudFolderId = "0" }
        do {
            try await JMClient.shared.favoriteFolderDelete(folderId: id)
        } catch {
            deletedFolderTombstones[id] = nil
            saveTombstones()
            cloudFolders = backup
            return
        }
        for _ in 0..<3 {
            if let res = try? await JMClient.shared.favorites(page: 1, folderId: "0"),
               !res.folders.contains(where: { $0.id == id }) {
                noteFolders(res.folders)
                return
            }
            try? await Task.sleep(nanoseconds: 1_200_000_000)
        }
    }

    private func refreshFolderList() async {
        if let res = try? await JMClient.shared.favorites(page: 1, folderId: cloudFolderId) {
            noteFolders(res.folders)
        }
    }

    func moveFavorite(aid: String, toFolder: String) async {
        guard !toFolder.hasPrefix("pending-") else { return }
        do {
            try await JMClient.shared.favoriteFolderMove(albumId: aid, folderId: toFolder)
            await applyFolderMove(aid: aid, toFolder: toFolder)
        } catch { /* keep state */ }
    }

    /// After a move: drop the comic from every cached list except the
    /// target's, and invalidate the target so it refetches.
    func applyFolderMove(aid: String, toFolder: String) async {
        if toFolder != "0", cloudFavorites.contains(where: { $0.id == aid }) {
            cloudFavorites.removeAll { $0.id == aid }
            cloudTotal = max(0, cloudTotal - 1)
            syncFavoritesCount(cloudTotal)
            persistCloudSnapshot()
        }
        for key in Array(folderItems.keys) where key != toFolder {
            folderItems[key]?.removeAll { $0.id == aid }
            if let items = folderItems[key] { folderTotals[key] = items.count }
        }
        if toFolder != "0" {
            folderItems[toFolder] = nil; folderTotals[toFolder] = nil; folderPages[toFolder] = nil
            if cloudFolderId == toFolder { await loadFolderPage() }
        } else if cloudFolderId == "0" {
            // Moved back to the default folder: a front-reconcile picks it up.
            await reconcileCloudCache()
        }
    }

    private var persistScheduled = false

    /// Encoding a 1000+ item snapshot on the main thread was part of the
    /// profile-page hitch: encode detached, and coalesce bursts (paging
    /// calls this per page) into one write with the latest state.
    private func persistCloudSnapshot() {
        guard let owner = cloudOwner, !persistScheduled else { return }
        persistScheduled = true
        let snap = CloudFavSnapshot(owner: owner, total: cloudTotal, pages: cloudPage,
                                    pageSize: cloudPageSize, items: cloudFavorites)
        let key = cloudKey
        Task.detached(priority: .utility) {
            if let data = try? JSONEncoder().encode(snap) {
                UserDefaults.standard.set(data, forKey: key)
            }
            await MainActor.run { [weak self] in self?.persistScheduled = false }
        }
    }

    private func hydrateCloudFromDisk() async {
        guard let data = UserDefaults.standard.data(forKey: cloudKey) else { return }
        let snap = await Task.detached(priority: .userInitiated) {
            try? JSONDecoder().decode(CloudFavSnapshot.self, from: data)
        }.value
        guard let snap, snap.owner == username else { return }
        cloudFavorites = snap.items
        cloudTotal = snap.total
        cloudPage = snap.pages
        cloudPageSize = snap.pageSize
    }

    /// Reset only when the signed-in account changes. Warm cache (memory or disk)
    /// is shown instantly; a one-page check in the background catches changes.
    func ensureCloudCache() async {
        guard isLoggedIn else { return }
        if cloudOwner != username {
            cloudOwner = username
            cloudFavorites = []; cloudPage = 0; cloudTotal = 0; cloudPageSize = 0
            cloudFolders = []; cloudFolderId = "0"
            folderItems = [:]; folderTotals = [:]; folderPages = [:]
            clearTombstones()
            await hydrateCloudFromDisk()
        }
        if cloudFavorites.isEmpty {
            await loadCloudPage()
        } else {
            await reconcileCloudCache()
        }
    }

    /// Cache check that does NOT nuke the whole cache on any change.
    /// The old validate cleared everything whenever the newest favorite
    /// or the total moved — for an active account that meant a full
    /// 55-page refetch on most profile visits, which is exactly the
    /// heavy path behind the intermittent profile freezes. Now:
    /// additions at the front are prepended after a short overlap walk,
    /// and only a SHRINK (unfavorited elsewhere) falls back to a reload.
    private func reconcileCloudCache() async {
        guard let res = try? await JMClient.shared.favorites(page: 1) else { return }
        noteFolders(res.folders)
        if res.total == cloudTotal && res.items.first?.id == cloudFavorites.first?.id {
            return // cache is fresh
        }
        if res.total < cloudFavorites.count {
            cloudFavorites = []; cloudPage = 0; cloudTotal = 0; cloudPageSize = 0
            await loadCloudPage()
            return
        }
        var fresh: [Comic] = []
        var pageItems = res.items
        var overlapped = false
        for page in 1...3 {
            if page > 1 {
                guard let next = try? await JMClient.shared.favorites(page: page) else { break }
                pageItems = next.items
            }
            for item in pageItems {
                if item.id == cloudFavorites.first?.id { overlapped = true; break }
                fresh.append(item)
            }
            if overlapped { break }
        }
        if overlapped {
            cloudFavorites = fresh + cloudFavorites
            cloudTotal = res.total
            syncFavoritesCount(res.total)
            persistCloudSnapshot()
        } else {
            cloudFavorites = []; cloudPage = 0; cloudTotal = 0; cloudPageSize = 0
            await loadCloudPage()
        }
    }

    func loadCloudPage() async {
        guard !cloudLoading, isLoggedIn else { return }
        if cloudFolderId != "0" { await loadFolderPage(); return }
        if !cloudFavorites.isEmpty && cloudFullyLoaded { return }
        cloudLoading = true
        defer { cloudLoading = false }
        do {
            let res = try await JMClient.shared.favorites(page: cloudPage + 1)
            noteFolders(res.folders)
            cloudTotal = res.total
            syncFavoritesCount(res.total)
            if cloudPageSize == 0 { cloudPageSize = res.items.count }
            cloudPage += 1
            let known = Set(cloudFavorites.map(\.id))
            cloudFavorites += res.items.filter { !known.contains($0.id) }
            persistCloudSnapshot()
        } catch { /* keep cache */ }
    }

    /// Load every remaining page CONCURRENTLY (sequential paging was the slow part).
    func loadAllCloud() async {
        if cloudFolderId != "0" {
            // Non-default folders: page through everything sequentially.
            var guardPages = 0
            while !displayedCloudFullyLoaded, guardPages < 80 {
                let before = displayedCloud.count
                await loadFolderPage()
                guardPages += 1
                if displayedCloud.count == before { break }
            }
            return
        }
        // Two view tasks can ask for this at once; overlapping runs used to
        // interleave their mutations of the published favorites array.
        guard !cloudBulkLoading, !cloudLoading else { return }
        cloudBulkLoading = true
        defer { cloudBulkLoading = false }
        if cloudFavorites.isEmpty { await loadCloudPage() }
        guard isLoggedIn, !cloudFullyLoaded, cloudTotal > 0, cloudPageSize > 0 else { return }
        let totalPages = Int(ceil(Double(cloudTotal) / Double(cloudPageSize)))
        let remaining = Array((cloudPage + 1)...totalPages)
        guard !remaining.isEmpty else { return }
        // Fetch in waves of 8 pages: firing all ~54 remaining pages at
        // once bursts the network stack and the decrypt queue, and the
        // stall that follows lands right on the profile page.
        var fetched: [Int: [Comic]] = [:]
        for waveStart in stride(from: 0, to: remaining.count, by: 8) {
            let wave = Array(remaining[waveStart..<min(waveStart + 8, remaining.count)])
            let waveResult = await withTaskGroup(of: (Int, [Comic]).self, returning: [Int: [Comic]].self) { group in
                for p in wave {
                    group.addTask {
                        let r = try? await JMClient.shared.favorites(page: p)
                        return (p, r?.items ?? [])
                    }
                }
                var out: [Int: [Comic]] = [:]
                for await (p, items) in group { out[p] = items }
                return out
            }
            fetched.merge(waveResult) { _, new in new }
        }
        // Merge into a LOCAL array and publish ONCE. Appending item-by-item
        // fired objectWillChange per comic (~1000 publishes for a big
        // account), each re-rendering the whole favorites grid on the main
        // thread — the profile page froze solid and the scene watchdog
        // killed the app (0x8BADF00D, crash log 2026-11-03).
        var merged = cloudFavorites
        var seen = Set(merged.map(\.id))
        for p in remaining {
            for item in fetched[p] ?? [] where !seen.contains(item.id) {
                seen.insert(item.id)
                merged.append(item)
            }
        }
        if merged.count != cloudFavorites.count { cloudFavorites = merged }
        cloudPage = totalPages
        persistCloudSnapshot()
    }

    func signOut() {
        Keychain.remove("jwttoken")
        Keychain.remove("avs")
        Keychain.remove("username")
        username = nil
        stats = nil
        cloudFavorites = []; cloudTotal = 0; cloudPage = 0; cloudPageSize = 0; cloudOwner = nil
        cloudFolders = []; cloudFolderId = "0"
        folderItems = [:]; folderTotals = [:]; folderPages = [:]
        clearTombstones()
        UserDefaults.standard.removeObject(forKey: cloudKey)
        UserDefaults.standard.removeObject(forKey: statsKey)
    }
}

final class AppState: ObservableObject {
    @Published var selectedTab: Int = 0
    @Published var pendingSearch: String?
    @Published var searchFocusToken: Int = 0
    @Published var gridColumns: Int {
        didSet { UserDefaults.standard.set(gridColumns, forKey: "jm.gridColumns") }
    }

    init() {
        let saved = UserDefaults.standard.integer(forKey: "jm.gridColumns")
        gridColumns = [2, 3, 4].contains(saved) ? saved : 4
    }

    func requestSearch(_ query: String) {
        pendingSearch = query
        selectedTab = 1
    }

    func goSearch() {
        selectedTab = 1
        searchFocusToken += 1
    }

    func cycleColumns() {
        gridColumns = gridColumns >= 4 ? 2 : gridColumns + 1
    }
}

final class LibraryStore: ObservableObject {
    @Published private(set) var favorites: [Comic] = []
    /// albumId -> timeIntervalSince1970 when it was favorited (for sorting)
    @Published private(set) var favDates: [String: Double] = [:]
    /// albumId -> (chapterId, pageIndex)
    @Published private(set) var progress: [String: ProgressEntry] = [:]

    struct ProgressEntry: Codable {
        var chapterId: String
        var chapterName: String
        var page: Int
        var updatedAt: Double
        var albumName: String? = nil
    }

    private let favURL: URL
    private let progKey = "jm.progress.v1"
    private let favDatesKey = "jm.favdates.v1"

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        favURL = docs.appendingPathComponent("favorites.json")
        if let data = try? Data(contentsOf: favURL),
           let list = try? JSONDecoder().decode([Comic].self, from: data) {
            favorites = list
        }
        if let data = UserDefaults.standard.data(forKey: progKey),
           let map = try? JSONDecoder().decode([String: ProgressEntry].self, from: data) {
            progress = map
        }
        if let data = UserDefaults.standard.data(forKey: favDatesKey),
           let map = try? JSONDecoder().decode([String: Double].self, from: data) {
            favDates = map
        }
        if favDates.isEmpty && !favorites.isEmpty {
            // legacy favorites have no timestamps; synthesize from list order (newest first)
            let now = Date().timeIntervalSince1970
            for (i, c) in favorites.enumerated() { favDates[c.id] = now - Double(i) * 3600 }
            saveFavDates()
        }
    }

    func favDate(for comic: Comic) -> Double { favDates[comic.id] ?? 0 }

    private func saveFavDates() {
        if let data = try? JSONEncoder().encode(favDates) {
            UserDefaults.standard.set(data, forKey: favDatesKey)
        }
    }

    func isFavorite(_ id: String) -> Bool { favorites.contains { $0.id == id } }

    func toggleFavorite(_ comic: Comic) {
        if isFavorite(comic.id) {
            favorites.removeAll { $0.id == comic.id }
            favDates[comic.id] = nil
        } else {
            favorites.insert(comic, at: 0)
            favDates[comic.id] = Date().timeIntervalSince1970
        }
        saveFavDates()
        if let data = try? JSONEncoder().encode(favorites) {
            try? data.write(to: favURL, options: .atomic)
        }
    }

    func progressFor(albumId: String) -> ProgressEntry? { progress[albumId] }

    func saveProgress(albumId: String, chapterId: String, chapterName: String, page: Int, albumName: String = "") {
        let name = albumName.isEmpty ? progress[albumId]?.albumName : albumName
        progress[albumId] = ProgressEntry(chapterId: chapterId, chapterName: chapterName,
                                          page: page, updatedAt: Date().timeIntervalSince1970,
                                          albumName: name)
        persistProgress()
    }

    func removeProgress(albumId: String) {
        progress[albumId] = nil
        persistProgress()
    }

    func clearProgress() {
        progress = [:]
        persistProgress()
    }

    /// Fill in a missing album title (entries recorded before titles were stored).
    func backfillProgressName(albumId: String, name: String) {
        guard var e = progress[albumId], (e.albumName ?? "").isEmpty, !name.isEmpty else { return }
        e.albumName = name
        progress[albumId] = e
        persistProgress()
    }

    private func persistProgress() {
        if let data = try? JSONEncoder().encode(progress) {
            UserDefaults.standard.set(data, forKey: progKey)
        }
    }
}

final class SearchHistory: ObservableObject {
    @Published private(set) var items: [String] = []
    private let key = "jm.searchHistory.v1"

    init() { items = UserDefaults.standard.stringArray(forKey: key) ?? [] }

    func add(_ q: String) {
        let q = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        items.removeAll { $0 == q }
        items.insert(q, at: 0)
        items = Array(items.prefix(12))
        UserDefaults.standard.set(items, forKey: key)
    }

    func clear() {
        items = []
        UserDefaults.standard.removeObject(forKey: key)
    }
}


// MARK: - blocked tags (custom browse filter)

/// User-defined tag blocklist. Browse surfaces (home shelves, channels,
/// categories, search) hide matching comics two ways: immediately when the
/// title / author / category contains a blocked word, and — once an album's
/// real tags are known — when its detail tags contain a blocked tag. List
/// endpoints return no tags, so tags are learned from detail fetches
/// (opening a detail page, plus background prefetch from lists) and kept
/// in a persisted cache. Favorites and history are never filtered.
/// Main-actor isolated for the same reason as AccountStore: prefetch
/// learned tags in a background task and published them off the main
/// thread, which is one of the three parties in the profile deadlock.
@MainActor
final class BlockTagStore: ObservableObject {
    @Published private(set) var blockedTags: [String] = []
    @Published private(set) var tagCache: [String: [String]] = [:]
    private var inFlight: Set<String> = []

    private let listKey = "jm.blockedTags.v1"
    private let cacheKey = "jm.tagCache.v1"

    init() {
        if let arr = UserDefaults.standard.array(forKey: listKey) as? [String] {
            blockedTags = arr
        }
        if let data = UserDefaults.standard.data(forKey: cacheKey),
           let map = try? JSONDecoder().decode([String: [String]].self, from: data) {
            tagCache = map
        }
    }

    func add(_ raw: String) {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !blockedTags.contains(t) else { return }
        blockedTags.append(t)
        UserDefaults.standard.set(blockedTags, forKey: listKey)
    }

    func remove(_ tag: String) {
        blockedTags.removeAll { $0 == tag }
        UserDefaults.standard.set(blockedTags, forKey: listKey)
    }

    /// Record tags learned from an album detail fetch (empty is recorded
    /// too, so tag-less albums are not refetched forever).
    func remember(albumId: String, tags: [String]) {
        guard tagCache[albumId] != tags else { return }
        tagCache[albumId] = tags
        if let data = try? JSONEncoder().encode(tagCache) {
            UserDefaults.standard.set(data, forKey: cacheKey)
        }
    }

    func isBlocked(_ comic: Comic) -> Bool {
        guard !blockedTags.isEmpty else { return false }
        let hay = comic.name + "\n" + comic.author + "\n" + comic.categoryTitle
        for t in blockedTags where hay.localizedCaseInsensitiveContains(t) { return true }
        if let tags = tagCache[comic.id] {
            for t in blockedTags where tags.contains(t) { return true }
        }
        return false
    }

    func visible(_ items: [Comic]) -> [Comic] {
        guard !blockedTags.isEmpty else { return items }
        return items.filter { !isBlocked($0) }
    }

    /// Background: learn tags for listed albums whose tags are still
    /// unknown (4 at a time, at most 40 per call). Once known, the
    /// tag-based filter applies to them here and on future visits.
    func prefetch(_ items: [Comic]) {
        guard !blockedTags.isEmpty else { return }
        let unknown = items.map(\.id).filter { tagCache[$0] == nil && !inFlight.contains($0) }
        guard !unknown.isEmpty else { return }
        let batch = Array(unknown.prefix(24))
        batch.forEach { inFlight.insert($0) }
        Task {
            for chunkStart in stride(from: 0, to: batch.count, by: 3) {
                let chunk = Array(batch[chunkStart..<min(chunkStart + 3, batch.count)])
                let results = await withTaskGroup(of: (String, [String]?).self) { group in
                    for id in chunk {
                        group.addTask {
                            let d = try? await JMClient.shared.album(id: id)
                            return (id, d?.tags)
                        }
                    }
                    var out: [(String, [String]?)] = []
                    for await r in group { out.append(r) }
                    return out
                }
                // Remember fetched albums even when their tag list is empty —
                // otherwise tagless albums were re-fetched on every appearance.
                // Failures (nil) stay uncached and retry another time.
                for (id, tags) in results {
                    if let tags { self.remember(albumId: id, tags: tags) }
                }
            }
            batch.forEach { self.inFlight.remove($0) }
        }
    }
}
