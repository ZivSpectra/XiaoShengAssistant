package com.zhizhou.xxtassistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewCookieTargetTest {
    @Test
    fun acceptsOnlyAbsoluteHttpUrlsForAcademicNavigation() {
        assertTrue(WebViewCookieTarget.isHttpUrl("https://portal.example.edu/login"))
        assertTrue(WebViewCookieTarget.isHttpUrl("http://10.0.2.2:8080/jw"))
        assertFalse(WebViewCookieTarget.isHttpUrl("javascript:alert(1)"))
        assertFalse(WebViewCookieTarget.isHttpUrl("file:///sdcard/table.html"))
        assertFalse(WebViewCookieTarget.isHttpUrl("https://"))
        assertFalse(WebViewCookieTarget.isHttpUrl("https://user:password@portal.example.edu/login"))
    }

    @Test
    fun academicImportTopLevelNavigationRequiresHttps() {
        assertTrue(WebViewCookieTarget.isHttpsUrl("https://portal.ccsu.cn/jwglxt"))
        assertTrue(WebViewCookieTarget.isSafeAcademicTopLevelUrl("about:blank"))
        assertTrue(WebViewCookieTarget.isSafeAcademicTopLevelUrl("https://jwxt.ccsu.cn/login"))
        assertFalse(WebViewCookieTarget.isHttpsUrl("http://portal.ccsu.cn/jwglxt"))
        assertFalse(WebViewCookieTarget.isSafeAcademicTopLevelUrl("http://portal.ccsu.cn/jwglxt"))
        assertFalse(WebViewCookieTarget.isSafeAcademicTopLevelUrl("file:///sdcard/schedule.html"))
        assertFalse(WebViewCookieTarget.isSafeAcademicTopLevelUrl("javascript:alert(1)"))
        assertFalse(WebViewCookieTarget.isHttpsUrl("https://user:password@portal.ccsu.cn/login"))
    }

    @Test
    fun normalizesBareAcademicHostToHttpsWithoutOpeningCleartextUrls() {
        assertEquals(
            "https://portal.ccsu.cn",
            WebViewCookieTarget.normalizeAcademicUrl("  portal.ccsu.cn  ")
        )
        assertEquals(
            "https://jwxt.ccsu.cn/jwglxt/kbcx",
            WebViewCookieTarget.normalizeAcademicUrl("jwxt.ccsu.cn/jwglxt/kbcx")
        )
        assertEquals(null, WebViewCookieTarget.normalizeAcademicUrl("http://portal.ccsu.cn"))
        assertEquals(null, WebViewCookieTarget.normalizeAcademicUrl("javascript:alert(1)"))
    }

    @Test
    fun learningPlatformTopLevelNavigationRequiresHttpsAndChaoxingHost() {
        assertTrue(WebViewCookieTarget.isSecureLearningPlatformUrl("https://passport2.chaoxing.com/login"))
        assertTrue(WebViewCookieTarget.isSecureLearningPlatformUrl("https://mooc1-api.chaoxing.com/api"))
        assertFalse(WebViewCookieTarget.isSecureLearningPlatformUrl("http://passport2.chaoxing.com/login"))
        assertFalse(WebViewCookieTarget.isSecureLearningPlatformUrl("https://chaoxing.com.evil.example/login"))
        assertFalse(WebViewCookieTarget.isSecureLearningPlatformUrl("https://evil.example/login"))
    }

    @Test
    fun convertsStoredHostNameToCookieManagerUrl() {
        assertEquals("https://mooc1.chaoxing.com/", WebViewCookieTarget.fromDomain("mooc1.chaoxing.com"))
    }

    @Test
    fun preservesExistingUrlAndTrimsTrailingSlash() {
        assertEquals("https://jwxt.ccsu.cn", WebViewCookieTarget.fromDomain("https://jwxt.ccsu.cn/"))
    }

    @Test
    fun extractsExactOriginWithoutPathOrQuery() {
        assertEquals(
            "https://portal.ccsu.cn",
            WebViewCookieTarget.originOf("https://portal.ccsu.cn/jwglxt/menu?ticket=private")
        )
    }

    @Test
    fun keepsSchemeAndNonDefaultPortInOriginIdentity() {
        assertEquals(
            "http://portal.example.edu.cn:8080",
            WebViewCookieTarget.originOf("http://portal.example.edu.cn:8080/login")
        )
        assertEquals(
            "https://portal.example.edu.cn",
            WebViewCookieTarget.originOf("https://portal.example.edu.cn:443/login")
        )
    }

    @Test
    fun identifiesLearningPlatformHostsWithoutMatchingLookalikes() {
        assertEquals(true, WebViewCookieTarget.isLearningPlatformHost("mooc1.chaoxing.com"))
        assertEquals(false, WebViewCookieTarget.isLearningPlatformHost("chaoxing.com.attacker.example"))
    }
}
