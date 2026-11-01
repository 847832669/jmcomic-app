import SwiftUI

let accentPink = Color(red: 1.0, green: 0.18, blue: 0.533)

// MARK: - shared bits

struct RemoteImage: View {
    let path: String
    var scrambleNum: Int = 0
    var targetWidth: CGFloat = 0
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
            } else {
                Rectangle().fill(Color.white.opacity(0.07))
                    .overlay(ProgressView().tint(.gray))
            }
        }
        .task(id: path) {
            image = await ImagePipe.shared.load(path: path, scrambleNum: scrambleNum, targetWidth: targetWidth)
        }
    }
}

struct ComicCell: View {
    let comic: Comic
    var columns: Int = 2

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RemoteImage(path: ImagePipe.coverPath(albumId: comic.id), targetWidth: 400)
                .aspectRatio(3 / 4, contentMode: .fill)
                .clipped()
                .cornerRadius(columns >= 4 ? 7 : 10)
                .overlay(alignment: .topLeading) {
                    if columns <= 3 && !comic.categoryTitle.isEmpty {
                        Text(comic.categoryTitle)
                            .font(.caption2).padding(.horizontal, 6).padding(.vertical, 3)
                            .background(Color.black.opacity(0.55)).foregroundColor(.white)
                            .cornerRadius(5).padding(6)
                    }
                }
            Text(comic.name)
                .font(columns >= 4 ? .caption2 : (columns == 3 ? .caption : .subheadline))
                .fontWeight(.semibold)
                .lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 5)
            if columns <= 3 {
                Text(comic.author)
                    .font(.caption2).foregroundColor(.secondary)
                    .lineLimit(1).padding(.top, 2)
            }
        }
    }
}

struct ComicGrid: View {
    let items: [Comic]
    var columnsOverride: Int? = nil
    var onLastAppear: (() -> Void)? = nil
    @EnvironmentObject var appState: AppState

    var body: some View {
        let cols = columnsOverride ?? appState.gridColumns
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: cols >= 4 ? 8 : 13), count: cols),
                  spacing: cols >= 4 ? 12 : 18) {
            ForEach(items) { comic in
                NavigationLink(value: comic.id) { ComicCell(comic: comic, columns: cols) }
                    .buttonStyle(.plain)
                    .onAppear { if comic.id == items.last?.id { onLastAppear?() } }
            }
        }
        .padding(.horizontal, cols >= 4 ? 10 : 14)
    }
}

struct ColumnButton: View {
    @EnvironmentObject var appState: AppState
    var body: some View {
        Button {
            appState.cycleColumns()
        } label: {
            Text("\(appState.gridColumns)列")
                .font(.subheadline).fontWeight(.semibold)
        }
    }
}

extension View {
    func withDestinations() -> some View {
        self
            .navigationDestination(for: String.self) { DetailView(albumId: $0) }
            .navigationDestination(for: ReaderRoute.self) { ReaderView(route: $0) }
            .navigationDestination(for: JMCategory.self) { CategoryListView(category: $0) }
    }
}

// MARK: - home (channels)

enum HomeChannel: Identifiable, Equatable {
    case recommend, latest, ai
    case category(JMCategory)
    var id: String {
        switch self {
        case .recommend: return "recommend"
        case .latest: return "latest"
        case .ai: return "ai"
        case .category(let c): return "cat-\(c.slug)"
        }
    }
    var title: String {
        switch self {
        case .recommend: return "推荐"
        case .latest: return "最新"
        case .ai: return "AI生成"
        case .category(let c): return c.name
        }
    }
}

