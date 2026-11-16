import Foundation
import PDFKit

// MARK: - Inflate (RFC 1951 raw deflate, zero-dependency)
//
// Pure-Swift decompressor needed because CBZ/EPUB are ZIP files and the
// project ships no third-party ZIP library. The block/table flow mirrors a
// reference implementation that was verified byte-for-byte against zlib
// output (fixed + dynamic Huffman, stored blocks) before transcription.

enum InflateError: Error { case badBlockType, badCode, overrun, badLength }

private final class BitReader {
    let bytes: [UInt8]
    var pos = 0          // byte index
    var bit = 0          // bit index within byte (LSB first)
    init(_ data: Data) { bytes = [UInt8](data) }
    func read(_ n: Int) throws -> Int {
        var v = 0
        for i in 0..<n {
            guard pos < bytes.count else { throw InflateError.overrun }
            v |= Int((bytes[pos] >> bit) & 1) << i
            bit += 1
            if bit == 8 { bit = 0; pos += 1 }
        }
        return v
    }
    func alignByte() { if bit != 0 { bit = 0; pos += 1 } }
}

private struct Huffman {
    var counts = [Int](repeating: 0, count: 16)   // number of codes per length
    var symbols: [Int] = []                        // symbols in code order
    var maxBits = 0

    init(lengths: [Int]) {
        for l in lengths where l > 0 { counts[l] += 1; if l > maxBits { maxBits = l } }
        var offsets = [Int](repeating: 0, count: 17)
        for b in 1..<16 { offsets[b + 1] = offsets[b] + counts[b] }
        symbols = [Int](repeating: 0, count: lengths.filter { $0 > 0 }.count)
        var next = offsets
        for (sym, l) in lengths.enumerated() where l > 0 {
            symbols[next[l]] = sym
            next[l] += 1
        }
    }

    /// puff-style canonical decode.
    func decode(_ br: BitReader) throws -> Int {
        var code = 0, first = 0, index = 0
        if maxBits == 0 { throw InflateError.badCode }
        for len in 1...maxBits {
            code |= try br.read(1)
            let count = counts[len]
            if code - count < first { return symbols[index + (code - first)] }
            index += count
            first += count
            first <<= 1
            code <<= 1
        }
        throw InflateError.badCode
    }
}

enum Inflate {
    private static let lenBase = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258]
    private static let lenExt = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0]
    private static let distBase = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577]
    private static let distExt = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13]
    private static let clenOrder = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]

    static func decompress(_ data: Data) throws -> Data {
        let br = BitReader(data)
        var out: [UInt8] = []
        while true {
            let bfinal = try br.read(1)
            let btype = try br.read(2)
            switch btype {
            case 0:
                br.alignByte()
                guard br.pos + 4 <= br.bytes.count else { throw InflateError.overrun }
                let len = Int(br.bytes[br.pos]) | (Int(br.bytes[br.pos + 1]) << 8)
                br.pos += 4
                guard br.pos + len <= br.bytes.count else { throw InflateError.overrun }
                out.append(contentsOf: br.bytes[br.pos..<(br.pos + len)])
                br.pos += len
            case 1, 2:
                let lit: Huffman, dist: Huffman
                if btype == 1 {
                    var ll = [Int](repeating: 8, count: 144)
                    ll += [Int](repeating: 9, count: 112)
                    ll += [Int](repeating: 7, count: 24)
                    ll += [Int](repeating: 8, count: 8)
                    lit = Huffman(lengths: ll)
                    dist = Huffman(lengths: [Int](repeating: 5, count: 32))
                } else {
                    let hlit = try br.read(5) + 257
                    let hdist = try br.read(5) + 1
                    let hclen = try br.read(4) + 4
                    var cl = [Int](repeating: 0, count: 19)
                    for i in 0..<hclen { cl[clenOrder[i]] = try br.read(3) }
                    let ch = Huffman(lengths: cl)
                    var lengths: [Int] = []
                    let total = hlit + hdist
                    while lengths.count < total {
                        let sym = try ch.decode(br)
                        if sym < 16 {
                            lengths.append(sym)
                        } else if sym == 16 {
                            guard let prev = lengths.last else { throw InflateError.badLength }
                            let rep = 3 + (try br.read(2))
                            lengths.append(contentsOf: [Int](repeating: prev, count: rep))
                        } else if sym == 17 {
                            let rep = 3 + (try br.read(3))
                            lengths.append(contentsOf: [Int](repeating: 0, count: rep))
                        } else {
                            let rep = 11 + (try br.read(7))
                            lengths.append(contentsOf: [Int](repeating: 0, count: rep))
                        }
                    }
                    guard lengths.count == total else { throw InflateError.badLength }
                    lit = Huffman(lengths: Array(lengths[..<hlit]))
                    dist = Huffman(lengths: Array(lengths[hlit...]))
                }
                while true {
                    let sym = try lit.decode(br)
                    if sym < 256 {
                        out.append(UInt8(sym))
                    } else if sym == 256 {
                        break
                    } else {
                        let li = sym - 257
                        guard li < lenBase.count else { throw InflateError.badLength }
                        let length = lenBase[li] + (try br.read(lenExt[li]))
                        let dsym = try dist.decode(br)
                        guard dsym < distBase.count else { throw InflateError.badLength }
                        let d = distBase[dsym] + (try br.read(distExt[dsym]))
                        guard d <= out.count, d > 0 else { throw InflateError.badLength }
                        var start = out.count - d
                        for _ in 0..<length { out.append(out[start]); start += 1 }
                    }
                }
            default:
                throw InflateError.badBlockType
            }
            if bfinal == 1 { break }
        }
        return Data(out)
    }
}

