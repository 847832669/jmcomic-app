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
    static var currentAuth: Auth? {
        guard let jwt = Keychain.get("jwttoken"), !jwt.isEmpty else { return nil }
        return Auth(jwt: jwt, avs: Keychain.get("avs") ?? "")
    }

    var isLoggedIn: Bool { username != nil }

    /// The member id, decoded from the stored login token (SESSION.uid).
    static var currentUserId: Int? {
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
    }

    private func saveStats() {
        if let st = stats, let data = try? JSONEncoder().encode(st) {
            UserDefaults.standard.set(data, forKey: statsKey)
        }
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

    @MainActor
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

    private func persistCloudSnapshot() {
        guard let owner = cloudOwner else { return }
        let snap = CloudFavSnapshot(owner: owner, total: cloudTotal, pages: cloudPage,
                                    pageSize: cloudPageSize, items: cloudFavorites)
        if let data = try? JSONEncoder().encode(snap) {
            UserDefaults.standard.set(data, forKey: cloudKey)
        }
    }

    private func hydrateCloudFromDisk() {
        guard let data = UserDefaults.standard.data(forKey: cloudKey),
              let snap = try? JSONDecoder().decode(CloudFavSnapshot.self, from: data),
              snap.owner == username else { return }
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
            hydrateCloudFromDisk()
        }
        if cloudFavorites.isEmpty {
            await loadCloudPage()
        } else {
            await validateCloudCache()
        }
    }

    private func validateCloudCache() async {
        guard let res = try? await JMClient.shared.favorites(page: 1) else { return }
        if res.total != cloudTotal || res.items.first?.id != cloudFavorites.first?.id {
            cloudFavorites = []; cloudPage = 0; cloudTotal = 0; cloudPageSize = 0
            await loadCloudPage()
        }
    }

    @MainActor
    func loadCloudPage() async {
        guard !cloudLoading, isLoggedIn else { return }
        if !cloudFavorites.isEmpty && cloudFullyLoaded { return }
        cloudLoading = true
        defer { cloudLoading = false }
        do {
            let res = try await JMClient.shared.favorites(page: cloudPage + 1)
            cloudTotal = res.total
            if cloudPageSize == 0 { cloudPageSize = res.items.count }
            cloudPage += 1
            let known = Set(cloudFavorites.map(\.id))
            cloudFavorites += res.items.filter { !known.contains($0.id) }
            persistCloudSnapshot()
        } catch { /* keep cache */ }
    }

    /// Load every remaining page CONCURRENTLY (sequential paging was the slow part).
    @MainActor
    func loadAllCloud() async {
        if cloudFavorites.isEmpty { await loadCloudPage() }
        guard isLoggedIn, !cloudFullyLoaded, cloudTotal > 0, cloudPageSize > 0 else { return }
        let totalPages = Int(ceil(Double(cloudTotal) / Double(cloudPageSize)))
        let remaining = Array((cloudPage + 1)...totalPages)
        guard !remaining.isEmpty else { return }
        let fetched = await withTaskGroup(of: (Int, [Comic]).self, returning: [Int: [Comic]].self) { group in
            for p in remaining {
                group.addTask {
                    let r = try? await JMClient.shared.favorites(page: p)
                    return (p, r?.items ?? [])
                }
            }
            var out: [Int: [Comic]] = [:]
            for await (p, items) in group { out[p] = items }
            return out
        }
        let known = Set(cloudFavorites.map(\.id))
        var seen = known
        for p in remaining {
            for item in fetched[p] ?? [] where !seen.contains(item.id) {
                seen.insert(item.id)
                cloudFavorites.append(item)
            }
        }
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
