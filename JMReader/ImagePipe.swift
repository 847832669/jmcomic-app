import UIKit
import Foundation
import ImageIO

/// Image loading with memory + disk cache, multi-host failover, and JM page de-scrambling.
final class ImagePipe {
    static let shared = ImagePipe()

    /// Built-in fallback order; the live pool is managed by LineStore.
    static var imageHosts: [String] { LineStore.builtinIMG }

    static func coverURLString(albumId: String) -> String {
        "https://\(LineStore.shared.imgHosts().first ?? imageHosts[0])/media/albums/\(albumId)_3x4.jpg"
    }
    static func coverPath(albumId: String) -> String { "/media/albums/\(albumId)_3x4.jpg" }
    static func pagePath(chapterId: String, file: String) -> String { "/media/photos/\(chapterId)/\(file)" }

    private let memCache = NSCache<NSString, UIImage>()
    private let session: URLSession
    private let diskDir: URL
    /// Image hosts in try-order; the last one that served moves to the front
    /// so a slow first host doesn't tax every cover and page.
    private var hostOrder = ImagePipe.imageHosts
    /// Bumped on every clearDiskCache(): a download that began before the
    /// wipe must not write the images it is still holding back to disk.
    private var cacheGeneration = 0
    /// Paths whose image is currently being downloaded, so a cover that
    /// appears in three shelves at once still costs one request.
    private var inflight: [String: Task<UIImage?, Never>] = [:]
    private let inflightLock = NSLock()