// MARK: - Minimal ZIP reader (central directory + store/deflate entries)

enum ZipError: Error { case notAZip, truncated, unsupportedMethod(Int), entryNotFound }

struct ZipEntry {
    let name: String
    let method: Int
    let compressedSize: Int
    let uncompressedSize: Int
    let localOffset: Int
}

struct ZipArchive {
    let entries: [ZipEntry]
    private let bytes: [UInt8]

    init(data: Data) throws {
        bytes = [UInt8](data)
        // Locate End Of Central Directory (scan the tail backwards).
        let tail = min(bytes.count, 65_557)
        var eocd = -1
        if bytes.count >= 22 {
            var i = bytes.count - 22
            let floor = bytes.count - tail
            while i >= floor {
                if bytes[i] == 0x50, bytes[i + 1] == 0x4B, bytes[i + 2] == 0x05, bytes[i + 3] == 0x06 { eocd = i; break }
                i -= 1
            }
        }
        guard eocd >= 0 else { throw ZipError.notAZip }
        let total = ZipArchive.u16(bytes, eocd + 10)
        var p = ZipArchive.u32(bytes, eocd + 16)
        var parsed: [ZipEntry] = []
        for _ in 0..<total {
            guard p + 46 <= bytes.count, ZipArchive.u32(bytes, p) == 0x0201_4B50 else { throw ZipError.truncated }
            let method = ZipArchive.u16(bytes, p + 10)
            let comp = ZipArchive.u32(bytes, p + 20)
            let uncomp = ZipArchive.u32(bytes, p + 24)
            let nameLen = ZipArchive.u16(bytes, p + 28)
            let extraLen = ZipArchive.u16(bytes, p + 30)
            let commentLen = ZipArchive.u16(bytes, p + 32)
            let local = ZipArchive.u32(bytes, p + 42)
            let nameStart = p + 46
            guard nameStart + nameLen <= bytes.count else { throw ZipError.truncated }
            let name = String(bytes: bytes[nameStart..<(nameStart + nameLen)], encoding: .utf8)
                ?? String(decoding: bytes[nameStart..<(nameStart + nameLen)], as: UTF8.self)
            parsed.append(ZipEntry(name: name, method: method, compressedSize: comp,
                                   uncompressedSize: uncomp, localOffset: local))
            p += 46 + nameLen + extraLen + commentLen
        }
        entries = parsed
    }

    func data(for entry: ZipEntry) throws -> Data {
        let p = entry.localOffset
        guard p + 30 <= bytes.count, ZipArchive.u32(bytes, p) == 0x0403_4B50 else { throw ZipError.truncated }
        let nameLen = ZipArchive.u16(bytes, p + 26)
        let extraLen = ZipArchive.u16(bytes, p + 28)
        let start = p + 30 + nameLen + extraLen
        let end = start + entry.compressedSize
        guard end <= bytes.count else { throw ZipError.truncated }
        let raw = Data(bytes[start..<end])
        switch entry.method {
        case 0: return raw
        case 8: return try Inflate.decompress(raw)
        default: throw ZipError.unsupportedMethod(entry.method)
        }
    }

    func entry(named name: String) -> ZipEntry? { entries.first { $0.name == name } }

    private static func u16(_ b: [UInt8], _ off: Int) -> Int { Int(b[off]) | (Int(b[off + 1]) << 8) }
    private static func u32(_ b: [UInt8], _ off: Int) -> Int {
        Int(b[off]) | (Int(b[off + 1]) << 8) | (Int(b[off + 2]) << 16) | (Int(b[off + 3]) << 24)
    }
}

// MARK: - Local book model

enum LocalBookKind: String, Codable { case comic, pdf, novel }

struct LocalBook: Identifiable, Codable, Hashable {
    var id: String
    var title: String
    var kind: LocalBookKind
    var dirName: String
    var sourceName: String = ""          // pdf: the file inside dir; others: original import name
    var coverRel: String? = nil          // path relative to the book dir
    var pageFiles: [String] = []         // comic: file names under pages/
    var pageCount: Int = 0               // pdf
    var chapterTitles: [String] = []     // novel
    var addedAt: Double = 0
    var sizeBytes: Int = 0
}

/// One piece of a novel chapter: a run of text, or an illustration
/// stored as a file inside the book directory (EPUB imports, build 67+).
struct NovelBlock: Codable {
    var kind: String            // "text" | "image"
    var text: String? = nil
    var file: String? = nil     // image: path relative to the book dir
}

struct NovelChapter: Codable {
    var title: String
    /// Legacy whole-chapter text (imports before build 67, and TXT).
    var text: String = ""
    /// Ordered text/image blocks (EPUB imports from build 67 on).
    var blocks: [NovelBlock]? = nil

    var contentBlocks: [NovelBlock] {
        if let blocks, !blocks.isEmpty { return blocks }
        return text.isEmpty ? [] : [NovelBlock(kind: "text", text: text)]
    }

    init(title: String, text: String = "", blocks: [NovelBlock]? = nil) {
        self.title = title
        self.text = text
        self.blocks = blocks
    }

    private enum CodingKeys: String, CodingKey { case title, text, blocks }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = try c.decode(String.self, forKey: .title)
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        blocks = try c.decodeIfPresent([NovelBlock].self, forKey: .blocks)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(title, forKey: .title)
        try c.encode(text, forKey: .text)
        try c.encodeIfPresent(blocks, forKey: .blocks)
    }
}

