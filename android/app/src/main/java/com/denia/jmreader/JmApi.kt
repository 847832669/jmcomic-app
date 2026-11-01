package com.denia.jmreader

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// JM Web API — protocol reverse-engineered & verified live 2026-11-01/02.
// Signing: Token = md5(ts + seed); Tokenparam = "<ts>,<jm3_version>".
// Response data = base64(AES-256-ECB/PKCS7(json)), key = Token hex string as UTF-8.
object JmApi {
    private const val SEED = "185Hcomic3PAPP7R"
    private val API_HOSTS = listOf("www.cdngwc.net", "www.cdnhjk.net")
    @Volatile var apiHost = API_HOSTS[0]
    @Volatile var imgHost = "cdn-msp3.jmapiproxy3.cc"
    @Volatile var version = "2.1.12"

    @Volatile var authToken: String? = null   // jwt
    @Volatile var authCookie: String? = null  // AVS value ("s" from login)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val bootstrapMutex = Mutex()
    @Volatile private var bootstrapped = false

    class ApiException(message: String) : Exception(message)

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun tokenPair(): Pair<String, String> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        return md5(ts + SEED) to "$ts,$version"
    }

    private fun decrypt(dataB64: String, token: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(token.toByteArray(Charsets.UTF_8), "AES"))
        val plain = cipher.doFinal(Base64.decode(dataB64, Base64.DEFAULT))
        return String(plain, Charsets.UTF_8)
    }

    suspend fun bootstrap() {
        if (bootstrapped) return
        bootstrapMutex.withLock {
            if (bootstrapped) return
            for (host in API_HOSTS) {
                try {
                    apiHost = host
                    val d = request("GET", "setting", emptyMap(), null, skipBootstrap = true)
                    version = d.optString("jm3_version", version)
                    imgHost = d.optString("img_host", imgHost).removePrefix("https://").trimEnd('/')
                    break
                } catch (e: Exception) { /* try next host */ }
            }
            bootstrapped = true
        }
    }

    /** Signed GET; returns decrypted `data` as JSONObject (or throws). */
    suspend fun call(path: String, params: Map<String, String> = emptyMap()): JSONObject =
        request("GET", path, params, null)

    /** Signed form POST. */
    suspend fun callForm(path: String, form: Map<String, String>): JSONObject =
        request("POST", path, emptyMap(), form)

    private suspend fun request(method: String, path: String, params: Map<String, String>, form: Map<String, String>?, skipBootstrap: Boolean = false): JSONObject {
        if (!skipBootstrap) bootstrap()
        return withContext(Dispatchers.IO) {
            val (token, tokenParam) = tokenPair()
            val allParams = mapOf("lang" to "TW") + params
            val url = StringBuilder("https://$apiHost/$path")
            url.append("?")
            url.append(allParams.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" })
            // Header set must mirror the verified web-client fingerprint exactly:
            // probes with a plain Android UA (and no Origin/Referer) hang at the API edge.
            val builder = Request.Builder().url(url.toString())
                .header("token", token)
                .header("tokenparam", tokenParam)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.7 Mobile/15E148 Safari/604.1")
                .header("Accept", "*/*")
                .header("Origin", "https://jmapp3.cc")
                .header("Referer", "https://jmapp3.cc/")
                .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            authToken?.let { builder.header("Authorization", "Bearer $it") }
            authCookie?.let { builder.header("Cookie", "AVS=$it") }
            if (method == "POST") {
                val fb = FormBody.Builder()
                form?.forEach { (k, v) -> fb.add(k, v) }
                builder.post(fb.build())
            }
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string() ?: throw ApiException("空响应")
                val env = JSONObject(body)
                val code = env.optInt("code", -1)
                if (code != 200) {
                    val msg = env.optString("errorMsg", "请求失败 ($code)")
                    throw ApiException(msg.ifEmpty { "请求失败 ($code)" })
                }
                val data = env.opt("data") ?: throw ApiException("无数据")
                when (data) {
                    is JSONObject -> data
                    is String -> {
                        if (data.isEmpty()) JSONObject()
                        else try { JSONObject(decrypt(data, token)) }
                        catch (e: Exception) { JSONObject().put("_raw", data) }
                    }
                    else -> JSONObject().put("_raw", data.toString())
                }
            }
        }
    }

    // ---------- endpoints ----------
    suspend fun promote(): List<Pair<String, List<Album>>> {
        val d = call("promote")
        val out = mutableListOf<Pair<String, List<Album>>>()
        val arr = d.optJSONArray("content") ?: return out
        for (i in 0 until arr.length()) {
            val sec = arr.optJSONObject(i) ?: continue
            val title = sec.optString("name")
            val items = parseAlbums(sec.optJSONArray("content"))
            if (items.isNotEmpty()) out.add(title to items)
        }
        return out
    }

    suspend fun latest(page: Int): List<Album> = parseAlbums(call("latest", mapOf("page" to page.toString())).optJSONArray("content"))

    suspend fun search(query: String, page: Int): Pair<Int, List<Album>> {
        val d = call("search", mapOf("search_query" to query, "page" to page.toString()))
        return d.optInt("total") to parseAlbums(d.optJSONArray("content"))
    }

    data class Category(val slug: String, val name: String)
    suspend fun categories(): List<Category> {
        val d = call("categories")
        val arr = d.optJSONArray("content") ?: return emptyList()
        val out = mutableListOf<Category>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Category(o.optString("slug"), o.optString("name")))
        }
        return out
    }

    suspend fun categoryFilter(slug: String, page: Int): List<Album> =
        parseAlbums(call("categories/filter", mapOf("c" to slug, "o" to "new", "page" to page.toString())).optJSONArray("content"))

    suspend fun randomRecommend(): List<Album> = parseAlbums(call("random_recommend").optJSONArray("content"))

    suspend fun album(id: String): JSONObject = call("album", mapOf("id" to id))
    suspend fun chapter(id: String): JSONObject = call("chapter", mapOf("id" to id))

    suspend fun login(username: String, password: String): JSONObject =
        callForm("login", mapOf("username" to username, "password" to password))

    suspend fun favorites(page: Int): Pair<Int, List<Album>> {
        val d = call("favorite", mapOf("page" to page.toString()))
        return d.optInt("total") to parseAlbums(d.optJSONArray("content"))
    }

    suspend fun addFavorite(aid: String) { callForm("favorite", mapOf("aid" to aid)) }

    suspend fun daily(userId: String): JSONObject = call("daily", mapOf("user_id" to userId))
    suspend fun dailyCheck(userId: String, dailyId: String): JSONObject =
        callForm("daily_chk", mapOf("user_id" to userId, "daily_id" to dailyId))

    // ---------- images ----------
    fun coverUrl(id: String) = "https://$imgHost/media/albums/${id}_3x4.jpg"
    fun photoUrl(chapterId: String, file: String) = "https://$imgHost/media/photos/$chapterId/$file"

    suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("图片加载失败 ${resp.code}")
            resp.body?.bytes() ?: throw ApiException("图片为空")
        }
    }

    // ---------- models ----------
    data class Album(
        val id: String,
        val name: String,
        val author: String,
        val tags: List<String>,
        val totalViews: String,
        val likes: String,
    )

    fun parseAlbums(arr: JSONArray?): List<Album> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Album>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "novel") continue
            val authors = mutableListOf<String>()
            val a = o.opt("author")
            if (a is JSONArray) for (j in 0 until a.length()) authors.add(a.optString(j))
            else if (a is String && a.isNotEmpty()) authors.add(a)
            val tags = mutableListOf<String>()
            val t = o.optJSONArray("tags")
            if (t != null) for (j in 0 until t.length()) tags.add(t.optString(j))
            out.add(Album(
                id = o.optString("id"),
                name = o.optString("name"),
                author = authors.joinToString(" / "),
                tags = tags,
                totalViews = o.optString("total_views"),
                likes = o.optString("likes"),
            ))
        }
        return out
    }

    /** uid lives in the JWT payload's SESSION object. */
    fun uidFromJwt(jwt: String): String {
        return try {
            val payload = jwt.split(".")[1]
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            val session = JSONObject(json).optJSONObject("SESSION") ?: return ""
            session.opt("uid")?.toString() ?: ""
        } catch (e: Exception) { "" }
    }
}