struct HomeView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var downloads: DownloadManager
    @State private var cats: [JMCategory] = []
    @State private var channel: HomeChannel = .recommend

    private var channels: [HomeChannel] {
        [.recommend, .latest, .ai] + cats.map { .category($0) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                channelStrip
                Group {
                    switch channel {
                    case .recommend:
                        RecommendView()
                    case .latest:
                        FeedView(source: .latest)
                    case .ai:
                        FeedView(source: .tagSearch("AI生成"))
                    case .category(let cat):
                        FeedView(source: .category(cat.slug))
                    }
                }
                .id(channel.id)
            }
            .toolbar(.hidden, for: .navigationBar)
                                    .withDestinations()
        }
        .task {
            if cats.isEmpty {
                if let list = try? await JMClient.shared.categories() { cats = list }
            }
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Button {
                appState.selectedTab = 3
            } label: {
                Image("AvatarArt")
                    .resizable().aspectRatio(contentMode: .fill)
                    .frame(width: 38, height: 38)
                    .clipShape(Circle())
                    .overlay(Circle().strokeBorder(Color.white.opacity(0.25), lineWidth: 1))
            }
            .buttonStyle(.plain)
            Button {
                appState.goSearch()
            } label: {
                HStack(spacing: 7) {
                    Image(systemName: "magnifyingglass")
                    Text("搜索").foregroundColor(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 14).padding(.vertical, 9)
                .glassSurface(Capsule(), interactive: true)
            }
            .buttonStyle(.plain)
            NavigationLink {
                DownloadsView(embedded: true)
            } label: {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: "arrow.down.circle")
                        .font(.system(size: 19))
                        .padding(9)
                        .glassSurface(Circle(), interactive: true)
                    if downloads.jobs.contains(where: { $0.state == .working || $0.state == .queued }) {
                        Circle().fill(accentPink).frame(width: 9, height: 9).offset(x: 1, y: -1)
                    }
                }
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14).padding(.top, 8).padding(.bottom, 6)
    }

    private var channelStrip: some View {
        HStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(channels) { ch in
                        Button {
                            channel = ch
                        } label: {
                            Text(ch.title)
                                .font(.subheadline)
                                .fontWeight(channel == ch ? .bold : .regular)
                                .foregroundColor(channel == ch ? .white : .primary)
                                .padding(.horizontal, 13).padding(.vertical, 6)
                                .background {
                                    if channel == ch {
                                        Capsule().fill(accentPink)
                                    }
                                }
                                .glassSurface(Capsule(), interactive: true)
                                .opacity(channel == ch ? 1 : 0.92)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 14).padding(.vertical, 4)
            }
        }
        .padding(.bottom, 4)
    }
}

/// Paginated comic feed used by the Latest / Category / AI home channels.
struct FeedView: View {
    enum Source { case latest, category(String), tagSearch(String) }
    let source: Source
    @State private var items: [Comic] = []
    @State private var page = 0
    @State private var total = Int.max
    @State private var loading = false
    @State private var reachedEnd = false

    var body: some View {
        Group {
            if items.isEmpty && loading {
                ProgressView("加载中…").frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    ComicGrid(items: items) { Task { await loadMore() } }
                        .padding(.top, 8)
                    if loading { ProgressView().padding() }
                    if !loading && items.isEmpty {
                        Text("暂时没内容").foregroundColor(.secondary).padding(.top, 40)
                    }
                }
                .tabMinimizeOnScroll()
                .refreshable { page = 0; reachedEnd = false; total = Int.max; await loadMore(reset: true) }
            }
        }
        .task { if items.isEmpty { await loadMore(reset: true) } }
    }

    private func loadMore(reset: Bool = false) async {
        guard !loading, !reachedEnd else { return }
        if !items.isEmpty && items.count >= total { reachedEnd = true; return }
        loading = true
        defer { loading = false }
        do {
            let res: (total: Int, items: [Comic])
            switch source {
            case .latest:
                let list = try await JMClient.shared.latest(page: page + 1)
                res = (Int.max, list)
            case .category(let slug):
                res = try await JMClient.shared.categoryFilter(slug: slug, page: page + 1)
            case .tagSearch(let q):
                res = try await JMClient.shared.search(query: q, page: page + 1)
            }
            total = res.total
            if res.items.isEmpty { reachedEnd = true; return }
            page += 1
            items = reset ? res.items : items + res.items
        } catch {
            reachedEnd = true
        }
    }
}

/// Recommend channel: curated shelves from `promote` + a random-recommend grid.
struct RecommendView: View {
    @State private var sections: [PromoSection] = []
    @State private var randoms: [Comic] = []
    @State private var loading = false

    var body: some View {
        Group {
            if sections.isEmpty && loading {
                ProgressView("加载中…").frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(sections) { sec in
                            ShelfRow(section: sec)
                        }
                        if !randoms.isEmpty {
                            Text("随机推荐")
                                .font(.headline)
                                .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 6)
                            ComicGrid(items: randoms, columnsOverride: 2)
                        }
                        if sections.isEmpty && randoms.isEmpty && !loading {
                            Text("暂时没内容").foregroundColor(.secondary).padding(30)
                        }
                    }
                    .padding(.bottom, 12)
                }
                .tabMinimizeOnScroll()
                .refreshable { await load() }
            }
        }
        .task { if sections.isEmpty { await load() } }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        let all = (try? await JMClient.shared.promote()) ?? sections
        sections = all.filter { !$0.title.contains("連載更新") && !$0.title.contains("连载更新") }
        randoms = (try? await JMClient.shared.randomRecommend()) ?? randoms
    }
}

struct ShelfRow: View {
    let section: PromoSection
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(section.title)
                .font(.headline)
                .padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(section.items) { comic in
                        NavigationLink(value: comic.id) {
                            ComicCell(comic: comic, columns: 3)
                                .frame(width: 102)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .padding(.top, 12)
    }
}

// MARK: - categories

struct CategoriesView: View {
    @EnvironmentObject var appState: AppState
    @State private var cats: [JMCategory] = []
    @State private var loading = false
    @State private var loadFailed = false

