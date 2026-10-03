package app.narrio.updates

import app.narrio.data.NarrioJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal val signer = "b".repeat(64)
internal fun objectJson(value: String) = NarrioJson.parseToJsonElement(value).jsonObject
internal fun manifest(dev: Boolean = true, code: Long = if (dev) 60 else 1_005_000, size: Int = 4, sha: String = "c".repeat(64)): JsonObject {
    val version = if (dev) "1.5.0-dev.$code" else "1.5.0"
    return objectJson("""{"applicationId":"app.narrio${if (dev) ".dev" else ""}","version":"$version","versionCode":$code,
        "apk":"Narrio-$version.apk","bytes":$size,"sha256":"$sha","certificateSha256":"$signer",
        "sourceCommit":"${"a".repeat(40)}","workflowRun":"${UpdatePolicy.REPOSITORY}/actions/runs/$code"}""")
}
internal fun release(dev: Boolean = true, code: Long = if (dev) 60 else 1_005_000, size: Int = 4): JsonObject {
    val version = if (dev) "1.5.0-dev.$code" else "1.5.0"
    val tag = if (dev) "dev-$code" else "v$version"
    val base = "${UpdatePolicy.REPOSITORY}/releases/download/$tag"
    return objectJson("""{"draft":false,"prerelease":$dev,"tag_name":"$tag","assets":[
        {"name":"release-manifest.json","browser_download_url":"$base/release-manifest.json"},
        {"name":"Narrio-$version.apk","browser_download_url":"$base/Narrio-$version.apk","size":$size}]}""")
}
internal fun workflow(code: Long = 60, conclusion: String = "success") = objectJson("""{"status":"completed","conclusion":"$conclusion",
    "event":"push","head_branch":"dev","head_sha":"${"a".repeat(40)}","run_number":$code,
    "html_url":"${UpdatePolicy.REPOSITORY}/actions/runs/$code","path":".github/workflows/ci.yml"}""")
internal fun JsonObject.changed(key: String, value: JsonElement) = JsonObject(toMutableMap().apply { put(key, value) })

class UpdatePolicyTest {
    @Test fun channelComesFromInstalledIdentityAndDebugNeverUpdates() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.forApplication("app.narrio"))
        assertEquals(UpdateChannel.DEV, UpdateChannel.forApplication("app.narrio.dev"))
        assertNull(UpdateChannel.forApplication("app.narrio.local"))
        assertNull(UpdateChannel.forApplication("app.narrio", true))
    }

    @Test fun stableAndDevCannotCrossChannelsOrDowngrade() {
        val stable = UpdatePolicy(UpdateChannel.STABLE, 1_004_002, signer)
        val dev = UpdatePolicy(UpdateChannel.DEV, 58, signer)
        assertEquals(1_005_000L, stable.candidate(release(false), manifest(false)).code)
        assertEquals(60L, dev.candidate(release(), manifest()).code)
        assertFalse(stable.acceptsRelease(release()))
        assertFalse(dev.acceptsRelease(release(false)))
        assertFalse(dev.acceptsRelease(release().changed("draft", JsonPrimitive(true))))
        assertThrows(IllegalArgumentException::class.java) { dev.candidate(release(true, 58), manifest(true, 58)) }
    }

    @Test fun rejectsWrongSignerPackageCodeSizeAndUntrustedAsset() {
        val policy = UpdatePolicy(UpdateChannel.DEV, 58, signer)
        for ((key, value) in listOf("certificateSha256" to JsonPrimitive("d".repeat(64)),
            "applicationId" to JsonPrimitive("app.narrio"), "versionCode" to JsonPrimitive(60.5),
            "bytes" to JsonPrimitive(UpdatePolicy.MAX_APK_BYTES + 1), "apk" to JsonPrimitive("../update.apk"),
            "sha256" to JsonPrimitive("bad"), "workflowRun" to JsonPrimitive("https://example.com/60"))) {
            assertThrows("Must reject $key", IllegalArgumentException::class.java) { policy.candidate(release(), manifest().changed(key, value)) }
        }
        val redirected = release().toString().replace("https://github.com", "https://example.com")
        assertThrows(IllegalArgumentException::class.java) { policy.candidate(objectJson(redirected), manifest()) }
        val candidate = policy.candidate(release(), manifest())
        assertTrue(policy.acceptsCached(candidate))
        assertFalse(policy.acceptsCached(candidate.copy(apk = "../update.apk")))
        assertFalse(policy.acceptsCached(candidate.copy(downloadUrl = "https://example.com/update.apk")))
    }

    @Test fun previewRequiresCompletedPassingTrustedPushFromExactSource() {
        val policy = UpdatePolicy(UpdateChannel.DEV, 58, signer)
        val update = policy.candidate(release(), manifest())
        assertTrue(policy.passedPreview(update, workflow()))
        for ((key, value) in listOf("conclusion" to "failure", "status" to "in_progress", "event" to "pull_request",
            "head_branch" to "master", "head_sha" to "d".repeat(40), "path" to ".github/workflows/other.yml")) {
            assertFalse("Must reject $key", policy.passedPreview(update, workflow().changed(key, JsonPrimitive(value))))
        }
        assertFalse(policy.passedPreview(update, workflow(61)))
    }

    @Test fun automaticInstallationWaitsForAudioForegroundPermissionAndSupportedAndroid() {
        assertTrue(automaticInstallAllowed(true, true, false, false, false, false, 31))
        assertFalse(automaticInstallAllowed(false, true, false, false, false, false, 36))
        assertFalse(automaticInstallAllowed(true, false, false, false, false, false, 36))
        assertFalse(automaticInstallAllowed(true, true, true, false, false, false, 36))
        assertFalse(automaticInstallAllowed(true, true, false, true, false, false, 36))
        assertFalse(automaticInstallAllowed(true, true, false, false, true, false, 36))
        assertFalse(automaticInstallAllowed(true, true, false, false, false, true, 36))
        assertFalse(automaticInstallAllowed(true, true, false, false, false, false, 30))
    }

    @Test fun briefActivityRecreationDoesNotCountAsLeavingTheApp() {
        assertFalse(hasSettledBackground(true, 0, 20_000))
        assertFalse(hasSettledBackground(false, 10_000, 10_100))
        assertFalse(hasSettledBackground(false, 10_000, 14_999))
        assertTrue(hasSettledBackground(false, 10_000, 15_000))
        assertTrue(hasSettledBackground(false, 0, 20_000))
        assertFalse(hasSettledBackground(false, 0, 20_000) && hasSettledBackground(true, 0, 20_000))
    }
}