    /// Disk budget for Caches/JMImages. NSCache only bounds memory, so
    /// without this the folder grew forever and the storage page kept
    /// reporting a bigger number (iOS).
    private static let diskBudget: Int64 = 512 * 1024 * 1024
    /// Live image hosts, shared with LineStore so a host that 404s or times
    /// out for one page is demoted for every caller.
    private static let deadHostKey = "line.deadImgHosts.v1"
    private static var deadHosts: [String: Date] {
        get {
            guard let d = UserDefaults.standard.data(forKey: deadHostKey),
                  let m = try? JSONDecoder().decode([String: Date].self, from: d) else { return [:] }
            return m
        }
        set {
            let now = Date()
            // 10 minutes in the doghouse, then it gets another chance.
            let live = newValue.filter { now.timeIntervalSince($0.value) < 600 }
            UserDefaults.standard.set(try? JSONEncoder().encode(live), forKey: deadHostKey)
        }
    }

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 30
        // Let the shared cache do the HTTP-level work (ETag / no-store
        // aware); we already keep our own decoded-image cache in front.
        cfg.urlCache = URLCache(memoryCapacity: 8 << 20, diskCapacity: 128 << 20, diskPath: "JMHTTP")
        cfg.requestCachePolicy = .useProtocolCachePolicy
        session = URLSession(configuration: cfg)
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        diskDir = base.appendingPathComponent("JMImages", isDirectory: true)
        try? FileManager.default.createDirectory(at: diskDir, withIntermediateDirectories: true)
        memCache.countLimit = 160
        memCache.totalCostLimit = 96 * 1024 * 1024
        // Trim in the background: the store may start on a cold launch and
        // a synchronous directory walk would delay the first frame.
        DispatchQueue.global(qos: .utility).async { [weak self] in self?.trimDiskIfNeeded() }
    }

    /// Manual pin first, then last-good order, then any pool hosts not tried
    /// yet, and hosts that recently failed last.
    private func tryOrder() -> [String] {
        var order: [String] = []
        if let manual = LineStore.shared.manualIMGHost { order.append(manual) }
        for h in hostOrder where !order.contains(h) { order.append(h) }
        for h in LineStore.shared.imgHosts() where !order.contains(h) { order.append(h) }
        let dead = Self.deadHosts
        return order.filter { dead[$0] == nil } + order.filter { dead[$0] != nil }
    }

    /// Mark a host as unresponsive so the next load doesn't spend another
    /// 15 s timeout on it.
    private func markDead(_ host: String) {
        var dead = Self.deadHosts
        dead[host] = Date()
        Self.deadHosts = dead
    }

    /// Persist a freshly downloaded image: atomic, budget-aware, and skipped
    /// when the cache was cleared while the request was in flight.
    private func storeToDisk(_ data: Data, key: String, generation: Int) {
        guard generation == cacheGeneration else { return }
        let dest = diskDir.appendingPathComponent(key)
        do {
            try data.write(to: dest, options: .atomic)
        } catch {
            // Disk full: make room once, then give up quietly (the image is
            // still in the memory cache for this session).
            trimDisk(bytesNeeded: Int64(data.count))
            try? data.write(to: dest, options: .atomic)
        }
    }

    /// Delete oldest files until the cache fits in `diskBudget` (or until
    /// `bytesNeeded` more bytes fit).
    private func trimDisk(bytesNeeded: Int64 = 0) {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: diskDir,
                                                      includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])
        else { return }
        var entries: [(url: URL, date: Date, size: Int64)] = []
        var total: Int64 = 0
        for url in files {
            guard let v = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey]) else { continue }
            let size = Int64(v.fileSize ?? 0)
            total += size
            entries.append((url, v.contentModificationDate ?? .distantPast, size))
        }
        let budget = Self.diskBudget
        guard total + bytesNeeded > budget else { return }
        entries.sort { $0.date < $1.date }
        for e in entries {
            if total + bytesNeeded <= budget { break }
            try? fm.removeItem(at: e.url)
            total -= e.size
        }
    }

    private func trimDiskIfNeeded() { trimDisk() }

    /// Disk usage of the image cache (Caches/JMImages), for the storage page.
    func diskCacheUsage() -> (bytes: Int64, files: Int) {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: diskDir, includingPropertiesForKeys: [.fileSizeKey]) else {
            return (0, 0)
        }
        var bytes: Int64 = 0
        var files = 0
        for url in entries {
            if let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize {
                bytes += Int64(size)
                files += 1
            }
        }
        return (bytes, files)
    }

    /// Drop every cached image, on disk and in memory.
    func clearDiskCache() {
        cacheGeneration &+= 1
        let fm = FileManager.default
        if let entries = try? fm.contentsOfDirectory(at: diskDir, includingPropertiesForKeys: nil) {
            for url in entries { try? fm.removeItem(at: url) }
        }
        memCache.removeAllObjects()
        session.configuration.urlCache?.removeAllCachedResponses()
    }

    /// Load an image by path (e.g. /media/albums/123_3x4.jpg).
    /// If `scrambleNum` > 0 the downloaded page is de-scrambled before caching/returning.
    /// All heavy work (disk IO, decode, de-scramble) runs off the main thread;
    /// `targetWidth` > 0 decodes a downsampled thumbnail (covers / small thumbs).
    func load(path: String, scrambleNum: Int = 0, targetWidth: CGFloat = 0) async -> UIImage? {
        let memKey = "\(path)#\(scrambleNum)#\(Int(targetWidth))" as NSString
        if let hit = memCache.object(forKey: memKey) { return hit }

        // Single-flight: the same cover can be demanded by several shelves,
        // the detail header and the preloader at once. Callers share one task.
        inflightLock.lock()
        let task: Task<UIImage?, Never>
        if let existing = inflight[memKey as String] {
            task = existing
        } else {
            task = Task.detached(priority: .userInitiated) { [self] in
                let img = await loadOffMain(path: path, scrambleNum: scrambleNum, targetWidth: targetWidth)
                inflightLock.lock()
                inflight[memKey as String] = nil
                inflightLock.unlock()
                return img
            }
            inflight[memKey as String] = task
        }
        inflightLock.unlock()

        let result = await task.value
        if let result {
            let cost = Int(result.size.width * result.scale) * Int(result.size.height * result.scale) * 4
            memCache.setObject(result, forKey: memKey, cost: cost)
        }
        return result
    }

    private func loadOffMain(path: String, scrambleNum: Int, targetWidth: CGFloat) async -> UIImage? {
        var raw: UIImage?
        let fileKey = JMCrypto.md5Hex(path)
        let fileURL = diskDir.appendingPathComponent(fileKey)
        if let data = try? Data(contentsOf: fileURL), let img = Self.decode(data, targetWidth: targetWidth) {
            raw = img
        } else {
            let generation = cacheGeneration
            for host in tryOrder() {
                guard let url = URL(string: "https://\(host)\(path)") else { continue }
                var req = URLRequest(url: url)
                req.timeoutInterval = 15
                req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")
                guard let (data, resp) = try? await session.data(for: req) else {
                    markDead(host)
                    if Task.isCancelled { return nil }
                    continue
                }
                let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
                guard status == 200 else {
                    // 5xx / 429 / CDN error pages: this host is having a bad
                    // time, remember it. 4xx (404) means the file is gone
                    // everywhere, so don't bother with the other mirrors.
                    if status >= 500 || status == 429 { markDead(host) }
                    if status == 404 { return nil }
                    continue
                }
                guard let img = Self.decode(data, targetWidth: targetWidth) else { continue }
                storeToDisk(data, key: fileKey, generation: generation)
                inflightLock.lock()
                if host != hostOrder[0] {
                    hostOrder.removeAll { $0 == host }
                    hostOrder.insert(host, at: 0)
                }
                inflightLock.unlock()
                raw = img
                break
            }
        }
        guard let downloaded = raw else { return nil }
        return scrambleNum > 0 ? Self.descramble(downloaded, num: scrambleNum) : downloaded
    }

    /// Decode with optional ImageIO thumbnail downsampling (much cheaper than full-size decode).
    private static func decode(_ data: Data, targetWidth: CGFloat) -> UIImage? {
        // Downsample whenever the caller asked for a width. The old guard
        // fell back to `UIImage(data:)` (full-size RGBA decode) whenever the
        // source carried no embedded thumbnail, which for a 3 MB cover is
        // ~12 MB of memory per shelf cell — multiplied by every batch of
        // 4-column cells, that was the main peak-memory driver in browse.
        guard targetWidth > 0,
              let src = CGImageSourceCreateWithData(data as CFData, nil) else {
            return UIImage(data: data)
        }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(targetWidth),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else {
            return UIImage(data: data)
        }
        return UIImage(cgImage: cg)
    }

    func preload(path: String, scrambleNum: Int = 0) {
        Task.detached(priority: .utility) { _ = await self.load(path: path, scrambleNum: scrambleNum) }
    }

    // MARK: - de-scrambling (verified against the live site, 2026-11-01)

    /// Number of horizontal bands a page was shuffled into. `stem` is the file name without extension.
    static func splitNum(chapterId: String, stem: String) -> Int {
        let aid = Int(chapterId) ?? 0
        if aid < 220980 { return 0 }
        if aid < 268850 { return 10 }
        let x = aid < 421926 ? 10 : 8
        let hex = JMCrypto.md5Hex("\(aid)\(stem)")
        guard let last = hex.last, let scalar = last.unicodeScalars.first else { return 0 }
        return (Int(scalar.value) % x) * 2 + 2
    }

    /// The site shuffles pages by reversing the order of horizontal bands
    /// (the remainder rows ride with the bottom band). This restores the original.
    /// Final displayable image re-encoded as JPEG — used by the downloader so saved
    /// pages are ordinary files any app can open (descramble already applied).
    func loadJPEG(path: String, scrambleNum: Int) async -> Data? {
        guard let img = await load(path: path, scrambleNum: scrambleNum) else { return nil }
        return img.jpegData(compressionQuality: 0.97)
    }

    /// Download-oriented encode: decode at most `maxWidth` pixels wide
    /// (0 = original size) and re-encode at `quality`. Keeps both the peak
    /// bitmap and the saved file modest; the de-scramble math is unaffected
    /// because it runs on whole rows before the downscale.
    func pageJPEG(path: String, scrambleNum: Int, maxWidth: CGFloat = 1800,
                  quality: CGFloat = 0.88) async -> Data? {
        guard let img = await load(path: path, scrambleNum: scrambleNum) else { return nil }
        let w = img.size.width * img.scale
        guard maxWidth > 0, w > maxWidth else {
            return img.jpegData(compressionQuality: quality)
        }
        let ratio = maxWidth / w
        let target = CGSize(width: maxWidth, height: (img.size.height * img.scale * ratio).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let scaled = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            img.draw(in: CGRect(origin: .zero, size: target))
        }
        return scaled.jpegData(compressionQuality: quality)
    }

    static func descramble(_ image: UIImage, num: Int) -> UIImage {
        guard num > 0, let cg = image.cgImage else { return image }
        let w = cg.width, h = cg.height
        guard h >= num else { return image }
        let over = h % num
        let base = h / num
        // Draw straight into one CGContext instead of cropping `num` bands
        // and compositing each through UIKit: the old path allocated a
        // full-size RGBA buffer *plus* one cropped image per band (a 2×5 band
        // page made ~12 MB of garbage per page), and it called UIKit drawing
        // APIs from a detached background task.
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
            return image
        }
        // CGContext's origin is bottom-left; flip so y grows downward and the
        // band math below matches the stored layout.
        ctx.translateBy(x: 0, y: CGFloat(h))
        ctx.scaleBy(x: 1, y: -1)
        for i in 0..<num {
            var move = base
            let ySrc = h - (move * (i + 1)) - over
            var yDst = move * i
            if i == 0 { move += over } else { yDst += over }
            guard move > 0,
                  let band = cg.cropping(to: CGRect(x: 0, y: ySrc, width: w, height: move)) else { continue }
            ctx.draw(band, in: CGRect(x: 0, y: yDst, width: w, height: move))
        }
        guard let out = ctx.makeImage() else { return image }
        return UIImage(cgImage: out)
    }
}