    var body: some View {
        NavigationStack {
            Group {
                if cats.isEmpty && loading {
                    ProgressView("加载中…")
                } else if loadFailed && cats.isEmpty {
                    VStack(spacing: 12) {
                        Text("分类加载失败").font(.headline)
                        Button("重试") { Task { await load() } }
                    }
                } else {
                    List(cats) { cat in
                        NavigationLink(value: cat) {
                            HStack {
                                Text(cat.name).font(.headline)
                                Spacer()
                                Text("\(cat.totalAlbums) 本").font(.caption).foregroundColor(.secondary)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                    .listStyle(.plain)
                    .tabMinimizeOnScroll()
                    .refreshable { await load() }
                }
            }
            .navigationTitle("分类")
                                    .withDestinations()
        }
        .task { if cats.isEmpty { await load() } }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do { cats = try await JMClient.shared.categories(); loadFailed = false }
        catch { loadFailed = true }
    }
}

struct CategoryListView: View {
    let category: JMCategory
    @State private var items: [Comic] = []
    @State private var page = 0
    @State private var loading = false
    @State private var reachedEnd = false

    var body: some View {
        Group {
            if items.isEmpty && loading {
                ProgressView("加载中…")
            } else {
                ScrollView {
                    ComicGrid(items: items) { Task { await loadMore() } }
                        .padding(.top, 10)
                    if loading { ProgressView().padding() }
                    if !loading && items.isEmpty {
                        Text("这个分类暂时没内容").foregroundColor(.secondary).padding(.top, 40)
                    }
                }
                .tabMinimizeOnScroll()
                .refreshable { page = 0; reachedEnd = false; await loadMore(reset: true) }
            }
        }
        .navigationTitle(category.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { ColumnButton() } }
        .task { if items.isEmpty { await loadMore(reset: true) } }
    }

    private func loadMore(reset: Bool = false) async {
        guard !loading, !reachedEnd else { return }
        loading = true
        defer { loading = false }
        do {
            let res = try await JMClient.shared.categoryFilter(slug: category.slug, page: page + 1)
            if res.items.isEmpty { reachedEnd = true; return }
            page += 1
            items = reset ? res.items : items + res.items
        } catch { reachedEnd = true }
    }
}

// MARK: - search

struct SearchView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var history: SearchHistory
    @State private var query: String = ""
    @State private var items: [Comic] = []
    @State private var page = 0
    @State private var total = 0
    @State private var loading = false
    @State private var searched = false
    @State private var doujinItems: [Comic] = []
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundColor(.secondary)
                        TextField("搜漫画名、作者、标签", text: $query)
                            .focused($focused)
                            .submitLabel(.search)
                            .onSubmit { runSearch(query) }
                        if !query.isEmpty {
                            Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundColor(.secondary) }
                        }
                    }
                    .padding(.horizontal, 10).padding(.vertical, 8)
                    .background(Color.white.opacity(0.08)).cornerRadius(10)
                    Button("搜索") { runSearch(query) }.fontWeight(.semibold)
                }
                .padding(12)

                if !searched && items.isEmpty {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            historyView
                            if !doujinItems.isEmpty {
                                Text("同人推荐").font(.headline)
                                    .padding(.horizontal, 16).padding(.top, 20).padding(.bottom, 6)
                                ComicGrid(items: doujinItems)
                            }
                        }
                        .padding(.bottom, 20)
                    }
                    .tabMinimizeOnScroll()
                    .task { await loadDoujin() }
                } else {
                    ScrollView {
                        if searched && !loading {
                            Text("共 \(total) 本").font(.caption).foregroundColor(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16)
                        }
                        ComicGrid(items: items) { Task { await loadMore() } }
                            .padding(.top, 4)
                        if loading { ProgressView().padding() }
                        if searched && !loading && items.isEmpty {
                            Text("没搜到，换个词试试").foregroundColor(.secondary).padding(.top, 40)
                        }
                    }
                    .tabMinimizeOnScroll()
                }
            }
            .navigationTitle("搜索")
                                    .toolbar { ToolbarItem(placement: .topBarTrailing) { ColumnButton() } }
                        .withDestinations()
        }
        .onChange(of: appState.searchFocusToken) { _ in focused = true }
        .onAppear {
            if let pending = appState.pendingSearch {
                appState.pendingSearch = nil
                query = pending
                runSearch(pending)
            }
        }
    }

    private func loadDoujin() async {
        if !doujinItems.isEmpty { return }
        doujinItems = (try? await JMClient.shared.categoryFilter(slug: "doujin", page: 1))?.items ?? []
    }

    private var historyView: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !history.items.isEmpty {
                HStack {
                    Text("搜索历史").font(.subheadline).foregroundColor(.secondary)
                    Spacer()
                    Button("清除") { history.clear() }.font(.caption)
                }
                FlowChips(items: history.items) { runSearch($0) }
            } else {
                Text("输入关键词开始搜索").foregroundColor(.secondary).padding(.top, 30)
            }
            Spacer()
        }
        .padding(.horizontal, 16).padding(.top, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func runSearch(_ q: String) {
        let q = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        focused = false
        history.add(q)
        query = q
        page = 0; items = []; total = 0; searched = true
        Task { await loadMore() }
    }

    private func loadMore() async {
        guard !loading else { return }
        if !items.isEmpty && items.count >= total && total > 0 { return }
        loading = true
        defer { loading = false }
        do {
            let res = try await JMClient.shared.search(query: query, page: page + 1)
            total = res.total
            page += 1
            items += res.items
        } catch { /* keep what we have */ }
    }
}

