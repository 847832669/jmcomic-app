import Foundation
import SwiftUI
import Combine
import UIKit

// MARK: - minimal PDF writer (one image per page, streamed page by page)

enum PdfWriter {
    /// entries are (label, JPEG file on disk) in reading order; images are loaded one at a time.
    static func write(entries: [(name: String, file: URL)], to url: URL) throws {
        guard let first = entries.first,
              let firstImg = UIImage(contentsOfFile: first.file.path) else {
            throw NSError(domain: "PdfWriter", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "没有可写入的页面"])
        }
        UIGraphicsBeginPDFContextToFile(url.path, CGRect(origin: .zero, size: firstImg.size), nil)
        defer { UIGraphicsEndPDFContext() }
        for e in entries {
            // One autorelease pool per page: without it every decoded page
            // (~12 MB RGBA for a 2000×3000 scan) lived until the whole album
            // finished, which jetsammed the app on long albums.
            autoreleasepool {
                guard let img = UIImage(contentsOfFile: e.file.path) else { return }
                UIGraphicsBeginPDFPageWithInfo(CGRect(origin: .zero, size: img.size), nil)
                img.draw(in: CGRect(origin: .zero, size: img.size))
            }
        }
    }
}

// MARK: - completed downloads store

struct DownloadedRecord: Codable, Identifiable {
    var albumId: String
    var title: String
    var chapters: Int
    var pages: Int
    var bytes: Int64
    var fileName: String
    var date: Double
    var id: String { albumId }
    var fileURL: URL { DownloadedStore.documents.appendingPathComponent(fileName) }
}

final class DownloadedStore: ObservableObject {
    @Published private(set) var records: [DownloadedRecord] = []
    private let storeURL: URL
    static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }

    init() {
        storeURL = Self.documents.appendingPathComponent("downloads.json")
        if let data = try? Data(contentsOf: storeURL),
           let list = try? JSONDecoder().decode([DownloadedRecord].self, from: data) {
            records = list
        }
    }

    func record(for albumId: String) -> DownloadedRecord? { records.first { $0.albumId == albumId } }

    func add(_ r: DownloadedRecord) {
        records.removeAll { $0.albumId == r.albumId }
        records.insert(r, at: 0)
        persist()
    }

    func delete(_ r: DownloadedRecord) {
        try? FileManager.default.removeItem(at: r.fileURL)
        records.removeAll { $0.albumId == r.albumId }
        persist()
    }

    /// On-disk footprint of the downloaded PDFs, for the storage page.
    static func diskUsage() -> (bytes: Int64, files: Int) {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: documents, includingPropertiesForKeys: [.fileSizeKey]) else {
            return (0, 0)
        }
        var bytes: Int64 = 0
        var files = 0
        for url in entries where url.pathExtension.lowercased() == "pdf" {
            if let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize {
                bytes += Int64(size)
                files += 1
            }
        }
        return (bytes, files)
    }

    private func persist() {
        if let d = try? JSONEncoder().encode(records) { try? d.write(to: storeURL, options: .atomic) }
    }
}

// MARK: - download jobs

final class DownloadJob: ObservableObject, Identifiable {
    let id = UUID()
    let albumId: String
    let title: String
    enum State { case queued, working, done, failed, cancelled }
    @Published var state: State = .queued
    @Published var totalPages = 0
    @Published var donePages = 0
    @Published var bytes: Int64 = 0
    @Published var speedBps: Double = 0
    @Published var phase: String = "排队中"

    init(albumId: String, title: String) {
        self.albumId = albumId
        self.title = title
    }

    var fraction: Double { totalPages > 0 ? Double(donePages) / Double(totalPages) : 0 }
}

@MainActor
final class DownloadManager: ObservableObject {
    @Published var jobs: [DownloadJob] = []
    let store = DownloadedStore()
    private var running = false
    private var currentTask: Task<Void, Never>?
    private var currentJobId: UUID?
    private var cancellables = Set<AnyCancellable>()

