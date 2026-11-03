import SwiftUI

struct ReaderRoute: Hashable {
    var albumId: String
    var albumName: String
    var chapters: [ChapterRef]
    var startChapterId: String
    var startPage: Int
}

struct ReaderView: View {
    let route: ReaderRoute
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var chapterId: String = ""
    @State private var chapterName: String = ""
    @State private var pages: [String] = []
    @State private var seriesFromAPI: [ChapterRef] = []
    @State private var currentPage: Int = 0
    @State private var showChrome = true
    @State private var loading = true
    @State private var loadFailed = false
    @State private var jumpTarget: Int?
    @State private var sliderValue: Double = 0
    @State private var didInit = false
    @State private var displayIndex: Int = 0
    @AppStorage("reader.mode") private var readerMode = "scroll"   // scroll | page
    @AppStorage("reader.rtl") private var readerRTL = false

    private var paged: Bool { readerMode == "page" }

    private var chapters: [ChapterRef] {
        !route.chapters.isEmpty ? route.chapters : seriesFromAPI
    }
    private var chapterIndex: Int? { chapters.firstIndex { $0.id == chapterId } }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            content
            if showChrome { chromeOverlay }
            if loading { ProgressView().tint(.white) }
        }
        .toolbar(.hidden, for: .navigationBar)
        .restoreSwipeBack()
        .swipeAnywhereBack { dismiss() }
        .onChange(of: readerRTL) { _ in
            if paged { displayIndex = displayIndex(forLogical: currentPage) }
        }
        .onChange(of: readerMode) { _ in
            if paged { displayIndex = displayIndex(forLogical: currentPage) }
        }
                        .task {
            if !didInit {
                didInit = true
                await openChapter(id: route.startChapterId, page: route.startPage)
            }
        }
    }

    private var content: some View {
        ScrollViewReader { proxy in
            Group {
                if paged {
                    pagedContent
                } else {
                    scrollContent
                }
            }
            .onChange(of: jumpTarget) { target in
                if let target {
                    if paged {
                        displayIndex = displayIndex(forLogical: target)
                    } else {
                        withAnimation { proxy.scrollTo(target, anchor: .top) }
                    }
                    jumpTarget = nil
                }
            }
        }
    }

    private var scrollContent: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(Array(pages.enumerated()), id: \.offset) { idx, file in
                    ReaderPageView(chapterId: chapterId, file: file)
                        .id(idx)
                        .onAppear {
                            currentPage = idx
                            sliderValue = Double(idx)
                            saveProgress()
                            prefetch(after: idx)
                        }
                }
                if loadFailed {
                    Text("这一话加载失败，下拉或重进试试").foregroundColor(.gray)
                        .padding(.vertical, 60)
                } else if !pages.isEmpty {
                    Text("— 本话完 —").foregroundColor(.gray).font(.footnote)
                        .padding(.vertical, 34)
                }
            }
        }
        .simultaneousGesture(TapGesture().onEnded {
            withAnimation(.easeInOut(duration: 0.2)) { showChrome.toggle() }
        })
    }

    // MARK: - paged (horizontal) mode

    private func displayIndex(forLogical page: Int) -> Int {
        readerRTL ? max(pages.count - 1 - page, 0) : page
    }
    private func logicalPage(forDisplay idx: Int) -> Int {
        readerRTL ? max(pages.count - 1 - idx, 0) : idx
    }

    private var pagedContent: some View {
        TabView(selection: $displayIndex) {
            ForEach(pages.indices, id: \.self) { dIdx in
                let logical = logicalPage(forDisplay: dIdx)
                ZStack {
                    ReaderPageView(chapterId: chapterId, file: pages[logical])
                    HStack(spacing: 0) {
                        tapZone { stepPage(displayDelta: readerRTL ? 1 : -1) }
                        tapZone {
                            withAnimation(.easeInOut(duration: 0.2)) { showChrome.toggle() }
                        }
                        tapZone { stepPage(displayDelta: readerRTL ? -1 : 1) }
                    }
                }
                .tag(dIdx)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .onChange(of: displayIndex) { newValue in
            let logical = logicalPage(forDisplay: newValue)
            if logical != currentPage {
                currentPage = logical
                sliderValue = Double(logical)
                saveProgress()
                prefetch(after: logical)
            }
        }
    }

    private func tapZone(action: @escaping () -> Void) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onTapGesture { action() }
    }

    private func stepPage(displayDelta: Int) {
        let next = displayIndex + displayDelta
        guard pages.indices.contains(next) else { return }
        withAnimation { displayIndex = next }
    }

    private var chromeOverlay: some View {
        VStack {
            HStack(spacing: 10) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left").font(.headline)
                        .padding(9).glassSurface(Circle(), interactive: true)
                }
                Text(chapterName.isEmpty ? route.albumName : chapterName)
                    .font(.subheadline).lineLimit(1)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .glassSurface(RoundedRectangle(cornerRadius: 10))
                Spacer()
                Menu {
                    Button(paged ? "上下卷轴" : "✓ 上下卷轴") { readerMode = "scroll" }
                    Button(paged ? "✓ 横向翻页" : "横向翻页") { readerMode = "page" }
                    if paged {
                        Divider()
                        Button(readerRTL ? "从左往右" : "✓ 从左往右") { readerRTL = false }
                        Button(readerRTL ? "✓ 从右往左" : "从右往左") { readerRTL = true }
                    }
                } label: {
                    Image(systemName: "rectangle.split.2x1")
                        .font(.subheadline)
                        .padding(9)
                        .glassSurface(Circle(), interactive: true)
                }
                Text(pages.isEmpty ? "" : "\(currentPage + 1) / \(pages.count)")
                    .font(.caption)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .glassSurface(RoundedRectangle(cornerRadius: 10))
            }
            .foregroundColor(.white)
            .padding(.horizontal, 12)
            Spacer()
            // Single slim row, about as tall as the round chrome buttons:
            // prev / page count / slider / next (user-approved mock, 2026-11-03).
            HStack(spacing: 10) {
                Button("‹ 上一话") { stepChapter(-1) }
                    .disabled(chapterIndex == nil || chapterIndex == 0)
                if !pages.isEmpty {
                    Text("\(currentPage + 1) / \(pages.count)")
                        .font(.footnote).foregroundColor(accentPink)
                        .fixedSize()
                    Slider(value: $sliderValue, in: 0...Double(max(pages.count - 1, 1)), step: 1) { editing in
                        if !editing { jumpTarget = Int(sliderValue) }
                    }
                    .tint(accentPink)
                }
                Button("下一话 ›") { stepChapter(1) }
                    .disabled(chapterIndex == nil || chapterIndex == chapters.count - 1)
            }
            .font(.subheadline).foregroundColor(.white)
            .padding(.horizontal, 16).padding(.vertical, 10)
            .glassSurface(RoundedRectangle(cornerRadius: 30))
            .padding(.horizontal, 10).padding(.bottom, 8)
        }
        .transition(.opacity)
    }

    // MARK: - actions

    private func openChapter(id: String, page: Int) async {
        loading = true
        loadFailed = false
        do {
            let ch = try await JMClient.shared.chapter(id: id)
            chapterId = id
            chapterName = ch.name
            pages = ch.images
            if !ch.series.isEmpty { seriesFromAPI = ch.series }
            currentPage = 0
            sliderValue = 0
            displayIndex = displayIndex(forLogical: 0)
            loading = false
            if page > 0 && page < ch.images.count {
                // give layout a beat, then jump
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                    currentPage = page
                    sliderValue = Double(page)
                    jumpTarget = page
                }
            }
            if !ch.images.isEmpty { prefetch(after: -1) }
        } catch {
            loading = false
            loadFailed = true
        }
    }

    private func stepChapter(_ delta: Int) {
        guard let idx = chapterIndex else { return }
        let next = idx + delta
        guard chapters.indices.contains(next) else { return }
        let target = chapters[next]
        Task { await openChapter(id: target.id, page: 0) }
    }

    private func saveProgress() {
        guard !chapterId.isEmpty else { return }
        library.saveProgress(albumId: route.albumId, chapterId: chapterId,
                             chapterName: chapterName, page: currentPage,
                             albumName: route.albumName)
    }

    private func prefetch(after idx: Int) {
        for j in (idx + 1)...(idx + 3) {
            guard pages.indices.contains(j) else { return }
            let file = pages[j]
            let stem = (file as NSString).deletingPathExtension
            ImagePipe.shared.preload(path: ImagePipe.pagePath(chapterId: chapterId, file: file),
                                     scrambleNum: ImagePipe.splitNum(chapterId: chapterId, stem: stem))
        }
    }
}

private struct ReaderPageView: View {
    let chapterId: String
    let file: String
    @State private var image: UIImage?

    private var stem: String { (file as NSString).deletingPathExtension }

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
            } else {
                Color.white.opacity(0.05)
                    .aspectRatio(0.72, contentMode: .fit)
                    .overlay(ProgressView().tint(.gray))
            }
        }
        .task(id: chapterId + file) {
            image = await ImagePipe.shared.load(
                path: ImagePipe.pagePath(chapterId: chapterId, file: file),
                scrambleNum: ImagePipe.splitNum(chapterId: chapterId, stem: stem))
        }
    }
}