struct FlowChips: View {
    let items: [String]
    var onTap: (String) -> Void
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 76), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(items, id: \.self) { s in
                Button { onTap(s) } label: {
                    Text(s).font(.subheadline).lineLimit(1)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Color.white.opacity(0.08)).cornerRadius(16)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

// MARK: - login

struct LoginView: View {
    @EnvironmentObject var account: AccountStore
    @Environment(\.dismiss) private var dismiss
    @State private var name: String = ""
    @State private var password: String = ""
    @State private var busy = false
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("用户名", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("密码", text: $password)
                }
                if let errorText {
                    Section { Text(errorText).foregroundColor(.red).font(.subheadline) }
                }
                Section {
                    Button {
                        Task { await doLogin() }
                    } label: {
                        HStack {
                            Spacer()
                            if busy { ProgressView() } else { Text("登录").fontWeight(.semibold) }
                            Spacer()
                        }
                    }
                    .disabled(busy || name.isEmpty || password.isEmpty)
                }
                Section {
                    Text("登录后可同步账号收藏。密码只保存在这台手机的钥匙串里，不经过任何服务器中转。")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .navigationTitle("账号登录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }

    private func doLogin() async {
        busy = true
        errorText = nil
        do {
            try await account.signIn(username: name.trimmingCharacters(in: .whitespaces),
                                     password: password)
            dismiss()
        } catch let e as JMError {
            if case .server(let msg) = e { errorText = msg }
            else { errorText = "登录失败，检查网络后再试" }
        } catch {
            errorText = "登录失败，检查网络后再试"
        }
        busy = false
    }
}

// MARK: - favorites (local + account)

struct FavoritesView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var account: AccountStore
    @State private var showLogin = false
    @State private var favQuery = ""
    @AppStorage("jm.favSortNewest") private var sortNewest = true

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    accountHeader
                    ProfileStatsCard()
                    CheckinCard()
                    historyEntry
                    favControls
                    if account.isLoggedIn {
                        sectionTitle("账号收藏")
                        if shownCloud.isEmpty && !account.cloudLoading {
                            Text(favQuery.isEmpty ? "账号里还没有收藏" : "没有匹配的收藏").foregroundColor(.secondary)
                                .padding(.horizontal, 16)
                        } else {
                            ComicGrid(items: shownCloud) { Task { await account.loadCloudPage() } }
                        }
                        if account.cloudLoading { ProgressView().padding() }
                    }
                    if !shownLocal.isEmpty {
                        sectionTitle(account.isLoggedIn ? "本地收藏" : "收藏")
                        ComicGrid(items: shownLocal)
                    } else if !favQuery.isEmpty {
                        Text("没有匹配的收藏").foregroundColor(.secondary)
                            .padding(.horizontal, 16).padding(.top, 10)
                    } else if !account.isLoggedIn {
                        VStack(spacing: 10) {
                            Image(systemName: "star").font(.largeTitle).foregroundColor(.secondary)
                            Text("还没有收藏").foregroundColor(.secondary)
                            Text("在详情页点右上角的星星就能收藏").font(.caption).foregroundColor(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 50)
                    }
                }
                .padding(.bottom, 20)
            }
            .tabMinimizeOnScroll()
            .navigationTitle("个人")
                                    .toolbar { ToolbarItem(placement: .topBarTrailing) { ColumnButton() } }
            .withDestinations()
            .sheet(isPresented: $showLogin) { LoginView() }
            .task(id: account.username) {
                await account.ensureCloudCache()
                if !sortNewest { await account.loadAllCloud() }
            }
            .task(id: sortNewest) {
                if !sortNewest { await account.loadAllCloud() }
            }
        }
    }

    private var historyEntry: some View {
        NavigationLink {
            HistoryView()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "clock.arrow.circlepath")
                    .foregroundColor(accentPink)
                Text("观看历史").foregroundColor(.primary)
                Spacer()
                if !library.progress.isEmpty {
                    Text("\(library.progress.count) 本")
                        .font(.subheadline).foregroundColor(.secondary)
                }
                Image(systemName: "chevron.right")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func matchesQuery(_ c: Comic) -> Bool {
        favQuery.isEmpty
            || c.name.localizedCaseInsensitiveContains(favQuery)
            || c.author.localizedCaseInsensitiveContains(favQuery)
    }

    private var shownCloud: [Comic] {
        var r = account.cloudFavorites.filter(matchesQuery)
        // 只在全部加载完后才反转，半截数据反转必然是乱的
        if !sortNewest && account.cloudFullyLoaded { r.reverse() }
        return r
    }

    private var shownLocal: [Comic] {
        library.favorites.filter(matchesQuery).sorted {
            sortNewest ? library.favDate(for: $0) > library.favDate(for: $1)
                       : library.favDate(for: $0) < library.favDate(for: $1)
        }
    }

    private var favControls: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").font(.caption).foregroundColor(.secondary)
                TextField("搜收藏（标题/作者）", text: $favQuery).font(.subheadline)
                if !favQuery.isEmpty {
                    Button { favQuery = "" } label: {
                        Image(systemName: "xmark.circle.fill").foregroundColor(.secondary)
                    }
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 7)
            .glassSurface(Capsule(), interactive: true)
            Menu {
                Button(sortNewest ? "✓ 最近收藏" : "最近收藏") { sortNewest = true }
                Button(sortNewest ? "最早收藏" : "✓ 最早收藏") { sortNewest = false }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "arrow.up.arrow.down").font(.caption)
                    Text(sortNewest ? "最近收藏" : "最早收藏").font(.caption.weight(.semibold))
                }
                .padding(.horizontal, 10).padding(.vertical, 7)
                .glassSurface(Capsule(), interactive: true)
            }
        }
        .padding(.horizontal, 16).padding(.top, 14)
    }

    private var accountHeader: some View {
        HStack(spacing: 10) {
            Image(systemName: account.isLoggedIn ? "person.circle.fill" : "person.circle")
                .font(.title2).foregroundColor(account.isLoggedIn ? accentPink : .secondary)
            if account.isLoggedIn {
                Text(account.username ?? "").font(.headline)
                Spacer()
                Button("注销") { account.signOut() }.font(.subheadline)
            } else {
                Text("未登录").font(.headline)
                Spacer()
                Button("登录同步收藏") { showLogin = true }
                    .font(.subheadline).fontWeight(.semibold)
                    .foregroundColor(accentPink)
            }
        }
        .padding(14)
        .background(Color.white.opacity(0.06))
        .cornerRadius(12)
        .padding(.horizontal, 14).padding(.top, 12)
    }

    private func sectionTitle(_ s: String) -> some View {
        Text(s).font(.headline)
            .padding(.horizontal, 16).padding(.top, 18).padding(.bottom, 8)
    }

}

