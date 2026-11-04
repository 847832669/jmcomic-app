import SwiftUI

@main
struct JMReaderApp: App {
    @StateObject private var appState = AppState()
    @StateObject private var library = LibraryStore()
    @StateObject private var history = SearchHistory()
    @StateObject private var account = AccountStore()
    @StateObject private var downloads = DownloadManager()
    @StateObject private var blockStore = BlockTagStore()
    @StateObject private var readingStats = ReadingStatsStore()

    var body: some Scene {
        WindowGroup {
            TabView(selection: $appState.selectedTab) {
                HomeView()
                    .tabItem { Label("首页", systemImage: "house.fill") }
                    .tag(0)
                CategoriesView()
                    .tabItem { Label("分类", systemImage: "square.grid.2x2.fill") }
                    .tag(2)
                FavoritesView()
                    .tabItem { Label("个人", systemImage: "person.fill") }
                    .tag(3)
                SearchView()
                    .tabItem { Label("搜索", systemImage: "magnifyingglass") }
                    .tag(1)
            }
            .tint(accentPink)
            .tabMinimizeOnScroll()
            .task(id: account.username) {
                await AutoCheckIn.runIfNeeded(account: account)
                await LineStore.shared.refreshIfStale()
            }
            .environmentObject(appState)
            .environmentObject(library)
            .environmentObject(history)
            .environmentObject(account)
            .environmentObject(downloads)
            .environmentObject(blockStore)
            .environmentObject(readingStats)
        }
    }
}