    init() {
        store.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)
    }

    func job(for albumId: String) -> DownloadJob? {
        jobs.first { $0.albumId == albumId && ($0.state == .queued || $0.state == .working) }
    }

    func enqueue(albumId: String, title: String) {
        if job(for: albumId) != nil { return }
        jobs.removeAll { $0.albumId == albumId && $0.state != .done }
        let j = DownloadJob(albumId: albumId, title: title)
        jobs.append(j)
        pump()
    }

    func cancel(_ job: DownloadJob) {
        if job.id == currentJobId {
            job.state = .cancelled
            currentTask?.cancel()
        } else {
            job.state = .cancelled
        }
    }

    func clearFinished() {
        jobs.removeAll { $0.state == .done || $0.state == .cancelled || $0.state == .failed }
    }

    private func pump() {
        guard !running, let next = jobs.first(where: { $0.state == .queued }) else { return }
        running = true
        currentJobId = next.id
        currentTask = Task { [weak self] in
            await self?.run(next)
            self?.running = false
            self?.currentJobId = nil
            self?.pump()
        }
    }

    private static func sanitize(_ s: String) -> String {
        let bad = CharacterSet(charactersIn: "/:\\?%*|\"<>")
        let cleaned = s.components(separatedBy: bad).joined(separator: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return String(cleaned.prefix(60)).isEmpty ? "comic" : String(cleaned.prefix(60))
    }

    private func run(_ job: DownloadJob) async {
        let fm = FileManager.default
        // Keyed by album only (not by job UUID): a new job for the same
        // album reuses the page folder left by the failed attempt, which is
        // what makes "retry" resume instead of starting over.
        let tmp = fm.temporaryDirectory.appendingPathComponent("jm-dl-\(job.albumId)")
        // Pages are written as plain JPEG files under this folder (not held
        // as [(URL)] in memory) and the folder is kept on failure, so
        // "retry" resumes instead of re-downloading the whole album.
        let pagesDir = tmp.appendingPathComponent("pages", isDirectory: true)
        job.state = .working
        job.phase = "获取章节列表…"
        do {
            let album = try await JMClient.shared.album(id: job.albumId)
            let chapters: [ChapterRef] = album.chapters.isEmpty
                ? [ChapterRef(id: album.id, name: album.name, sort: 1)]
                : album.chapters
            try fm.createDirectory(at: pagesDir, withIntermediateDirectories: true)
            defer {
                // Completed or cancelled: drop the scratch folder. Failed:
                // keep it for the retry (the storage page reports its size).
                if job.state == .done || job.state == .cancelled {
                    try? fm.removeItem(at: tmp)
                }
            }

            // Chapter lists are fetched in windows of 4 instead of one at a
            // time: a 40-chapter album spent 40 sequential round trips
            // (each up to 12 s on a bad line) before the first image moved.
            var plan: [(chapter: ChapterRef, images: [String])] = []
            var totalPages = 0
            for window in stride(from: 0, to: chapters.count, by: 4) {
                if Task.isCancelled { throw CancellationError() }
                let slice = Array(chapters[window..<min(window + 4, chapters.count)])
                let results = await withTaskGroup(of: (Int, [String]?).self) { group in
                    for (off, ch) in slice.enumerated() {
                        group.addTask {
                            let cd = try? await JMClient.shared.chapter(id: ch.id)
                            return (window + off, cd?.images)
                        }
                    }
                    var out: [(Int, [String]?)] = []
                    for await r in group { out.append(r) }
                    return out.sorted { $0.0 < $1.0 }
                }
                for (i, images) in results {
                    // A chapter whose page list fails must not abort the
                    // whole album: keep the rest and report at the end.
                    plan.append((chapters[i], images ?? []))
                    totalPages += images?.count ?? 0
                }
            }
            job.totalPages = max(totalPages, 1)
            guard totalPages > 0 else {
                job.state = .failed
                job.phase = "没有可下载的页面"
                return
            }

            // Resume: count what a previous attempt already wrote.
            let existing = (try? fm.contentsOfDirectory(at: pagesDir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
            var existingBytes: Int64 = 0
            for url in existing {
                existingBytes += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            }
            job.donePages = existing.count
            job.bytes = existingBytes
            if existingBytes > 0 { job.phase = "继续上次下载…" }

            var samples: [(Date, Int64)] = [(Date(), job.bytes)]
            let multi = plan.count > 1

            for (ci, item) in plan.enumerated() {
                job.phase = multi ? "下载 \(item.chapter.name)（\(ci + 1)/\(plan.count)）" : "下载中…"
                let files = item.images
                var idx = 0
                while idx < files.count {
                    if Task.isCancelled { throw CancellationError() }
                    let batch = Array(files[idx..<min(idx + 3, files.count)])
                    let base = idx
                    let chapId = item.chapter.id
                    let results: [(Int, Int64?)] = await withTaskGroup(of: (Int, Int64?).self) { group in
                        for (off, file) in batch.enumerated() {
                            let pageIndex = base + off
                            group.addTask {
                                let dest = pagesDir.appendingPathComponent("\(ci)-\(pageIndex).jpg")
                                if let size = (try? dest.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size > 0 {
                                    return (pageIndex, Int64(size))   // already fetched
                                }
                                // Download at a capped width and encode at
                                // quality 88: the old path decoded every page
                                // at full resolution and re-encoded at 0.97,
                                // so a download of N pages held N full-size
                                // RGBA buffers and produced PDFs larger than
                                // the originals.
                                let stem = (file as NSString).deletingPathExtension
                                let num = ImagePipe.splitNum(chapterId: chapId, stem: stem)
                                guard let data = await ImagePipe.shared.pageJPEG(
                                    path: ImagePipe.pagePath(chapterId: chapId, file: file),
                                    scrambleNum: num) else { return (pageIndex, nil) }
                                do {
                                    try data.write(to: dest, options: .atomic)
                                    return (pageIndex, Int64(data.count))
                                } catch { return (pageIndex, nil) }
                            }
                        }
                        var collected: [(Int, Int64?)] = []
                        for await r in group { collected.append(r) }
                        return collected
                    }
                    for (pageIndex, size) in results.sorted(by: { $0.0 < $1.0 }) {
                        job.donePages += 1
                        if let size {
                            job.bytes += size
                        }
                        let now = Date()
                        samples.append((now, job.bytes))
                        samples.removeAll { now.timeIntervalSince($0.0) > 8 }
                        if let first = samples.first, samples.count > 1 {
                            let dt = now.timeIntervalSince(first.0)
                            if dt > 0.5 { job.speedBps = Double(job.bytes - first.1) / dt }
                        }
                    }
                    idx += batch.count
                }
            }

            if Task.isCancelled { throw CancellationError() }
            job.phase = "打包中…"
            job.speedBps = 0
            // Assemble the page list from the files actually on disk.
            var entries: [(name: String, file: URL)] = []
            for (ci, item) in plan.enumerated() {
                for (pi, _) in item.images.enumerated() {
                    let url = pagesDir.appendingPathComponent("\(ci)-\(pi).jpg")
                    guard fm.fileExists(atPath: url.path) else { continue }
                    let name = multi
                        ? String(format: "%03d-%@/%05d.jpg", ci + 1, Self.sanitize(item.chapter.name), pi + 1)
                        : String(format: "%05d.jpg", pi + 1)
                    entries.append((name, url))
                }
            }
            guard !entries.isEmpty else {
                job.state = .failed
                job.phase = "下载失败，检查网络后重试"
                return
            }
            let fileName = "\(job.albumId) - \(Self.sanitize(job.title)).pdf"
            let dest = DownloadedStore.documents.appendingPathComponent(fileName)
            let partial = DownloadedStore.documents.appendingPathComponent(fileName + ".part")
            try? fm.removeItem(at: partial)
            // Build next to the destination and move when complete, so a
            // crash mid-write never leaves a truncated PDF that the library
            // treats as a finished download.
            try PdfWriter.write(entries: entries, to: partial)
            try? fm.removeItem(at: dest)
            try fm.moveItem(at: partial, to: dest)
            let totalBytes = (try? fm.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
            store.add(DownloadedRecord(albumId: job.albumId, title: job.title,
                                        chapters: plan.count, pages: entries.count,
                                        bytes: totalBytes, fileName: fileName,
                                        date: Date().timeIntervalSince1970))
            job.state = .done
            job.phase = "完成"
        } catch is CancellationError {
            job.state = .cancelled
            job.phase = "已取消（可继续下载）"
        } catch {
            if Task.isCancelled {
                job.state = .cancelled
                job.phase = "已取消（可继续下载）"
            } else {
                job.state = .failed
                job.phase = "下载失败，检查网络后重试"
            }
        }
    }
}

// MARK: - downloads screen

struct DownloadsView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var downloads: DownloadManager

    private func fmtSize(_ b: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: b, countStyle: .file)
    }
    private func fmtSpeed(_ s: Double) -> String {
        if s <= 0 { return "—" }
        return ByteCountFormatter.string(fromByteCount: Int64(s), countStyle: .file) + "/s"
    }

    var embedded = false

    private var listView: some View {
        List {
                if !downloads.jobs.isEmpty {
                    Section("下载队列") {
                        ForEach(downloads.jobs) { job in
                            JobRow(job: job, fmtSpeed: fmtSpeed)
                        }
                    }
                }
                Section {
                    if downloads.store.records.isEmpty {
                        Text("还没有已下载的漫画\n在任意漫画详情页点「下载」就会存到这里")
                            .foregroundColor(.secondary).font(.subheadline)
                    } else {
                        ForEach(downloads.store.records) { rec in
                            HStack(spacing: 12) {
                                RemoteImage(path: ImagePipe.coverPath(albumId: rec.albumId))
                                    .frame(width: 40, height: 54).clipped().cornerRadius(6)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(rec.title).font(.subheadline).fontWeight(.semibold).lineLimit(2)
                                    Text("\(rec.chapters) 话 · \(rec.pages) 页 · \(fmtSize(rec.bytes))")
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                Spacer()
                                ShareLink(item: rec.fileURL) {
                                    Image(systemName: "square.and.arrow.up").foregroundColor(accentPink)
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(.vertical, 2)
                            .background {
                                if rec.fileURL.pathExtension.lowercased() == "pdf" {
                                    NavigationLink(destination: LocalPdfView(url: rec.fileURL, title: rec.title)) {
                                        EmptyView()
                                    }
                                    .opacity(0)
                                }
                            }
                        }
                        .onDelete { idx in
                            for i in idx { downloads.store.delete(downloads.store.records[i]) }
                        }
                    }
                } header: {
                    Text("已下载")
                } footer: {
                    Text("点已下载的条目即可在 App 里直接打开阅读（PDF）；文件也保存在「文件」App → 我的 iPhone → JM Reader 里。左滑可删除。")
                }
            }
            .tabMinimizeOnScroll()
            .navigationTitle("下载")
                                    .toolbar {
                if downloads.jobs.contains(where: { $0.state == .done || $0.state == .failed || $0.state == .cancelled }) {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("清除记录") { downloads.clearFinished() }
                    }
                }
            }
    }

    var body: some View {
        if embedded {
            listView
        } else {
            NavigationStack { listView.withDestinations() }
        }
    }
}

private struct JobRow: View {
    @ObservedObject var job: DownloadJob
    var fmtSpeed: (Double) -> String
    @EnvironmentObject var downloads: DownloadManager

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(job.title).font(.subheadline).fontWeight(.semibold).lineLimit(1)
                Spacer()
                switch job.state {
                case .queued, .working:
                    Button("取消") { downloads.cancel(job) }.font(.caption)
                case .done:
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                case .failed:
                    Button("重试") { downloads.enqueue(albumId: job.albumId, title: job.title) }.font(.caption)
                case .cancelled:
                    Text("已取消").font(.caption).foregroundColor(.secondary)
                }
            }
            if job.state == .working || job.state == .queued {
                ProgressView(value: job.fraction)
                    .tint(accentPink)
                HStack {
                    Text(job.phase).font(.caption).foregroundColor(.secondary).lineLimit(1)
                    Spacer()
                    Text("\(job.donePages)/\(job.totalPages) 页 · \(fmtSpeed(job.speedBps))")
                        .font(.caption).foregroundColor(.secondary)
                }
            } else {
                Text(job.phase).font(.caption).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}