// MARK: - detail

struct DetailView: View {
    let albumId: String
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var downloads: DownloadManager
    @Environment(\.dismiss) private var dismiss
    @State private var detail: AlbumDetail?
    @State private var loadFailed = false
    @State private var cloudFav: Bool = false
    @State private var favBusy = false
    @State private var tagsExpanded = false

    private var asComic: Comic? {
        guard let d = detail else { return nil }
        return Comic(id: d.id, name: d.name, author: d.authors.first ?? "")
    }

    private var isFav: Bool {
        account.isLoggedIn ? cloudFav : (asComic.map { library.isFavorite($0.id) } ?? false)
    }

    var body: some View {
        Group {
            if let d = detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        header(d)
                        Text(d.name).font(.title3).fontWeight(.bold)
                            .padding(.horizontal, 16).padding(.top, 12)
                        if !d.authors.isEmpty {
                            authorRow(d)
                        }
                        Text(metaLine(d)).font(.caption).foregroundColor(.secondary)
                            .padding(.horizontal, 16).padding(.top, 5)
                        if !d.tags.isEmpty {
                            VStack(alignment: .leading, spacing: 8) {
                                FlowChips(items: tagsExpanded ? d.tags : Array(d.tags.prefix(8))) { appState.requestSearch($0) }
                                if d.tags.count > 8 {
                                    Button { withAnimation { tagsExpanded.toggle() } } label: {
                                        Text(tagsExpanded ? "收起标签" : "展开全部 \(d.tags.count) 个标签")
                                            .font(.caption.weight(.semibold)).foregroundColor(accentPink)
                                    }
                                }
                            }
                            .padding(.horizontal, 16).padding(.top, 12)
                        }
                        readButton(d)
                        downloadButton(d)
                        chapterList(d)
                        if !d.description.isEmpty {
                            Text(d.description).font(.subheadline).foregroundColor(.secondary)
                                .padding(.horizontal, 16).padding(.vertical, 14)
                        }
                    }
                }
            } else if loadFailed {
                VStack(spacing: 12) {
                    Text("加载失败").font(.headline)
                    Button("重试") { Task { await load() } }
                }
            } else {
                ProgressView("加载中…")
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if asComic != nil {
                    Button {
                        Task { await toggleFav() }
                    } label: {
                        Image(systemName: isFav ? "star.fill" : "star")
                            .foregroundColor(isFav ? accentPink : .primary)
                    }
                    .disabled(favBusy)
                }
            }
        }
        .swipeAnywhereBack { dismiss() }
        .task { if detail == nil { await load() } }
            }

    private func toggleFav() async {
        guard let comic = asComic else { return }
        if account.isLoggedIn {
            guard !cloudFav else { return }   // removing cloud favorites is web-side for now
            favBusy = true
            defer { favBusy = false }
            do {
                try await JMClient.shared.addFavorite(albumId: comic.id)
                cloudFav = true
            } catch { /* leave state */ }
        } else {
            library.toggleFavorite(comic)
        }
    }

    private func header(_ d: AlbumDetail) -> some View {
        ZStack {
            RemoteImage(path: ImagePipe.coverPath(albumId: d.id))
                .frame(height: 250).clipped().blur(radius: 24).opacity(0.55)
            RemoteImage(path: ImagePipe.coverPath(albumId: d.id))
                .frame(width: 150, height: 200).clipped().cornerRadius(10)
                .shadow(radius: 12)
                .padding(.vertical, 14)
        }
        .frame(maxWidth: .infinity)
        .background(Color.black)
    }

    private func authorRow(_ d: AlbumDetail) -> some View {
        HStack(alignment: .center, spacing: 8) {
            Text("作者").font(.caption).foregroundColor(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(d.authors, id: \.self) { a in
                        NavigationLink {
                            AuthorWorksView(author: a)
                        } label: {
                            Text(a)
                                .font(.caption.weight(.semibold))
                                .foregroundColor(accentPink)
                                .padding(.horizontal, 9).padding(.vertical, 4)
                                .background(accentPink.opacity(0.12))
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.horizontal, 16).padding(.top, 7)
    }

    private func metaLine(_ d: AlbumDetail) -> String {
        var parts: [String] = []
        if !d.totalViews.isEmpty { parts.append("浏览 \(d.totalViews)") }
        if !d.likes.isEmpty { parts.append("点赞 \(d.likes)") }
        if d.chapters.isEmpty { parts.append("共 \(d.totalPhotos) 页") }
        else { parts.append("共 \(d.chapters.count) 话") }
        return parts.joined(separator: " · ")
    }

    private func readButton(_ d: AlbumDetail) -> some View {
        let prog = library.progressFor(albumId: d.id)
        let targetChapter = prog?.chapterId ?? d.firstChapterId
        let targetPage = (prog != nil && prog!.chapterId == targetChapter) ? prog!.page : 0
        let route = ReaderRoute(albumId: d.id, albumName: d.name, chapters: d.chapters,
                                startChapterId: targetChapter, startPage: targetPage)
        return NavigationLink(value: route) {
            Text(prog == nil ? "开始阅读" : "继续阅读 · \(prog!.chapterName) 第\(prog!.page + 1)页")
                .font(.headline).foregroundColor(.white)
                .frame(maxWidth: .infinity).padding(.vertical, 13)
                .background(accentPink).cornerRadius(12)
        }
        .padding(.horizontal, 16).padding(.top, 16)
    }

    @ViewBuilder
    private func downloadButton(_ d: AlbumDetail) -> some View {
        if downloads.store.record(for: d.id) != nil {
            Label("已下载，可在「文件」App 里打开", systemImage: "checkmark.circle.fill")
                .font(.subheadline).foregroundColor(.green)
                .padding(.horizontal, 16).padding(.top, 12)
        } else if let job = downloads.job(for: d.id) {
            DownloadProgressStrip(job: job)
                .padding(.horizontal, 16).padding(.top, 14)
        } else {
            Button {
                downloads.enqueue(albumId: d.id, title: d.name)
            } label: {
                Label("下载全本", systemImage: "arrow.down.circle")
                    .font(.headline).foregroundColor(accentPink)
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(Color.white.opacity(0.08)).cornerRadius(12)
            }
            .padding(.horizontal, 16).padding(.top, 10)
        }
    }

    @ViewBuilder
    private func chapterList(_ d: AlbumDetail) -> some View {
        if !d.chapters.isEmpty {
            Text("章节").font(.subheadline).foregroundColor(.secondary)
                .padding(.horizontal, 16).padding(.top, 18).padding(.bottom, 4)
            ForEach(d.chapters) { ch in
                let prog = library.progressFor(albumId: d.id)
                NavigationLink(value: ReaderRoute(albumId: d.id, albumName: d.name, chapters: d.chapters,
                                                  startChapterId: ch.id,
                                                  startPage: prog?.chapterId == ch.id ? prog!.page : 0)) {
                    HStack {
                        Text(ch.name).font(.body)
                            .foregroundColor(prog?.chapterId == ch.id ? accentPink : .primary)
                        Spacer()
                        if prog?.chapterId == ch.id {
                            Text("已看 \(prog!.page + 1) 页").font(.caption).foregroundColor(.secondary)
                        }
                        Image(systemName: "chevron.right").font(.caption).foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Divider().padding(.leading, 16)
            }
        }
    }

    private func load() async {
        loadFailed = false
        do {
            let d = try await JMClient.shared.album(id: albumId)
            detail = d
            cloudFav = d.isFavorite
        } catch { loadFailed = true }
    }
}

struct DownloadProgressStrip: View {
    @ObservedObject var job: DownloadJob
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(job.phase).font(.caption).foregroundColor(.secondary).lineLimit(1)
                Spacer()
                Text("\(job.donePages)/\(job.totalPages) 页").font(.caption).foregroundColor(.secondary)
            }
            ProgressView(value: job.fraction).tint(accentPink)
        }
    }
}


// MARK: - profile stats strip (个人 page)

struct ProfileStatsCard: View {
    @EnvironmentObject var account: AccountStore

    var body: some View {
        if account.isLoggedIn, let s = account.stats {
            HStack(spacing: 0) {
                stat("经验值", "\(s.exp)/\(s.nextLevelExp)")
                stat("LV", "\(s.level)")
                stat("J Coins", "\(s.coin)")
                stat("可收藏数量", "\(s.favoritesCount)/\(s.favoritesMax)")
            }
            .padding(.vertical, 13).padding(.horizontal, 6)
            .glassSurface(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(.horizontal, 16)
            .padding(.top, 14)
        }
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 4) {
            Text(label).font(.caption).foregroundColor(.secondary)
            Text(value)
                .font(.system(.subheadline, design: .rounded).weight(.bold))
                .foregroundColor(accentPink)
                .lineLimit(1).minimumScaleFactor(0.65)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - daily check-in card (个人 page)

struct CheckinCard: View {
    @EnvironmentObject var account: AccountStore
    @State private var info: DailyInfo?
    @State private var loading = false
    @State private var checking = false
    @State private var notice: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "calendar.badge.checkmark")
                    .foregroundColor(accentPink)
                Text("每日签到").font(.headline)
                Spacer()
                if let info {
                    Text(info.eventName).font(.caption).foregroundColor(.secondary)
                }
            }
            if !account.isLoggedIn {
                Text("登录后每天签到，领金币和经验奖励").font(.subheadline).foregroundColor(.secondary)
            } else if let info {
                calendar(info)
                HStack(alignment: .center, spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("本月已签 \(info.signedCount) 天 · 进度 \(info.progress)")
                            .font(.caption).foregroundColor(.secondary)
                        Text("连签3天 +\(info.threeCoin) 金币 · 连签7天 +\(info.sevenCoin) 金币 · 补签 \(info.makeupUsed)/\(info.makeupLimit)")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    if info.todaySigned {
                        Label("今日已签", systemImage: "checkmark.circle.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(.secondary)
                    } else {
                        Button {
                            Task { await checkIn() }
                        } label: {
                            if checking { ProgressView() }
                            else { Text("立即签到").font(.subheadline.weight(.bold)) }
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(accentPink)
                        .disabled(checking)
                    }
                }
                if let notice {
                    Text(notice).font(.caption).foregroundColor(accentPink)
                }
            } else if loading {
                HStack { Spacer(); ProgressView(); Spacer() }
            } else {
                Text("签到信息没加载出来，进页面会自动重试").font(.caption).foregroundColor(.secondary)
            }
        }
        .padding(14)
        .glassSurface(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .task(id: account.username) { await load() }
    }

    private func calendar(_ info: DailyInfo) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 5), count: 7), spacing: 5) {
            ForEach(info.weeks.flatMap { $0 }) { day in
                dayCell(day, today: day.date == info.todayString)
            }
        }
    }

    private func dayCell(_ day: DailyDay, today: Bool) -> some View {
        ZStack(alignment: .topTrailing) {
            ZStack {
                Circle()
                    .fill(day.signed == true ? accentPink : (today ? accentPink.opacity(0.15) : Color.secondary.opacity(0.10)))
                if today && day.signed != true {
                    Circle().strokeBorder(accentPink, lineWidth: 1.5)
                }
                if day.signed == true {
                    Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                } else {
                    Text(day.date)
                        .font(.system(size: 10).weight(today ? .bold : .regular))
                        .foregroundColor(day.signed == false && !today ? .secondary : .primary)
                }
            }
            if day.bonus {
                Image(systemName: "star.fill")
                    .font(.system(size: 6))
                    .foregroundColor(.yellow)
                    .offset(x: 2, y: -2)
            }
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(1, contentMode: .fit)
    }

    private func load() async {
        guard account.isLoggedIn, let uid = AccountStore.currentUserId else { return }
        loading = true
        defer { loading = false }
        info = try? await JMClient.shared.dailyInfo(userId: uid)
    }

    private func checkIn() async {
        guard let uid = AccountStore.currentUserId else { return }
        checking = true
        defer { checking = false }
        do {
            let reward = try await JMClient.shared.dailyCheck(userId: uid, dailyId: info?.dailyId ?? 0)
            account.applyCheckinReward(reward)
            info = try? await JMClient.shared.dailyInfo(userId: uid)
            notice = info?.todaySigned == true ? "签到成功\(reward.isEmpty ? "" : "：\(reward)")" : "已提交，请稍后刷新看看"
        } catch {
            notice = "签到失败：\(error.localizedDescription)"
        }
    }
}


