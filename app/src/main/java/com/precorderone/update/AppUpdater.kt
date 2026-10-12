package com.precorderone.update

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.precorderone.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

/**
 * In-app updates from GitHub releases, same flow as Mau-Mau Flip: check the latest release at most once a day,
 * download the APK only on request, verify size and SHA-256 against the GitHub API, then package name, higher
 * versionCode, announced versionName and identical signature before the system installer gets the file.
 *
 * A release qualifies when it is published (no draft/pre-release), tagged `vX.Y.Z` and carries exactly one asset
 * named `PrecorderOne-X.Y.Z.apk` from this repository.
 */
object AppUpdater {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data object NoRelease : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Ready(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    data class Release(
        val version: String,
        val notes: String,
        val size: Long,
        val sha256: String,
        val url: String,
        val raw: String
    ) {
        val pageUrl: String get() = "https://github.com/$REPOSITORY/releases/tag/v$version"
    }

    private const val TAG = "AppUpdater"
    private const val REPOSITORY = "ShakieVan/Precorder-One"
    private const val ENDPOINT = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val ASSET = "PrecorderOne-%s.apk"
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val CHECK_TIMEOUT_MS = 12_000
    // A download only fails when no byte arrives for this long, however slow the connection is.
    private const val STALL_TIMEOUT_MS = 30_000
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_RELEASE = "update_release"

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Version the main screen already announced in this process (one hint per start, not per screen change). */
    var announcedVersion: String? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val cached = parseRelease(prefs().getString(KEY_RELEASE, null))
        if (cached != null && compareVersions(cached.version, currentVersion()) > 0) {
            mutableState.value = if (apkFile(cached).exists()) State.Ready(cached) else State.Available(cached)
        }
        // Leftovers of aborted downloads and already installed updates; only the APK of a pending update stays.
        prune((mutableState.value as? State.Ready)?.release)
    }

