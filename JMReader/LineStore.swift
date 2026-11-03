import Foundation

/// One API line as published in JM's official domain file (host + display name).
struct APILine: Codable, Equatable {
    var host: String
    var name: String
}

/// Domain pools for the API and image CDNs, with three sources merged:
///  1. built-in snapshot of JM's official domain file (verified 2026-11-04),
///  2. JM's official domain-distribution file (bytepluses newsvr-2025.txt,
///     AES-encrypted; decrypted with the same public client key the whole
///     ecosystem uses), refreshed at most every 6 hours,
///  3. a fallback list hosted on the user's own Cloudflare (my-modules),
///     only consulted to merge in extra official hosts.
/// Only official JM domains ever enter the pool — no third-party mirrors,
/// so the login token is never sent anywhere else.
final class LineStore {
    static let shared = LineStore()

    static let builtinAPI: [APILine] = [
        APILine(host: "www.cdnhjk.net", name: "线路1"),
        APILine(host: "www.cdnutc.cc", name: "线路2"),
        APILine(host: "www.cdngwc.net", name: "线路3"),
        APILine(host: "www.cdngwc.club", name: "线路4"),
        APILine(host: "www.cdnutc.me", name: "线路5"),
    ]

    static let builtinIMG: [String] = [
        "cdn-msp2.jmapiproxy1.cc",
        "cdn-msp3.jmapiproxy1.cc",
        "cdn-msp3.jmapiproxy3.cc",
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy2.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapiproxy2.cc",
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net",
    ]

    private static let domainFileURLs = [
        "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt",
    ]
    private static let fallbackListURL = "https://my-modules.peimingliang8.workers.dev/jm/domains.json"
    /// Public client key for JM's domain file (ships in every open client).
    private static let domainFileSecret = "diosfjckwpqpdfjkvnqQjsik"

    private let lock = NSLock()
    private var apiPool: [APILine]
    private var imgPool: [String]
    private var lastRefresh: Date?

    private let d = UserDefaults.standard
    private enum K {
        static let apiPool = "line.apiPool.v1"
        static let imgPool = "line.imgPool.v1"
        static let apiManual = "line.apiManual.v1"
        static let imgManual = "line.imgManual.v1"
        static let lastRefresh = "line.lastRefresh.v1"
    }

    private init() {
        if let data = d.data(forKey: K.apiPool),
           let saved = try? JSONDecoder().decode([APILine].self, from: data), !saved.isEmpty {
            apiPool = saved
        } else {
            apiPool = Self.builtinAPI
        }
        if let saved = d.stringArray(forKey: K.imgPool), !saved.isEmpty {
            imgPool = saved
        } else {
            imgPool = Self.builtinIMG
        }
        if let t = d.object(forKey: K.lastRefresh) as? Date { lastRefresh = t }
    }

    // MARK: - pools

    func apiLines() -> [APILine] { lock.lock(); defer { lock.unlock() }; return apiPool }
    func apiHosts() -> [String] { apiLines().map(\.host) }
    func imgHosts() -> [String] { lock.lock(); defer { lock.unlock() }; return imgPool }
    func refreshedAt() -> Date? { lock.lock(); defer { lock.unlock() }; return lastRefresh }

    // MARK: - manual selection (nil = automatic)

    var manualAPIHost: String? {
        get { let v = d.string(forKey: K.apiManual); return (v?.isEmpty == false) ? v : nil }
        set { d.set(newValue ?? "", forKey: K.apiManual) }
    }
    var manualIMGHost: String? {
        get { let v = d.string(forKey: K.imgManual); return (v?.isEmpty == false) ? v : nil }
        set { d.set(newValue ?? "", forKey: K.imgManual) }
    }

    // MARK: - remote refresh