// MARK: - author works page

struct AuthorWorksView: View {
    let author: String
    @State private var items: [Comic] = []
    @State private var page = 0
    @State private var total = 0
    @State private var loading = false
    @State private var done = false

    var body: some View {
        ScrollView {
            if !items.isEmpty {
                ComicGrid(items: items) { Task { await loadMore() } }
                    .padding(.top, 6)
            }
            if loading {
                ProgressView().padding()
            } else if done && items.isEmpty {
                Text("没找到这位作者的作品").foregroundColor(.secondary).padding(.top, 40)
            }
        }
        .tabMinimizeOnScroll()
        .navigationTitle(author)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { ColumnButton() } }
        .withDestinations()
        .task { if page == 0 { await loadMore() } }
    }

    private func loadMore() async {
        guard !loading, !done else { return }
        loading = true
        defer { loading = false }
        let next = page + 1
        guard let r = try? await JMClient.shared.search(query: author, page: next) else { done = true; return }
        total = r.total
        // search matches broadly; keep only works actually credited to this author
        let credited = r.items.filter { $0.author.contains(author) }
        for c in credited where !items.contains(where: { $0.id == c.id }) {
            items.append(c)
        }
        page = next
        if r.items.isEmpty || items.count >= total { done = true }
    }
}


// MARK: - 观看历史（数据来自 LibraryStore.progress：阅读器一直在记录的续读进度）
struct HistoryView: View {
    @EnvironmentObject var library: LibraryStore
    @State private var showClearConfirm = false

