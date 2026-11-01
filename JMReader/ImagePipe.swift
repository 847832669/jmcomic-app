import UIKit
import Foundation
import ImageIO

/// Image loading with memory + disk cache, multi-host failover, and JM page de-scrambling.
final class ImagePipe {
    static let shared = ImagePipe()

    static let imageHosts = [
        "cdn-msp2.jmapiproxy1.cc",
        "cdn-msp3.jmapiproxy1.cc",
        "cdn-msp3.jmapiproxy3.cc",
    ]

    static func coverURLString(albumId: String) -> String {
        "https://\(imageHosts[0])/media/albums/\(albumId)_3x4.jpg"
    }
    static func coverPath(albumId: String) -> String { "/media/albums/\(albumId)_3x4.jpg" }
    static func pagePath(chapterId: String, file: String) -> String { "/media/photos/\(chapterId)/\(file)" }

    private let memCache = NSCache<NSString, UIImage>()
    private let session: URLSession
    private let diskDir: URL

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 30
        session = URLSession(configuration: cfg)
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        diskDir = base.appendingPathComponent("JMImages", isDirectory: true)
        try? FileManager.default.createDirectory(at: diskDir, withIntermediateDirectories: true)
        memCache.countLimit = 160
        memCache.totalCostLimit = 96 * 1024 * 1024
    }

    /// Load an image by path (e.g. /media/albums/123_3x4.jpg).
    /// If `scrambleNum` > 0 the downloaded page is de-scrambled before caching/returning.
    /// All heavy work (disk IO, decode, de-scramble) runs off the main thread;
    /// `targetWidth` > 0 decodes a downsampled thumbnail (covers / small thumbs).
    func load(path: String, scrambleNum: Int = 0, targetWidth: CGFloat = 0) async -> UIImage? {
        let memKey = "\(path)#\(scrambleNum)#\(Int(targetWidth))" as NSString
        if let hit = memCache.object(forKey: memKey) { return hit }
        let result = await Task.detached(priority: .userInitiated) { [self] in
            await loadOffMain(path: path, scrambleNum: scrambleNum, targetWidth: targetWidth)
        }.value
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
            for host in Self.imageHosts {
                guard let url = URL(string: "https://\(host)\(path)") else { continue }
                var req = URLRequest(url: url)
                req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")
                if let (data, resp) = try? await session.data(for: req),
                   (resp as? HTTPURLResponse)?.statusCode == 200,
                   let img = Self.decode(data, targetWidth: targetWidth) {
                    try? data.write(to: fileURL)
                    raw = img
                    break
                }
            }
        }
        guard let downloaded = raw else { return nil }
        return scrambleNum > 0 ? Self.descramble(downloaded, num: scrambleNum) : downloaded
    }

    /// Decode with optional ImageIO thumbnail downsampling (much cheaper than full-size decode).
    private static func decode(_ data: Data, targetWidth: CGFloat) -> UIImage? {
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

    static func descramble(_ image: UIImage, num: Int) -> UIImage {
        guard num > 0, let cg = image.cgImage else { return image }
        let w = cg.width, h = cg.height
        guard h >= num else { return image }
        let over = h % num
        let base = h / num
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format)
        return renderer.image { _ in
            for i in 0..<num {
                var move = base
                let ySrc = h - (move * (i + 1)) - over
                var yDst = move * i
                if i == 0 { move += over } else { yDst += over }
                guard move > 0,
                      let band = cg.cropping(to: CGRect(x: 0, y: ySrc, width: w, height: move)) else { continue }
                UIImage(cgImage: band, scale: 1, orientation: .up)
                    .draw(in: CGRect(x: 0, y: yDst, width: w, height: move))
            }
        }
    }
}