    /// Refresh pools from the official domain file; the CF fallback only adds
    /// hosts, it never removes official ones. Throttled to once per 6 hours
    /// unless `force` is set (the settings page's manual refresh).
    func refreshIfStale(force: Bool = false) async {
        if !force, let last = refreshedAt(), Date().timeIntervalSince(last) < 6 * 3600 { return }
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 12
        let session = URLSession(configuration: cfg)

        var newAPI: [APILine]?
        for urlString in Self.domainFileURLs {
            guard let url = URL(string: urlString),
                  let (data, _) = try? await session.data(from: url),
                  let text = String(data: data, encoding: .utf8) else { continue }
            if let lines = Self.decryptDomainFile(text), !lines.isEmpty { newAPI = lines; break }
        }

        var extraAPI: [APILine] = []
        var extraIMG: [String] = []
        if let url = URL(string: Self.fallbackListURL),
           let (data, _) = try? await session.data(from: url),
           let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            if let arr = obj["api"] as? [String] {
                extraAPI = arr.map { APILine(host: $0, name: "") }
            }
            if let arr = obj["img"] as? [String] { extraIMG = arr }
        }

        lock.lock()
        if let newAPI {
            var merged = newAPI
            for line in extraAPI where !merged.contains(where: { $0.host == line.host }) {
                merged.append(line)
            }
            apiPool = merged
            if let data = try? JSONEncoder().encode(merged) { d.set(data, forKey: K.apiPool) }
        } else if !extraAPI.isEmpty {
            var merged = apiPool
            for line in extraAPI where !merged.contains(where: { $0.host == line.host }) {
                merged.append(line)
            }
            apiPool = merged
            if let data = try? JSONEncoder().encode(merged) { d.set(data, forKey: K.apiPool) }
        }
        if !extraIMG.isEmpty {
            var merged = imgPool
            for h in extraIMG where !merged.contains(h) { merged.append(h) }
            imgPool = merged
            d.set(merged, forKey: K.imgPool)
        }
        lastRefresh = Date()
        d.set(lastRefresh, forKey: K.lastRefresh)
        lock.unlock()
    }

    /// The domain file is base64 AES-256-ECB, key = md5(secret) hex string —
    /// the same construction as the API response envelope.
    static func decryptDomainFile(_ raw: String) -> [APILine]? {
        let cleaned = raw.filter { $0.isASCII && !$0.isWhitespace }
        guard !cleaned.isEmpty else { return nil }
        let key = JMCrypto.md5Hex(domainFileSecret)
        guard let plain = JMCrypto.aesECBDecrypt(base64: cleaned, keyString: key),
              let obj = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any] else { return nil }
        if let pairs = obj["jm3_Server"] as? [[String]] {
            let lines = pairs.compactMap { p -> APILine? in
                guard p.count >= 2, !p[0].isEmpty else { return nil }
                return APILine(host: p[0], name: p[1])
            }
            if !lines.isEmpty { return lines }
        }
        if let servers = obj["Server"] as? [String] {
            return servers.enumerated().map { APILine(host: $0.element, name: "线路\($0.offset + 1)") }
        }
        return nil
    }

    // MARK: - speed test

    private static let probeSession: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 10
        return URLSession(configuration: cfg)
    }()

    /// Milliseconds for a /setting round trip, or nil when the host fails.
    static func measureAPI(host: String) async -> Int? {
        var comps = URLComponents(string: "https://\(host)/setting")!
        comps.queryItems = [URLQueryItem(name: "app_img_shunt", value: "1"),
                            URLQueryItem(name: "lang", value: "TW")]
        guard let url = comps.url else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 10
        let start = Date()
        guard let (_, resp) = try? await probeSession.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return Int(Date().timeIntervalSince(start) * 1000)
    }

    /// Milliseconds to fetch one real cover image, or nil on failure.
    static func measureIMG(host: String) async -> Int? {
        guard let url = URL(string: "https://\(host)/media/albums/310311_3x4.jpg") else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 10
        let start = Date()
        guard let (data, resp) = try? await probeSession.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200, !data.isEmpty else { return nil }
        return Int(Date().timeIntervalSince(start) * 1000)
    }
}