    private struct Row: Identifiable {
        let albumId: String
        let entry: LibraryStore.ProgressEntry
        var id: String { albumId }
    }

    private var entries: [Row] {
        library.progress
            .map { Row(albumId: $0.key, entry: $0.value) }
            .sorted { $0.entry.updatedAt > $1.entry.updatedAt }
    }

    private func relTime(_ ts: Double) -> String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return f.localizedString(for: Date(timeIntervalSince1970: ts), relativeTo: Date())
    }

    var body: some View {
        List {
            ForEach(entries) { item in
                NavigationLink(value: ReaderRoute(
                    albumId: item.albumId,
                    albumName: item.entry.albumName ?? "",
                    chapters: [],
                    startChapterId: item.entry.chapterId,
                    startPage: item.entry.page
                )) {
                    HStack(spacing: 12) {
                        RemoteImage(path: ImagePipe.coverPath(albumId: item.albumId), targetWidth: 200)
                            .frame(width: 46, height: 61)
                            .clipped()
                            .cornerRadius(6)
                        VStack(alignment: .leading, spacing: 3) {
                            Text((item.entry.albumName ?? "").isEmpty ? "未知标题" : item.entry.albumName!)
                                .font(.subheadline).fontWeight(.semibold)
                                .lineLimit(1)
                            Text("\(item.entry.chapterName.isEmpty ? "单话" : item.entry.chapterName) · 看到第 \(item.entry.page + 1) 页")
                                .font(.caption).foregroundColor(.secondary)
                                .lineLimit(1)
                            Text(relTime(item.entry.updatedAt))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 2)
                }
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        library.removeProgress(albumId: item.albumId)
                    } label: {
                        Label("删除", systemImage: "trash")
                    }
                }
            }
        }
        .navigationTitle("观看历史")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !entries.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("清空") { showClearConfirm = true }
                }
            }
        }
        .confirmationDialog("清空全部观看历史？", isPresented: $showClearConfirm, titleVisibility: .visible) {
            Button("清空", role: .destructive) { library.clearProgress() }
            Button("取消", role: .cancel) {}
        }
        .overlay {
            if entries.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.largeTitle).foregroundColor(.secondary)
                    Text("还没有观看记录").foregroundColor(.secondary)
                    Text("看过的漫画会自动出现在这里，点开接着看")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
        }
        .task {
            // 老记录没有存标题的，逐个回填
            for item in entries where (item.entry.albumName ?? "").isEmpty {
                if let d = try? await JMClient.shared.album(id: item.albumId) {
                    library.backfillProgressName(albumId: item.albumId, name: d.name)
                }
            }
        }
    }
}
