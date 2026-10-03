package app.narrio.updates

import app.narrio.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.updateNumber(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: -1

enum class UpdateChannel(val label: String, val applicationId: String) {
    STABLE("Stable releases", "app.narrio"), DEV("Development previews", "app.narrio.dev");
    companion object {
        fun forApplication(applicationId: String, debug: Boolean = false): UpdateChannel? =
            if (debug) null else entries.firstOrNull { it.applicationId == applicationId }
    }
}

@Serializable
data class AppUpdate(val version: String, val code: Long, val apk: String, val bytes: Long,
                     val sha256: String, val certificate: String, val source: String,
                     val releaseUrl: String, val downloadUrl: String, val runUrl: String = "")

/** Channel, source, size and signing identity are checked before a download is offered. */
class UpdatePolicy(val channel: UpdateChannel, val installedCode: Long, val certificate: String) {
    companion object {
        const val REPOSITORY = "https://github.com/cort-robinson/Narrio"
        const val API = "https://api.github.com/repos/cort-robinson/Narrio"
        const val MAX_APK_BYTES = 256L * 1024 * 1024
    }

    fun acceptsRelease(release: JsonObject): Boolean {
        if (release.flag("draft")) return false
        val tag = release.text("tag_name")
        return when (channel) {
            UpdateChannel.STABLE -> !release.flag("prerelease") && Regex("v\\d+\\.\\d+\\.\\d+").matches(tag)
            UpdateChannel.DEV -> release.flag("prerelease") && Regex("dev-[1-9]\\d*").matches(tag)
        }
    }

    fun candidate(release: JsonObject, manifest: JsonObject): AppUpdate {
        require(acceptsRelease(release)) { "Release is from another update channel" }
        val tag = release.text("tag_name")
        val version = manifest.text("version")
        val code = manifest.updateNumber("versionCode")
        val source = manifest.text("sourceCommit")
        val apk = manifest.text("apk")
        val bytes = manifest.updateNumber("bytes")
        val sha = manifest.text("sha256")
        val signer = manifest.text("certificateSha256")
        require(manifest.text("applicationId") == channel.applicationId && code > installedCode && code <= 2_100_000_000) { "Update package or version is incompatible" }
        require(Regex("[a-f0-9]{40}").matches(source) && Regex("[a-f0-9]{64}").matches(sha) && signer == certificate) { "Update signing identity is incompatible" }
        require(bytes in 1..MAX_APK_BYTES && apk == "Narrio-$version.apk") { "Invalid update asset" }
        when (channel) {
            UpdateChannel.STABLE -> {
                require(version == tag.removePrefix("v")) { "Stable version differs from its tag" }
                val parts = version.split('.').map { it.toLong() }
                require(parts[0] <= 2100 && parts[1] < 1000 && parts[2] < 1000 && code == parts[0] * 1_000_000 + parts[1] * 1000 + parts[2]) { "Invalid stable version code" }
            }
            UpdateChannel.DEV -> require(Regex("\\d+\\.\\d+\\.\\d+-dev\\.$code").matches(version) && tag == "dev-$code") { "Invalid preview run number" }
        }
        val download = "$REPOSITORY/releases/download/$tag/$apk"
        require(release.objects("assets").count { it.text("name") == apk && it.text("browser_download_url") == download && it.updateNumber("size") == bytes } == 1) { "APK asset differs from its manifest" }
        val runUrl = manifest.text("workflowRun")
        if (channel == UpdateChannel.DEV) require(Regex("${Regex.escape(REPOSITORY)}/actions/runs/[1-9]\\d*").matches(runUrl)) { "Invalid preview workflow source" }
        return AppUpdate(version, code, apk, bytes, sha, signer, source, "$REPOSITORY/releases/tag/$tag", download, runUrl)
    }

    fun passedPreview(update: AppUpdate, run: JsonObject): Boolean =
        run.text("status") == "completed" && run.text("conclusion") == "success" &&
            run.text("event") == "push" && run.text("head_branch") == "dev" &&
            run.text("head_sha") == update.source && run.updateNumber("run_number") == update.code &&
            run.text("html_url") == update.runUrl && run.text("path") == ".github/workflows/ci.yml"

    fun acceptsCached(update: AppUpdate): Boolean = runCatching {
        val tag = when (channel) { UpdateChannel.STABLE -> "v${update.version}"; UpdateChannel.DEV -> "dev-${update.code}" }
        update.code > installedCode && update.code <= 2_100_000_000 && update.certificate == certificate &&
            update.bytes in 1..MAX_APK_BYTES && Regex("[a-f0-9]{64}").matches(update.sha256) &&
            Regex("[a-f0-9]{40}").matches(update.source) &&
            Regex("\\d+\\.\\d+\\.\\d+${if (channel == UpdateChannel.DEV) "-dev\\.${update.code}" else ""}").matches(update.version) &&
            update.apk == "Narrio-${update.version}.apk" &&
            update.downloadUrl == "$REPOSITORY/releases/download/$tag/${update.apk}" && update.releaseUrl == "$REPOSITORY/releases/tag/$tag" &&
            (channel != UpdateChannel.DEV || Regex("${Regex.escape(REPOSITORY)}/actions/runs/[1-9]\\d*").matches(update.runUrl))
    }.getOrDefault(false)
}

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY, INSTALLING, CONFIRMATION }
data class UpdateState(val channel: UpdateChannel?, val automatic: Boolean = true, val phase: UpdatePhase = UpdatePhase.IDLE,
                       val available: AppUpdate? = null, val progress: Float = 0f, val lastChecked: Long = 0,
                       val error: String? = null, val permissionNeeded: Boolean = false, val waitingForPlayback: Boolean = false)

fun automaticInstallAllowed(automatic: Boolean, permission: Boolean, playing: Boolean, buffering: Boolean,
                            pendingConfirmation: Boolean, foreground: Boolean, sdk: Int): Boolean =
    automatic && permission && !playing && !buffering && !pendingConfirmation && !foreground && sdk >= 31

fun hasSettledBackground(foreground: Boolean, hiddenAt: Long, now: Long): Boolean =
    !foreground && (hiddenAt == 0L || now - hiddenAt >= 5_000)
