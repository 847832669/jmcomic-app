import SwiftUI

@main
struct JMReaderApp: App {
    @StateObject private var appState = AppState()
    @StateObject private var library = LibraryStore()
    @StateObject private var history = SearchHistory()
    @StateObject private var account = AccountStore()
    @StateObject private var downloads = DownloadManager()

    var body: some Scene {
        WindowGroup {
            TabView(selection: $appState.selectedTab) {
                HomeView()
                    .tabSwipe(appState)
                    .tabItem { Label("首页", systemImage: "house.fill") }
                    .tag(0)
                CategoriesView()
                    .tabSwipe(appState)
                    .tabItem { Label("分类", systemImage: "square.grid.2x2.fill") }
                    .tag(2)
                FavoritesView()
                    .tabSwipe(appState)
                    .tabItem { Label("个人", systemImage: "person.fill") }
                    .tag(3)
                SearchView()
                    .tabSwipe(appState)
                    .tabItem { Label("搜索", systemImage: "magnifyingglass") }
                    .tag(1)
            }
            .tint(accentPink)
            .tabMinimizeOnScroll()
            .environmentObject(appState)
            .environmentObject(library)
            .environmentObject(history)
            .environmentObject(account)
            .environmentObject(downloads)
        }
    }
}