    fun currentVersion(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0.0.0"

    fun releaseOf(state: State): Release? = when (state) {
        is State.Available -> state.release
        is State.Downloading -> state.release
        is State.Ready -> state.release
        is State.Failed -> state.release
        else -> null
    }

    fun statusText(context: Context, state: State): String = when (state) {
        State.Idle -> {
            val last = prefs().getLong(KEY_LAST_CHECK, 0L)
            if (last > 0L) {
                val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
                context.getString(R.string.update_last_checked, time)
            } else {
                context.getString(R.string.update_not_checked)
            }
        }
        State.Checking -> context.getString(R.string.update_checking)
        State.UpToDate -> context.getString(R.string.update_up_to_date)
        State.NoRelease -> context.getString(R.string.update_no_release)
        is State.Available -> context.getString(R.string.update_available, state.release.version)
        is State.Downloading -> context.getString(R.string.update_downloading, state.percent)
        is State.Ready -> context.getString(R.string.update_ready, state.release.version)
        is State.Failed -> state.message
    }

    /** Automatic checks run at most once a day; [manual] checks always ask GitHub. */
    fun check(manual: Boolean) {
        when (mutableState.value) {
            State.Checking, is State.Downloading -> return
            else -> Unit
        }
        if (!manual && System.currentTimeMillis() - prefs().getLong(KEY_LAST_CHECK, 0L) < DAY_MS) return
        mutableState.value = State.Checking
        scope.launch {
            mutableState.value = runCatching { fetchLatest() }.getOrElse {
                Log.w(TAG, "Update check failed", it)
                State.Failed(app.getString(R.string.update_no_connection))
            }
        }
    }

    private fun fetchLatest(): State {
        val connection = openConnection(ENDPOINT, CHECK_TIMEOUT_MS).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            // 404: nothing published yet. A valid answer, so the daily check counts as done.
            if (code == 404) {
                markChecked(null)
                return State.NoRelease
            }
            if (code != 200) return State.Failed(failureText(connection, code))
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = parseRelease(body)
            markChecked(release?.raw)
            return when {
                release == null -> State.NoRelease
                compareVersions(release.version, currentVersion()) <= 0 -> State.UpToDate
                apkFile(release).exists() -> State.Ready(release)
                else -> State.Available(release)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun download() {
        val release = when (val current = mutableState.value) {
            is State.Available -> current.release
            is State.Failed -> current.release
            else -> null
        } ?: return
        mutableState.value = State.Downloading(release, 0)
        scope.launch {
            mutableState.value = runCatching { downloadVerified(release) }.getOrElse {
                Log.w(TAG, "Update download failed", it)
                partFile(release).delete()
                State.Failed(app.getString(R.string.update_download_failed), release)
            }
        }
    }

    private fun downloadVerified(release: Release): State {
        updatesDir().mkdirs()
        prune(release)
        val part = partFile(release)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val connection = openConnection(release.url, STALL_TIMEOUT_MS).apply {
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            var lastPercent = -1
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > release.size) error("Download larger than announced")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val percent = (total * 100 / release.size).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            mutableState.value = State.Downloading(release, percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (total != release.size || hash != release.sha256) {
            part.delete()
            return State.Failed(app.getString(R.string.update_checksum_failed), release)
        }
        if (!part.renameTo(apkFile(release))) error("Could not move download into place")
        return State.Ready(release)
    }

    /** Starts the system installer. Returns null on success, otherwise the reason for the user. */
    fun install(activity: Activity): String? {
        val release = (mutableState.value as? State.Ready)?.release ?: return null
        if (!activity.packageManager.canRequestPackageInstalls()) {
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }
            return activity.getString(R.string.update_allow_install)
        }
        val file = apkFile(release)
        verify(file, release.version)?.let { problem ->
            // A rejected file never gets offered again.
            file.delete()
            val message = activity.getString(R.string.update_rejected, problem)
            mutableState.value = State.Failed(message, release)
            return message
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("PrecorderOne-Update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            null
        }.getOrElse { activity.getString(R.string.update_install_failed, it.message ?: it.toString()) }
    }

    @Suppress("DEPRECATION")
    private fun verify(file: File, version: String): String? = runCatching {
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = pm.getPackageInfo(app.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.path, flags)
            ?: return@runCatching app.getString(R.string.update_verify_unreadable)
        when {
            candidate.packageName != app.packageName -> app.getString(R.string.update_verify_package)
            candidate.longVersionCode <= installed.longVersionCode -> app.getString(R.string.update_verify_not_newer)
            candidate.versionName != version -> app.getString(R.string.update_verify_version)
            signers(candidate).isEmpty() || signers(installed) != signers(candidate) ->
                app.getString(R.string.update_verify_signature)
            else -> null
        }
    }.getOrElse { app.getString(R.string.update_verify_failed, it.toString()) }

    private fun signers(info: PackageInfo): Set<Signature> =
        info.signingInfo?.apkContentsSigners?.toSet().orEmpty()

    internal fun parseRelease(json: String?): Release? {
        if (json.isNullOrBlank()) return null
        val data = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (data.optBoolean("draft", true) || data.optBoolean("prerelease", true)) return null
        val tag = data.optString("tag_name")
        val version = tag.removePrefix("v")
        if (!tag.startsWith("v") || !isValidVersion(version)) return null
        val name = ASSET.format(version)
        val assets = data.optJSONArray("assets") ?: return null
        val asset = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name") == name }
            .singleOrNull() ?: return null
        val size = asset.optLong("size", 0L)
        val digest = asset.optString("digest")
        val sha256 = digest.removePrefix("sha256:").lowercase()
        val url = asset.optString("browser_download_url")
        if (size <= 0L || size > MAX_APK_BYTES) return null
        if (!digest.startsWith("sha256:") || sha256.length != 64 || !sha256.all { it in '0'..'9' || it in 'a'..'f' }) return null
        if (url != "https://github.com/$REPOSITORY/releases/download/$tag/$name") return null
        return Release(version, data.optString("body").take(6000), size, sha256, url, json)
    }

    internal fun isValidVersion(text: String): Boolean {
        val parts = text.split(".")
        return parts.size == 3 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }
    }

    /** Numeric per part (0.10.0 > 0.9.9): positive when [a] is newer. */
    internal fun compareVersions(a: String, b: String): Int {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until 3) {
            val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun failureText(connection: HttpURLConnection, code: Int): String {
        // Without login GitHub allows 60 requests per hour and IP; a shared Wi-Fi can exhaust that.
        if (code != 403 && code != 429) return app.getString(R.string.update_github_error, code)
        val retryAfter = connection.getHeaderField("retry-after")?.toLongOrNull()
        val reset = connection.getHeaderField("x-ratelimit-reset")?.toLongOrNull()
        val waitSeconds = retryAfter ?: if (connection.getHeaderField("x-ratelimit-remaining") == "0" && reset != null) {
            (reset - System.currentTimeMillis() / 1000).coerceAtLeast(0)
        } else {
            null
        }
        waitSeconds ?: return app.getString(R.string.update_rate_limited)
        return app.getString(R.string.update_rate_limited_minutes, ((waitSeconds + 59) / 60).coerceAtLeast(1))
    }

    private fun openConnection(url: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CHECK_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", "PrecorderOne/${currentVersion()}")
        }

    private fun markChecked(rawRelease: String?) {
        prefs().edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putString(KEY_RELEASE, rawRelease)
            .apply()
    }

    private fun prune(keep: Release?) {
        val keepName = keep?.let { apkFile(it).name }
        updatesDir().listFiles()?.forEach { if (it.name != keepName) it.delete() }
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(app)
    private fun updatesDir() = File(app.filesDir, "updates")
    private fun apkFile(release: Release) = File(updatesDir(), "${release.sha256}.apk")
    private fun partFile(release: Release) = File(updatesDir(), "${release.sha256}.apk.part")
}
