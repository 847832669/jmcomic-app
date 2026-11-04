import Foundation

enum JMError: Error {
    case badResponse, decryptFailed, http(Int)
    case server(String)   // business error with the server's own message (errorMsg)
}

/// Client for the JM (jmapp3) JSON API.
/// Protocol (reverse-engineered from the official web client and verified live):
///  - bootstrap: GET /setting -> data.jm3_version (no signing headers needed)
///  - every request: headers  token = md5(ts + seed),  tokenparam = "<ts>,<jm3_version>"
///  - when signed in: Authorization: Bearer <jwttoken>, Cookie: AVS=<member s>
///  - response envelope {code, data, errorMsg?}; `data` for content endpoints is a base64
///    AES-256-ECB ciphertext whose key is the token hex string itself.
final class JMClient {
    static let shared = JMClient()

    private let seed = "185Hcomic3PAPP7R"
    /// Live domain pool (official lines only, managed by LineStore).
    private var apiHosts: [String] { LineStore.shared.apiHosts() }
    /// Last host that answered successfully; tried first for serial calls.
    private var preferredHost: String?
    private var version: String?
    private let session: URLSession

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 25
        cfg.waitsForConnectivity = true
        session = URLSession(configuration: cfg)
    }

    // MARK: - content endpoints

    func latest(page: Int) async throws -> [Comic] {
        let data = try await request("latest", params: ["page": "\(page)"])
        guard let arr = data as? [Any] else { return [] }
        return arr.compactMap { ($0 as? [String: Any]).map(Comic.init(dict:)) }
    }

    /// Search with the API's full parameter set (verified live 2026-11-04):
    /// `o` order — mr 最新 / mv 最多观看 / tf 最多爱心 / mp 最多图片;
    /// `main_tag` — 0 全部 / 1 作品 / 2 作者 / 3 标签 / 4 登场人物.
    /// A pure-numeric query (a JM id) answers with `redirect_aid` instead of
    /// a result list. The `t` time parameter is accepted by the server but
    /// ignored for search, so it is not exposed.
    func search(query: String, page: Int, order: String = "mr", mainTag: Int = 0)
        async throws -> (total: Int, items: [Comic], redirectAid: String?) {
        let data = try await request("search", params: [
            "search_query": query, "page": "\(page)", "o": order, "main_tag": "\(mainTag)",
        ])
        guard let obj = data as? [String: Any] else { return (0, [], nil) }
        let redirect = anyString(obj["redirect_aid"])
        return (anyInt(obj["total"]), Self.comicList(obj), redirect.isEmpty ? nil : redirect)
    }

    func album(id: String) async throws -> AlbumDetail {
        let data = try await request("album", params: ["id": id])
        guard let obj = data as? [String: Any] else { throw JMError.badResponse }
        return AlbumDetail(dict: obj)
    }

    func chapter(id: String) async throws -> ChapterData {
        let data = try await request("chapter", params: ["id": id])
        guard let obj = data as? [String: Any] else { throw JMError.badResponse }
        return ChapterData(dict: obj)
    }

    func promote() async throws -> [PromoSection] {
        let data = try await request("promote", params: [:])
        guard let arr = data as? [Any] else { return [] }
        return arr.compactMap { ($0 as? [String: Any]).flatMap(PromoSection.init(dict:)) }
    }

    func randomRecommend() async throws -> [Comic] {
        let data = try await request("random_recommend", params: [:])
        guard let arr = data as? [Any] else { return [] }
        return arr.compactMap { ($0 as? [String: Any]).map(Comic.init(dict:)) }
    }

    // MARK: - categories

    func categories() async throws -> [JMCategory] {
        let data = try await request("categories", params: [:])
        guard let obj = data as? [String: Any], let arr = obj["categories"] as? [Any] else { return [] }
        return arr.compactMap { ($0 as? [String: Any]).map(JMCategory.init(dict:)) }
            .filter { !$0.slug.isEmpty }
    }

    /// Category listing; `c` is the category slug (e.g. "doujin"), `o` the sort order ("new").
    func categoryFilter(slug: String, page: Int) async throws -> (total: Int, items: [Comic]) {
        let data = try await request("categories/filter",
                                      params: ["c": slug, "o": "new", "page": "\(page)"])
        guard let obj = data as? [String: Any] else { return (0, []) }
        return (anyInt(obj["total"]), Self.comicList(obj))
    }

    // MARK: - member endpoints

    // MARK: daily check-in

    func dailyInfo(userId: Int) async throws -> DailyInfo {
        let data = try await request("daily", params: ["user_id": "\(userId)"])
        guard let dict = data as? [String: Any] else { throw JMError.badResponse }
        return DailyInfo(dict: dict)
    }

    /// Returns the server's reward message (e.g. "Jcoin:2 EXP:2"); daily_id is required —
    /// without it the server silently returns an empty success and nothing is signed.
    func dailyCheck(userId: Int, dailyId: Int) async throws -> String {
        let data = try await postForm("daily_chk", fields: ["user_id": "\(userId)", "daily_id": "\(dailyId)"])
        if let dict = data as? [String: Any], let msg = dict["msg"] as? String { return msg }
        return ""
    }

    /// On success returns the raw member-info dictionary (contains jwttoken, s, username...).
    func login(username: String, password: String) async throws -> [String: Any] {
        let data = try await postForm("login", fields: ["username": username, "password": password])
        guard let obj = data as? [String: Any] else { throw JMError.badResponse }
        return obj
    }

    /// One page of cloud favorites in one folder ("0" = default folder).
    /// The response also carries `folder_list` (all folders: FID + name).
    func favorites(page: Int, folderId: String = "0")
        async throws -> (total: Int, items: [Comic], folders: [FavFolder]) {
        let data = try await request("favorite", params: ["page": "\(page)", "folder_id": folderId])
        if let obj = data as? [String: Any] {
            var folders: [FavFolder] = []
            if let fl = obj["folder_list"] as? [Any] {
                folders = fl.compactMap { ($0 as? [String: Any]).map {
                    FavFolder(id: anyString($0["FID"]), name: anyString($0["name"]))
                } }.filter { !$0.id.isEmpty }
            }
            return (anyInt(obj["total"]), Self.comicList(obj), folders)
        }
        if let arr = data as? [Any] {
            let items = arr.compactMap { ($0 as? [String: Any]).map(Comic.init(dict:)) }
            return (items.count, items, [])
        }
        return (0, [], [])
    }

    /// Folder management lives on POST favorite_folder (verified against the
    /// API ecosystem): type=add/del/move.
    func favoriteFolderAdd(name: String) async throws {
        _ = try await postForm("favorite_folder", fields: ["type": "add", "folder_name": name])
    }

    func favoriteFolderDelete(folderId: String) async throws {
        _ = try await postForm("favorite_folder", fields: ["type": "del", "folder_id": folderId])
    }

    /// A comic lives in exactly one folder; moving reassigns it.
    func favoriteFolderMove(albumId: String, folderId: String) async throws {
        _ = try await postForm("favorite_folder",
                               fields: ["type": "move", "folder_id": folderId, "aid": albumId])
    }

    /// The favorite endpoint is a TOGGLE (verified live 2026-11-05):
    /// every POST flips the server-side state and the response `type`
    /// reports what actually happened ("add" / "remove"); a `type`
    /// form field is ignored by the server. Returns whether the album
    /// is favorited after the call, per the server's own report.
    func toggleFavorite(albumId: String) async throws -> Bool {
        let data = try await postForm("favorite", fields: ["aid": albumId])
        guard let dict = data as? [String: Any],
              !anyString(dict["type"]).isEmpty else { throw JMError.badResponse }
        return anyString(dict["type"]) == "add"
    }

    static func comicList(_ obj: [String: Any]) -> [Comic] {
        let raw = (obj["content"] as? [Any]) ?? (obj["list"] as? [Any]) ?? []
        return raw.compactMap { ($0 as? [String: Any]).map(Comic.init(dict:)) }
    }

    // MARK: - transport

    private func bootstrapVersion() async {
        if version != nil { return }
        // Race both hosts for /setting too — serial order made a slow first
        // host tax the very first call of every launch.
        let found = await withTaskGroup(of: String?.self) { group in
            for host in apiHosts {
                group.addTask { await self.fetchVersion(host: host) }
            }
            var first: String?
            for await v in group {
                if first == nil, let v { first = v; group.cancelAll() }
            }
            return first
        }
        version = found ?? "2.1.12"
    }

    private func fetchVersion(host: String) async -> String? {
        var comps = URLComponents(string: "https://\(host)/setting")!
        comps.queryItems = [URLQueryItem(name: "app_img_shunt", value: "1"),
                            URLQueryItem(name: "lang", value: "TW")]
        guard let url = comps.url else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 8
        req.setValue(Self.ua, forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await session.data(for: req),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = obj["data"] as? [String: Any],
              let v = d["jm3_version"] as? String, !v.isEmpty else { return nil }
        return v
    }

    private static let ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.7 Mobile/15E148 Safari/604.1"

    private func orderedHosts() -> [String] {
        let hosts = apiHosts
        if let manual = LineStore.shared.manualAPIHost, hosts.contains(manual) {
            return [manual] + hosts.filter { $0 != manual }
        }
        guard let preferredHost, hosts.contains(preferredHost) else { return hosts }
        return [preferredHost] + hosts.filter { $0 != preferredHost }
    }

    private func request(_ path: String, params: [String: String]) async throws -> Any {
        await bootstrapVersion()
        // Race both API hosts and take the first success. GETs are idempotent,
        // and the hosts' latency differs wildly by network: serial failover
        // made every call pay the slow host first (measured on one network:
        // host A search 0.5s, host B 11–30s). This was the main reason the
        // search page felt stuck.
        let hosts = orderedHosts()
        if hosts.count == 1 { return try await getOnce(host: hosts[0], path: path, params: params) }
        if LineStore.shared.manualAPIHost != nil {
            // Manual line: the pinned host goes alone first; only a hard
            // failure falls back to racing the rest of the pool.
            do {
                let value = try await getOnce(host: hosts[0], path: path, params: params)
                preferredHost = hosts[0]
                return value
            } catch {
                return try await raceHosts(Array(hosts.dropFirst()), path: path, params: params)
            }
        }
        return try await raceHosts(hosts, path: path, params: params)
    }

    private func raceHosts(_ hosts: [String], path: String, params: [String: String]) async throws -> Any {
        return try await withThrowingTaskGroup(of: (String, Result<Any, Error>).self) { group in
            for host in hosts {
                group.addTask {
                    do { return (host, .success(try await self.getOnce(host: host, path: path, params: params))) }
                    catch { return (host, .failure(error)) }
                }
            }
            var lastError: Error = JMError.badResponse
            for try await (host, result) in group {
                switch result {
                case .success(let value):
                    preferredHost = host
                    group.cancelAll()
                    return value
                case .failure(let error):
                    lastError = error
                }
            }
            throw lastError
        }
    }

    private func postForm(_ path: String, fields: [String: String]) async throws -> Any {
        await bootstrapVersion()
        // POSTs are not raced (check-in / favorite must not fire twice);
        // serial, but the last-good host goes first.
        var lastError: Error = JMError.badResponse
        for host in orderedHosts() {
            do { return try await postOnce(host: host, path: path, fields: fields) }
            catch { lastError = error }
        }
        throw lastError
    }

    private func signedRequest(host: String, path: String, params: [String: String]) -> (URLRequest, String) {
        let ts = "\(Int(Date().timeIntervalSince1970))"
        let token = JMCrypto.md5Hex(ts + seed)
        var comps = URLComponents(string: "https://\(host)/\(path)")!
        var items = params.map { URLQueryItem(name: $0.key, value: $0.value) }
        items.append(URLQueryItem(name: "lang", value: "TW"))
        comps.queryItems = items
        var req = URLRequest(url: comps.url!)
        req.timeoutInterval = 12
        req.setValue(token, forHTTPHeaderField: "token")
        req.setValue("\(ts),\(version ?? "2.1.12")", forHTTPHeaderField: "tokenparam")
        req.setValue(Self.ua, forHTTPHeaderField: "User-Agent")
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        if let auth = AccountStore.currentAuth {
            req.setValue("Bearer \(auth.jwt)", forHTTPHeaderField: "Authorization")
            if !auth.avs.isEmpty { req.setValue("AVS=\(auth.avs)", forHTTPHeaderField: "Cookie") }
        }
        return (req, token)
    }

    private func getOnce(host: String, path: String, params: [String: String]) async throws -> Any {
        let (req, token) = signedRequest(host: host, path: path, params: params)
        let (data, resp) = try await session.data(for: req)
        return try decodeEnvelope(data: data, response: resp, token: token)
    }

    private func postOnce(host: String, path: String, fields: [String: String]) async throws -> Any {
        var (req, token) = signedRequest(host: host, path: path, params: [:])
        req.httpMethod = "POST"
        let boundary = "----JMReaderBoundary7d1a9"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        var body = Data()
        for (k, v) in fields {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(k)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(v)\r\n".data(using: .utf8)!)
        }
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        req.httpBody = body
        let (data, resp) = try await session.data(for: req)
        return try decodeEnvelope(data: data, response: resp, token: token)
    }

    private func decodeEnvelope(data: Data, response: URLResponse, token: String) throws -> Any {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw JMError.badResponse
        }
        let code = anyInt(obj["code"])
        if code != 200 {
            if let msg = obj["errorMsg"] as? String, !msg.isEmpty {
                throw JMError.server(msg)
            }
            if let http = response as? HTTPURLResponse { throw JMError.http(http.statusCode) }
            throw JMError.badResponse
        }
        let payload = obj["data"]
        if let enc = payload as? String {
            guard let plain = JMCrypto.aesECBDecrypt(base64: enc, keyString: token),
                  let decoded = try? JSONSerialization.jsonObject(with: plain) else {
                throw JMError.decryptFailed
            }
            return decoded
        }
        return payload as Any
    }
}