final class LocalBookStore: ObservableObject {
    @Published private(set) var books: [LocalBook] = []
    @Published private(set) var isImporting = false

    /// Canary for the import flow: shown under the bookshelf controls and
    /// appended to LocalBooks/import-log.txt. It exists because the
    /// picker -> import chain failed with ZERO visible reaction on the
    /// owner's device (builds 61/62): if the picker completion never
    /// fires, this line never changes; if it does fire, the line (and the
    /// log) say exactly which step stopped.
    @Published var lastImportStatus: String = ""

    func noteImport(_ line: String) {
        lastImportStatus = line
        let stamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        guard let data = "[\(stamp)] \(line)\n".data(using: .utf8) else { return }
        let logURL = root.appendingPathComponent("import-log.txt")
        if let handle = try? FileHandle(forWritingTo: logURL) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
        } else {
            try? data.write(to: logURL, options: .atomic)
        }
    }
    /// Reading progress kept OFF the published surface (same lesson as the
    /// library store: publishing per page turn re-renders lists mid-scroll).
    /// `progressTick` is bumped when a reader closes so the shelf refreshes.
    private(set) var progress: [String: [String: Int]] = [:]   // bookId -> ["page": n, "chapter": n]
    @Published private(set) var progressTick = 0

    private let root: URL
    private let indexURL: URL
    private let progressKey = "jm.localProgress.v1"

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        root = docs.appendingPathComponent("LocalBooks", isDirectory: true)
        indexURL = root.appendingPathComponent("index.json")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: indexURL),
           let list = try? JSONDecoder().decode([LocalBook].self, from: data) {
            books = list
        }
        if let data = UserDefaults.standard.data(forKey: progressKey),
           let map = try? JSONDecoder().decode([String: [String: Int]].self, from: data) {
            progress = map
        }
    }

    func dir(of book: LocalBook) -> URL { root.appendingPathComponent(book.dirName, isDirectory: true) }
    func fileURL(_ book: LocalBook, _ rel: String) -> URL { dir(of: book).appendingPathComponent(rel) }
    func lastPage(_ book: LocalBook) -> Int { progress[book.id]?["page"] ?? 0 }
    func lastChapter(_ book: LocalBook) -> Int { progress[book.id]?["chapter"] ?? 0 }
    /// Last time this book was opened/read (falls back to import time).
    func lastActivity(_ book: LocalBook) -> Double {
        if let ts = progress[book.id]?["ts"], ts > 0 { return Double(ts) }
        return book.addedAt
    }
    func hasProgress(_ book: LocalBook) -> Bool { (progress[book.id]?["ts"] ?? 0) > 0 }

    func lastNovelPage(_ book: LocalBook) -> Int { progress[book.id]?["npage"] ?? 0 }

    func saveProgress(bookId: String, page: Int? = nil, chapter: Int? = nil, novelPage: Int? = nil) {
        var entry = progress[bookId] ?? [:]
        if let page { entry["page"] = page }
        if let chapter { entry["chapter"] = chapter }
        if let novelPage { entry["npage"] = novelPage }
        entry["ts"] = Int(Date().timeIntervalSince1970)
        progress[bookId] = entry
        if let data = try? JSONEncoder().encode(progress) {
            UserDefaults.standard.set(data, forKey: progressKey)
        }
    }

    func noteReaderClosed() { progressTick &+= 1 }

    private func persistIndex() {
        if let data = try? JSONEncoder().encode(books) {
            try? data.write(to: indexURL, options: .atomic)
        }
    }

    func delete(_ book: LocalBook) {
        try? FileManager.default.removeItem(at: dir(of: book))
        books.removeAll { $0.id == book.id }
        progress[book.id] = nil
        persistIndex()
    }

    // MARK: import

    /// Imports picked files. Returns (imported titles, failed file names).
    @MainActor
    /// Import picked files. The original build staged directly from the
    /// picker's security-scoped URL inside a detached task; on the owner's
    /// device the whole import silently did nothing (16:24 report: picked
    /// a PDF and an EPUB, confirmed, zero reaction). This version removes
    /// every fragile link in that chain: each picked file is first COPIED
    /// into the app's own temp area while the security scope is fresh,
    /// staging then runs from that plain file, and every failure surfaces
    /// its actual reason so a bad file can be diagnosed from the result
    /// text alone.
    func importFiles(_ urls: [URL]) async -> (added: [String], failed: [String]) {
        guard !urls.isEmpty else { return ([], []) }
        isImporting = true
        defer { isImporting = false }
        noteImport("开始导入 \(urls.count) 个文件")
        var added: [String] = []
        var failed: [String] = []
        let tmpRoot = root.appendingPathComponent("tmp-imports", isDirectory: true)
        try? FileManager.default.createDirectory(at: tmpRoot, withIntermediateDirectories: true)
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            // Unique subfolder, original filename: stageImport derives the
            // book title and stored source name from the filename.
            let copyDir = tmpRoot.appendingPathComponent(UUID().uuidString, isDirectory: true)
            let copyURL = copyDir.appendingPathComponent(url.lastPathComponent)
            do {
                try FileManager.default.createDirectory(at: copyDir, withIntermediateDirectories: true)
                try FileManager.default.copyItem(at: url, to: copyURL)
            } catch {
                if scoped { url.stopAccessingSecurityScopedResource() }
                noteImport("拷贝失败 \(url.lastPathComponent)：\(error.localizedDescription)")
                failed.append("\(url.lastPathComponent)：读取失败（\(error.localizedDescription)）")
                continue
            }
            if scoped { url.stopAccessingSecurityScopedResource() }
            noteImport("拷贝完成 \(url.lastPathComponent)，开始解析")
            do {
                let book = try await Task.detached(priority: .userInitiated) {
                    try Self.stageImport(from: copyURL, root: self.root)
                }.value
                books.insert(book, at: 0)
                persistIndex()
                added.append(book.title)
                noteImport("导入成功：\(book.title)")
            } catch {
                noteImport("解析失败 \(url.lastPathComponent)：\(error.localizedDescription)")
                failed.append("\(url.lastPathComponent)：\(error.localizedDescription)")
            }
            try? FileManager.default.removeItem(at: copyDir)
        }
        return (added, failed)
    }

    /// Heavy lifting off the main actor: parse one file into a ready book dir.
    private static func stageImport(from url: URL, root: URL) throws -> LocalBook {
        let ext = url.pathExtension.lowercased()
        let baseTitle = url.deletingPathExtension().lastPathComponent
        let id = UUID().uuidString
        let dir = root.appendingPathComponent(id, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        do {
            switch ext {
            case "zip", "cbz":
                return try stageComicArchive(src: url, dir: dir, id: id, title: baseTitle)
            case "rar":
                return try stageRar(src: url, dir: dir, id: id, title: baseTitle)
            case "pdf":
                return try stagePdf(src: url, dir: dir, id: id, title: baseTitle)
            case "epub":
                return try stageEpub(src: url, dir: dir, id: id, title: baseTitle)
            case "txt":
                return try stageTxt(src: url, dir: dir, id: id, title: baseTitle)
            default:
                throw ZipError.notAZip
            }
        } catch {
            try? FileManager.default.removeItem(at: dir)
            throw error
        }
    }

    private static func stageComicArchive(src: URL, dir: URL, id: String, title: String) throws -> LocalBook {
        let data = try Data(contentsOf: src)
        let zip = try ZipArchive(data: data)
        let imageExts: Set<String> = ["jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif"]
        let images = zip.entries.filter { e in
            guard !e.name.hasSuffix("/") else { return false }
            let comps = e.name.split(separator: "/")
            if comps.contains("__MACOSX") { return false }
            let last = comps.last.map(String.init) ?? e.name
            if last.hasPrefix(".") { return false }
            return imageExts.contains((last as NSString).pathExtension.lowercased())
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        guard !images.isEmpty else { throw ZipError.entryNotFound }
        let pagesDir = dir.appendingPathComponent("pages", isDirectory: true)
        try FileManager.default.createDirectory(at: pagesDir, withIntermediateDirectories: true)
        var names: [String] = []
        for (i, entry) in images.enumerated() {
            let raw = try zip.data(for: entry)
            let ext = (entry.name as NSString).pathExtension.lowercased()
            let name = String(format: "%05d.%@", i, ext.isEmpty ? "img" : ext)
            try raw.write(to: pagesDir.appendingPathComponent(name), options: .atomic)
            names.append(name)
        }
        var size = 0
        for n in names { size += (try? Data(contentsOf: pagesDir.appendingPathComponent(n)).count) ?? 0 }
        return LocalBook(id: id, title: title, kind: .comic, dirName: id,
                         sourceName: src.lastPathComponent,
                         coverRel: names.first.map { "pages/\($0)" },
                         pageFiles: names, addedAt: Date().timeIntervalSince1970,
                         sizeBytes: size)
    }

    /// RAR via the vendored UnRAR library (UnrarBridge): extract to a
    /// staging subfolder, then route the contents through the same staging
    /// paths as everything else — images become a comic, a lone TXT/EPUB
    /// becomes a novel, a lone PDF stays a PDF.
    private static func stageRar(src: URL, dir: URL, id: String, title: String) throws -> LocalBook {
        let extractDir = dir.appendingPathComponent("rar-extract", isDirectory: true)
        try FileManager.default.createDirectory(at: extractDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: extractDir) }
        let rels = try UnrarBridge.extractArchive(atPath: src.path, toDirectory: extractDir.path)
            .filter { !$0.hasSuffix("/") }
        let imageExts: Set<String> = ["jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif"]
        let images = rels.filter { rel in
            let comps = rel.split(separator: "/")
            if comps.contains("__MACOSX") { return false }
            let last = comps.last.map(String.init) ?? rel
            if last.hasPrefix(".") { return false }
            return imageExts.contains((last as NSString).pathExtension.lowercased())
        }.sorted { $0.localizedStandardCompare($1) == .orderedAscending }
        if !images.isEmpty {
            let pagesDir = dir.appendingPathComponent("pages", isDirectory: true)
            try FileManager.default.createDirectory(at: pagesDir, withIntermediateDirectories: true)
            var names: [String] = []
            for (i, rel) in images.enumerated() {
                let ext = (rel as NSString).pathExtension.lowercased()
                let name = String(format: "%05d.%@", i, ext.isEmpty ? "img" : ext)
                try FileManager.default.copyItem(at: extractDir.appendingPathComponent(rel),
                                                 to: pagesDir.appendingPathComponent(name))
                names.append(name)
            }
            var size = 0
            for n in names { size += (try? Data(contentsOf: pagesDir.appendingPathComponent(n)).count) ?? 0 }
            return LocalBook(id: id, title: title, kind: .comic, dirName: id,
                             sourceName: src.lastPathComponent,
                             coverRel: names.first.map { "pages/\($0)" },
                             pageFiles: names, addedAt: Date().timeIntervalSince1970,
                             sizeBytes: size)
        }
        if let txt = rels.first(where: { $0.lowercased().hasSuffix(".txt") }) {
            return try stageTxt(src: extractDir.appendingPathComponent(txt), dir: dir, id: id, title: title)
        }
        if let epub = rels.first(where: { $0.lowercased().hasSuffix(".epub") }) {
            return try stageEpub(src: extractDir.appendingPathComponent(epub), dir: dir, id: id, title: title)
        }
        if let pdf = rels.first(where: { $0.lowercased().hasSuffix(".pdf") }) {
            return try stagePdf(src: extractDir.appendingPathComponent(pdf), dir: dir, id: id, title: title)
        }
        throw ZipError.entryNotFound
    }

    private static func stagePdf(src: URL, dir: URL, id: String, title: String) throws -> LocalBook {
        let dest = dir.appendingPathComponent("book.pdf")
        try FileManager.default.copyItem(at: src, to: dest)
        let pages = PDFDocument(url: dest)?.pageCount ?? 0
        let size = (try? Data(contentsOf: dest).count) ?? 0
        return LocalBook(id: id, title: title, kind: .pdf, dirName: id,
                         sourceName: "book.pdf", pageCount: pages,
                         addedAt: Date().timeIntervalSince1970, sizeBytes: size)
    }

    private static func stageTxt(src: URL, dir: URL, id: String, title: String) throws -> LocalBook {
        let data = try Data(contentsOf: src)
        let text = decodeText(data)
        let chapters = splitChapters(text)
        try writeChapters(chapters, dir: dir)
        return LocalBook(id: id, title: title, kind: .novel, dirName: id,
                         sourceName: src.lastPathComponent,
                         chapterTitles: chapters.map(\.title),
                         addedAt: Date().timeIntervalSince1970, sizeBytes: data.count)
    }

    private static func stageEpub(src: URL, dir: URL, id: String, title fallbackTitle: String) throws -> LocalBook {
        let data = try Data(contentsOf: src)
        let zip = try ZipArchive(data: data)
        guard let container = zip.entry(named: "META-INF/container.xml"),
              let containerData = try? zip.data(for: container),
              let containerText = String(data: containerData, encoding: .utf8),
              let opfPath = xmlAttr(containerText, tag: "rootfile", attr: "full-path")
        else { throw ZipError.entryNotFound }
        guard let opfEntry = zip.entry(named: opfPath),
              let opfData = try? zip.data(for: opfEntry),
              let opf = String(data: opfData, encoding: .utf8)
        else { throw ZipError.entryNotFound }
        let opfDir = (opfPath as NSString).deletingLastPathComponent

        var title = fallbackTitle
        if let t = xmlElementText(opf, tag: "dc:title"), !t.isEmpty { title = t }

        // Manifest: id -> (href, mediaType, properties)
        var manifest: [String: (href: String, type: String, props: String)] = [:]
        for tag in xmlTags(opf, name: "item") {
            guard let itemId = xmlAttrValue(tag, "id"), let href = xmlAttrValue(tag, "href") else { continue }
            manifest[itemId] = (href, xmlAttrValue(tag, "media-type") ?? "", xmlAttrValue(tag, "properties") ?? "")
        }
        // Spine order
        var spine: [String] = []
        for tag in xmlTags(opf, name: "itemref") {
            if let ref = xmlAttrValue(tag, "idref"), xmlAttrValue(tag, "linear") != "no" { spine.append(ref) }
        }
        // Chapter titles come from the navigation document (EPUB 3
        // nav, else the EPUB 2 NCX). Per-file <title> elements cannot
        // be used: Sigil-style EPUBs repeat the book title in every
        // file, so the first import showed a TOC of 27 identical rows.
        // Keys are archive paths with the fragment stripped.
        var navTitles: [String: String] = [:]
        if let navItem = manifest.values.first(where: { $0.props.split(separator: " ").contains("nav") }) {
            let navPath = joinPath(opfDir, navItem.href)
            if let entry = zip.entry(named: navPath),
               let raw = try? zip.data(for: entry),
               let navHtml = String(data: raw, encoding: .utf8) {
                let navDir = (navPath as NSString).deletingLastPathComponent
                for link in epubNavLinks(navHtml) {
                    let key = joinPath(navDir, link.href)
                    if navTitles[key] == nil { navTitles[key] = link.label }
                }
            }
        }
        if navTitles.isEmpty,
           let ncxItem = manifest.values.first(where: { $0.type.contains("ncx") }) {
            let ncxPath = joinPath(opfDir, ncxItem.href)
            if let entry = zip.entry(named: ncxPath),
               let raw = try? zip.data(for: entry),
               let ncx = String(data: raw, encoding: .utf8) {
                let ncxDir = (ncxPath as NSString).deletingLastPathComponent
                for link in epubNcxLinks(ncx) {
                    let key = joinPath(ncxDir, link.href)
                    if navTitles[key] == nil { navTitles[key] = link.label }
                }
            }
        }
        // Spine pages as ordered segments (text runs and image
        // references in document order, build 67: illustrations are
        // kept, not stripped). A page's plain text — the concatenation
        // of its text segments — drives the length tests below.
        struct EpubPage {
            var href: String
            var segs: [(kind: String, value: String)] = []  // image value = archive path
            var text: String = ""
        }
        var imageFiles: [String: String] = [:]   // archive path -> stored path rel. to book dir
        var imageSeq = 0
        func storeImage(_ archivePath: String) -> String? {
            if let kept = imageFiles[archivePath] { return kept }
            let ext = (archivePath as NSString).pathExtension.lowercased()
            guard ["jpg", "jpeg", "png", "webp", "gif"].contains(ext),
                  let entry = zip.entry(named: archivePath),
                  let raw = try? zip.data(for: entry) else { return nil }
            let imagesDir = dir.appendingPathComponent("images", isDirectory: true)
            try? FileManager.default.createDirectory(at: imagesDir, withIntermediateDirectories: true)
            let name = String(format: "img-%04d.%@", imageSeq, ext)
            imageSeq += 1
            do { try raw.write(to: imagesDir.appendingPathComponent(name), options: .atomic) }
            catch { return nil }
            let rel = "images/" + name
            imageFiles[archivePath] = rel
            return rel
        }
        var pages: [EpubPage] = []
        for ref in spine {
            guard let item = manifest[ref], item.type.contains("xhtml"),
                  let entry = zip.entry(named: joinPath(opfDir, item.href)),
                  let raw = try? zip.data(for: entry),
                  let html = String(data: raw, encoding: .utf8)
            else { continue }
            let pagePath = joinPath(opfDir, item.href)
            let pageDir = (pagePath as NSString).deletingLastPathComponent
            var page = EpubPage(href: pagePath)
            for seg in epubSegments(html) {
                if seg.kind == "image" {
                    page.segs.append(("image", joinPath(pageDir, seg.value)))
                } else {
                    page.segs.append(seg)
                }
            }
            // Running head: the first line of the first text segment
            // repeats the book title (it comes from the file's <title>
            // and is the ONLY text of image-only front-matter pages).
            let fileTitle = xmlElementText(html, tag: "title") ?? ""
            if let ti = page.segs.firstIndex(where: { $0.kind == "text" }) {
                let lines = page.segs[ti].value.components(separatedBy: "\n")
                if let first = lines.first {
                    let head = first.trimmingCharacters(in: .whitespaces)
                    if !head.isEmpty, head == fileTitle || head == title {
                        page.segs[ti] = ("text", lines.dropFirst().joined(separator: "\n")
                            .trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                }
            }
            page.text = page.segs.filter { $0.kind == "text" }.map(\.value)
                .filter { !$0.isEmpty }.joined(separator: "\n")
            pages.append(page)
        }
        // Kept chapters: image-only pages drop out anywhere; short
        // front-matter notices ahead of the first substantial chapter
        // (copyright/caution pages) drop too.
        let firstBig = pages.firstIndex { $0.text.count >= 1000 }
        var keptIdx: [Int] = []
        for (i, page) in pages.enumerated() {
            if page.text.count < 10 { continue }
            if let fb = firstBig, i < fb, page.text.count < 200 { continue }
            keptIdx.append(i)
        }
        guard let firstKept = keptIdx.first, let lastKept = keptIdx.last
        else { throw ZipError.entryNotFound }
        var chapters: [NovelChapter] = []
        var frontImages: [NovelBlock] = []
        for (i, page) in pages.enumerated() {
            if keptIdx.contains(i) {
                var blocks: [NovelBlock] = frontImages
                frontImages = []
                for seg in page.segs {
                    if seg.kind == "image" {
                        if let rel = storeImage(seg.value) {
                            blocks.append(NovelBlock(kind: "image", file: rel))
                        }
                    } else if !seg.value.isEmpty {
                        blocks.append(NovelBlock(kind: "text", text: seg.value))
                    }
                }
                // Title: nav label, else a heading-like first line,
                // else 第N章. A heading-like first line leaves the body.
                var chTitle = navTitles[page.href] ?? ""
                if let bi = blocks.firstIndex(where: { $0.kind == "text" }) {
                    let firstLine = (blocks[bi].text ?? "").components(separatedBy: "\n").first?
                        .trimmingCharacters(in: .whitespaces) ?? ""
                    if isChapterHeading(firstLine) {
                        if chTitle.isEmpty { chTitle = firstLine }
                        let rest = (blocks[bi].text ?? "").components(separatedBy: "\n").dropFirst()
                            .joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
                        blocks[bi] = NovelBlock(kind: "text", text: rest)
                    }
                }
                if chTitle.isEmpty || chTitle == title { chTitle = "第\(chapters.count + 1)章" }
                chapters.append(NovelChapter(title: chTitle, blocks: blocks))
            } else if i < firstKept {
                // Front matter: its illustrations open the book (cover
                // art, color pages, title page) — except the publisher's
                // own TOC page image, which the app replaces.
                if navTitles[page.href] != "目录" {
                    for seg in page.segs where seg.kind == "image" {
                        if let rel = storeImage(seg.value) {
                            frontImages.append(NovelBlock(kind: "image", file: rel))
                        }
                    }
                }
            } else if i < lastKept, !chapters.isEmpty {
                // An image-only page between chapters: its
                // illustrations close out the previous chapter.
                for seg in page.segs where seg.kind == "image" {
                    if let rel = storeImage(seg.value) {
                        var last = chapters[chapters.count - 1]
                        last.blocks = (last.blocks ?? []) + [NovelBlock(kind: "image", file: rel)]
                        chapters[chapters.count - 1] = last
                    }
                }
            }
            // Pages after the last chapter (colophon, publisher ads,
            // blank pages) contribute nothing.
        }
        guard !chapters.isEmpty else { throw ZipError.entryNotFound }
        try writeChapters(chapters, dir: dir)
        // Cover: properties=cover-image, else meta name=cover, else first image in spine order of manifest
        var coverRel: String? = nil
        var coverItem = manifest.values.first { $0.props.contains("cover-image") }
        if coverItem == nil, let metaId = xmlMetaCoverId(opf) { coverItem = manifest[metaId] }
        if coverItem == nil { coverItem = manifest.values.first { $0.type.hasPrefix("image/") } }
        if let ci = coverItem, let entry = zip.entry(named: joinPath(opfDir, ci.href)),
           let raw = try? zip.data(for: entry) {
            let ext = (ci.href as NSString).pathExtension.lowercased()
            let name = "cover." + (ext.isEmpty ? "jpg" : ext)
            try? raw.write(to: dir.appendingPathComponent(name), options: .atomic)
            coverRel = name
        }
        return LocalBook(id: id, title: title, kind: .novel, dirName: id,
                         sourceName: src.lastPathComponent, coverRel: coverRel,
                         chapterTitles: chapters.map(\.title),
                         addedAt: Date().timeIntervalSince1970, sizeBytes: data.count)
    }

    private static func writeChapters(_ chapters: [NovelChapter], dir: URL) throws {
        let data = try JSONEncoder().encode(chapters)
        try data.write(to: dir.appendingPathComponent("chapters.json"), options: .atomic)
    }

    func novelChapters(_ book: LocalBook) -> [NovelChapter] {
        let url = dir(of: book).appendingPathComponent("chapters.json")
        guard let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([NovelChapter].self, from: data) else { return [] }
        return list
    }

    // MARK: text helpers

    static func decodeText(_ data: Data) -> String {
        if let s = String(data: data, encoding: .utf8) { return s }
        let gb = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(UInt32(CFStringEncodings.GB_18030_2000.rawValue))))
        if let s = String(data: data, encoding: gb) { return s }
        return String(decoding: data, as: UTF8.self)
    }

    static func splitChapters(_ raw: String) -> [NovelChapter] {
        let normalized = raw.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let heading = try? NSRegularExpression(
            pattern: "^\\s*(第[0-9零一二三四五六七八九十百千万两]+[章节回卷集部篇][^\\n]{0,30}|(Chapter|CHAPTER)\\s+\\d+[^\\n]{0,30})\\s*$",
            options: [.anchorsMatchLines])
        var chapters: [NovelChapter] = []
        var currentTitle = "开始"
        var buffer: [String] = []
        func flush() {
            let text = buffer.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { chapters.append(NovelChapter(title: currentTitle, text: text)) }
            buffer = []
        }
        for line in normalized.components(separatedBy: "\n") {
            let range = NSRange(line.startIndex..<line.endIndex, in: line)
            if let heading, heading.firstMatch(in: line, range: range) != nil {
                flush()
                currentTitle = line.trimmingCharacters(in: .whitespaces)
            } else {
                buffer.append(line)
            }
        }
        flush()
        if chapters.isEmpty {
            return [NovelChapter(title: "正文", text: normalized)]
        }
        return chapters
    }

    static func stripHTML(_ html: String) -> String {
        var s = html
        for pat in ["(?is)<script.*?</script>", "(?is)<style.*?</style>"] {
            s = regexReplace(s, pat, "\n")
        }
        s = regexReplace(s, "(?i)<br\\s*/?>", "\n")
        s = regexReplace(s, "(?i)</(p|div|h[1-6]|li|tr)>", "\n")
        s = regexReplace(s, "<[^>]+>", "")
        s = s.replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
        s = regexReplace(s, "&#(\\d+);") { m in
            if let n = Int(m), let scalar = UnicodeScalar(n) { return String(Character(scalar)) }
            return m
        }
        s = regexReplace(s, "&#x([0-9a-fA-F]+);") { m in
            if let n = Int(m, radix: 16), let scalar = UnicodeScalar(n) { return String(Character(scalar)) }
            return m
        }
        s = s.replacingOccurrences(of: "&amp;", with: "&")
        let lines = s.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
        var out: [String] = []
        var blankRun = 0
        for line in lines {
            if line.isEmpty { blankRun += 1; if blankRun > 1 { continue } }
            else { blankRun = 0 }
            out.append(line)
        }
        return out.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func regexReplace(_ s: String, _ pattern: String, _ template: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }
        return re.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..<s.endIndex, in: s), withTemplate: template)
    }

    private static func regexReplace(_ s: String, _ pattern: String, _ body: (String) -> String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }
        var result = s
        let matches = re.matches(in: s, range: NSRange(s.startIndex..<s.endIndex, in: s)).reversed()
        for m in matches {
            guard m.numberOfRanges > 1, let r = Range(m.range, in: result), let g = Range(m.range(at: 1), in: result) else { continue }
            result.replaceSubrange(r, with: body(String(result[g])))
        }
        return result
    }

    // MARK: tiny XML string helpers (container.xml / OPF are machine-generated)

    static func xmlTags(_ xml: String, name: String) -> [String] {
        var out: [String] = []
        var search = xml.startIndex
        let needle = "<" + name
        while let r = xml.range(of: needle, range: search..<xml.endIndex) {
            guard let end = xml.range(of: ">", range: r.upperBound..<xml.endIndex) else { break }
            out.append(String(xml[r.lowerBound..<end.upperBound]))
            search = end.upperBound
        }
        return out
    }

    static func xmlAttr(_ xml: String, tag: String, attr: String) -> String? {
        for t in xmlTags(xml, name: tag) {
            if let v = xmlAttrValue(t, attr) { return v }
        }
        return nil
    }

    static func xmlAttrValue(_ tag: String, _ attr: String) -> String? {
        for quote in ["\"", "'"] {
            let needle = attr + "=" + quote
            guard let r = tag.range(of: needle) else { continue }
            let rest = tag[r.upperBound...]
            guard let end = rest.firstIndex(of: Character(quote)) else { continue }
            return String(rest[..<end])
        }
        return nil
    }

    static func xmlElementText(_ xml: String, tag: String) -> String? {
        // Handles namespaced tags like <dc:title>…</dc:title> via suffix
        // match, and attributes on the open tag (<dc:title id="title"> —
        // without tolerating them, Sigil EPUBs lose their dc:title and
        // the book falls back to its filename).
        let pattern = "(?s)<[^>]*" + NSRegularExpression.escapedPattern(for: tag) + "[^>]*>(.*?)</[^>]*" + NSRegularExpression.escapedPattern(for: tag) + "\\s*>"
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: xml, range: NSRange(xml.startIndex..<xml.endIndex, in: xml)),
              m.numberOfRanges > 1, let r = Range(m.range(at: 1), in: xml) else { return nil }
        return stripHTML(String(xml[r])).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Splits an XHTML page into ordered segments: ("text", stripped
    /// text) and ("image", raw href), in document order. Handles both
    /// <img src="…"> and SVG <image href="…"> / xlink:href forms.
    static func epubSegments(_ html: String) -> [(kind: String, value: String)] {
        var out: [(kind: String, value: String)] = []
        let pattern = "(?is)<img[^>]*?\\bsrc=\"([^\"]+)\"[^>]*>|<image[^>]*?(?:xlink:href|href)=\"([^\"]+)\"[^>]*>"
        guard let re = try? NSRegularExpression(pattern: pattern) else {
            let t = stripHTML(html)
            return t.isEmpty ? [] : [("text", t)]
        }
        var pos = html.startIndex
        for m in re.matches(in: html, range: NSRange(html.startIndex..<html.endIndex, in: html)) {
            guard let whole = Range(m.range, in: html) else { continue }
            if whole.lowerBound > pos {
                let t = stripHTML(String(html[pos..<whole.lowerBound]))
                if !t.isEmpty { out.append(("text", t)) }
            }
            var href: String? = nil
            if m.numberOfRanges > 1, let r = Range(m.range(at: 1), in: html) { href = String(html[r]) }
            if href == nil, m.numberOfRanges > 2, let r = Range(m.range(at: 2), in: html) { href = String(html[r]) }
            if let href { out.append(("image", href)) }
            pos = whole.upperBound
        }
        if pos < html.endIndex {
            let t = stripHTML(String(html[pos...]))
            if !t.isEmpty { out.append(("text", t)) }
        }
        return out
    }

    /// Links of an EPUB 3 nav document: (href without fragment, label),
    /// in document order.
    static func epubNavLinks(_ html: String) -> [(href: String, label: String)] {
        var out: [(href: String, label: String)] = []
        let pattern = "(?is)<a\\s[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>"
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        for m in re.matches(in: html, range: NSRange(html.startIndex..<html.endIndex, in: html)) {
            guard m.numberOfRanges > 2,
                  let hr = Range(m.range(at: 1), in: html),
                  let lr = Range(m.range(at: 2), in: html) else { continue }
            let rawHref = String(html[hr])
            let href = rawHref.split(separator: "#").first.map(String.init) ?? rawHref
            let label = stripHTML(String(html[lr])).trimmingCharacters(in: .whitespacesAndNewlines)
            if !label.isEmpty { out.append((href, label)) }
        }
        return out
    }

    /// Links of an EPUB 2 NCX: (src without fragment, label), in
    /// document order.
    static func epubNcxLinks(_ ncx: String) -> [(href: String, label: String)] {
        var out: [(href: String, label: String)] = []
        let pattern = "(?is)<text>(.*?)</text>\\s*</navLabel>\\s*<content[^>]*?src=\"([^\"]+)\""
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        for m in re.matches(in: ncx, range: NSRange(ncx.startIndex..<ncx.endIndex, in: ncx)) {
            guard m.numberOfRanges > 2,
                  let lr = Range(m.range(at: 1), in: ncx),
                  let hr = Range(m.range(at: 2), in: ncx) else { continue }
            let rawHref = String(ncx[hr])
            let href = rawHref.split(separator: "#").first.map(String.init) ?? rawHref
            let label = stripHTML(String(ncx[lr])).trimmingCharacters(in: .whitespacesAndNewlines)
            if !label.isEmpty { out.append((href, label)) }
        }
        return out
    }

    /// True when a line reads as a chapter heading （序章 / 第N话 …),
    /// in Chinese or Japanese forms.
    static func isChapterHeading(_ line: String) -> Bool {
        let s = line.trimmingCharacters(in: .whitespaces)
        guard !s.isEmpty, s.count <= 80 else { return false }
        let patterns = [
            "^(序章|終章|终章|尾声|番外編|番外|後記|后记|あとがき|プロローグ|エピローグ)",
            "^第[0-9０-９零一二三四五六七八九十百千]{1,6}[话話章回節节卷]",
        ]
        return patterns.contains { s.range(of: $0, options: .regularExpression) != nil }
    }

    static func xmlMetaCoverId(_ opf: String) -> String? {
        for tag in xmlTags(opf, name: "meta") {
            if xmlAttrValue(tag, "name") == "cover" { return xmlAttrValue(tag, "content") }
        }
        return nil
    }

    static func joinPath(_ base: String, _ rel: String) -> String {
        let decoded = rel.removingPercentEncoding ?? rel
        var parts: [String] = []
        let all = (base.isEmpty ? [] : base.split(separator: "/").map(String.init))
            + decoded.split(separator: "/").map(String.init)
        for p in all {
            if p == ".." { if !parts.isEmpty { parts.removeLast() } }
            else if p != "." && !p.isEmpty { parts.append(p) }
        }
        return parts.joined(separator: "/")
    }
}
