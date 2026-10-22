package com.zhizhou.xxtassistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubReleaseCheckerTest {
    @Test
    fun comparesSemanticVersionsAndIgnoresPrefix() {
        assertTrue(GithubReleaseChecker.isNewerThan("0.2.5", "v0.2.6"))
        assertTrue(GithubReleaseChecker.isNewerThan("0.2.5", "0.3.0"))
        assertFalse(GithubReleaseChecker.isNewerThan("0.2.5", "v0.2.5"))
        assertFalse(GithubReleaseChecker.isNewerThan("0.2.5", "v0.2.4"))
    }

    @Test
    fun rejectsDraftPrereleaseAndNonGithubReleaseUrls() {
        val draft = """{"tag_name":"v9.0.0","html_url":"https://github.com/ZivSpectra/XiaoShengAssistant/releases/tag/v9.0.0","draft":true}"""
        val prerelease = """{"tag_name":"v9.0.0","html_url":"https://github.com/ZivSpectra/XiaoShengAssistant/releases/tag/v9.0.0","prerelease":true}"""
        val wrongHost = """{"tag_name":"v9.0.0","html_url":"https://example.com/release/v9.0.0"}"""
        assertFalse(GithubReleaseChecker.parseReleaseJson(draft) != null)
        assertFalse(GithubReleaseChecker.parseReleaseJson(prerelease) != null)
        assertFalse(GithubReleaseChecker.parseReleaseJson(wrongHost) != null)
    }
}
