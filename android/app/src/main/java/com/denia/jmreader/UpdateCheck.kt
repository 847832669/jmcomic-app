package com.denia.jmreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Preserve upstream's decimal version order (2.19 < 2.2 == 2.20),
 *  then compare the fork's glass build without losing integer precision. */
internal fun isNewerReleaseVersion(latest: String, current: String): Boolean {
    val pattern = Regex("^v?(\\d+(?:\\.\\d+)?)(?:-glass\\.(\\d+))?$")
    fun parse(value: String): Pair<java.math.BigDecimal, java.math.BigInteger>? {
        val match = pattern.matchEntire(value.trim()) ?: return null
        return match.groupValues[1].toBigDecimal() to
            match.groupValues[2].ifEmpty { "0" }.toBigInteger()
    }
    val l = parse(latest) ?: return false
    val c = parse(current) ?: return false
    val base = l.first.compareTo(c.first)
    return base > 0 || (base == 0 && l.second > c.second)
}

/** Check only published releases from this fork so updates retain its features and signature. */
object UpdateCheck {
    var latestVersion by mutableStateOf<String?>(null)
        private set
    var latestUrl by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var showPrompt by mutableStateOf(false)

    private const val PREFS = "jm.updatecheck"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun currentVersion(context: Context): String =
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (e: Exception) { "0" }

    fun isUpdate(current: String): Boolean {
        return latestVersion?.let { isNewerReleaseVersion(it, current) } ?: false
    }

    /** Silent launch check, throttled to once per 6 hours; the prompt
     *  fires at most once per version (「忽略此版本」 is remembered). */
    suspend fun checkOnLaunch(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last < 6 * 3600_000L && latestVersion != null) return
        check(context, manual = false)
    }

    suspend fun check(context: Context, manual: Boolean) {
        if (checking) return
        checking = true
        errorMessage = null
        try {
            val body = withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/847832669/jmcomic-app/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    resp.body?.string()
                }
            } ?: error("空响应")
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name")
            val url = obj.optString("html_url")
            check(tag.isNotEmpty() && url.startsWith("https://github.com/847832669/jmcomic-app/releases/")) {
                "无效发布信息"
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            latestVersion = tag.removePrefix("v")
            latestUrl = url
            if (isUpdate(currentVersion(context))) {
                val ignored = prefs.getString("ignored", null)
                if (manual || ignored != latestVersion) showPrompt = true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "检查失败，请重试"
        } finally {
            checking = false
        }
    }

    fun ignore(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("ignored", latestVersion).apply()
        showPrompt = false
    }

    fun openRelease(context: Context) {
        val url = latestUrl ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
        showPrompt = false
    }
}

@Composable
fun UpdatePromptDialog(context: Context) {
    GlassAlertDialog(
        onDismissRequest = { UpdateCheck.showPrompt = false },
        title = { Text("有新版本 v${UpdateCheck.latestVersion ?: ""}") },
        text = {
            Text("当前版本 v${UpdateCheck.currentVersion(context)}，新版本已发布。点「去更新」打开下载页，下载新版 APK 覆盖安装即可。")
        },
        confirmButton = {
            TextButton(onClick = { UpdateCheck.openRelease(context) }) { Text("去更新") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { UpdateCheck.ignore(context) }) { Text("忽略此版本") }
                TextButton(onClick = { UpdateCheck.showPrompt = false }) { Text("稍后") }
            }
        },
    )
}
