package dev.herdr.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun comparesSemanticVersionsAccurately() {
        assertTrue(AppUpdateManager.isNewerVersion("v0.2.0", "0.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("0.1.1", "v0.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.0", "0.9.9"))
        assertFalse(AppUpdateManager.isNewerVersion("v0.1.0", "0.1.0"))
        assertFalse(AppUpdateManager.isNewerVersion("v0.0.9", "0.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("v0.2.0-beta1", "0.1.0"))
    }

    @Test
    fun parsesGitHubReleaseJsonAndFindsApkAsset() {
        val sampleJson = """
            {
              "tag_name": "v0.2.0",
              "name": "HerdrAndroid v0.2.0",
              "body": "- Added in-app GitHub Release auto update\n- Preserved existing user config",
              "html_url": "https://github.com/DracaeneSunbringer/HerdrAndroid/releases/tag/v0.2.0",
              "assets": [
                {
                  "name": "checksums.txt",
                  "size": 128,
                  "content_type": "text/plain",
                  "browser_download_url": "https://github.com/DracaeneSunbringer/HerdrAndroid/releases/download/v0.2.0/checksums.txt"
                },
                {
                  "name": "HerdrAndroid-v0.2.0.apk",
                  "size": 10485760,
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://github.com/DracaeneSunbringer/HerdrAndroid/releases/download/v0.2.0/HerdrAndroid-v0.2.0.apk"
                }
              ]
            }
        """.trimIndent()

        val info = AppUpdateManager.parseReleaseJson(sampleJson)
        assertEquals("v0.2.0", info.tagName)
        assertEquals("0.2.0", info.versionName)
        assertEquals("HerdrAndroid v0.2.0", info.releaseTitle)
        assertEquals("HerdrAndroid-v0.2.0.apk", info.apkAssetName)
        assertEquals(10485760L, info.apkSizeBytes)
        assertEquals(
            "https://github.com/DracaeneSunbringer/HerdrAndroid/releases/download/v0.2.0/HerdrAndroid-v0.2.0.apk",
            info.apkDownloadUrl
        )
    }
}
