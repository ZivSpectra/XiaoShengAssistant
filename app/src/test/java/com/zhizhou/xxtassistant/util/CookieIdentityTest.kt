package com.zhizhou.xxtassistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CookieIdentityTest {
    @Test
    fun acceptsOneUidAcrossDomainCookieJars() {
        assertEquals("123", CookieIdentity.singleUid(listOf("_uid=123; route=a", "route=b; _uid=123")))
    }

    @Test
    fun rejectsMixedMissingAndZeroUidValues() {
        assertNull(CookieIdentity.singleUid(listOf("_uid=123", "_uid=456")))
        assertNull(CookieIdentity.singleUid(listOf("_uid=123; _uid=456")))
        assertNull(CookieIdentity.singleUid(listOf("_uid=0; _uid=123")))
        assertNull(CookieIdentity.singleUid(listOf("route=a")))
        assertNull(CookieIdentity.singleUid(listOf("_uid=0")))
    }

    @Test
    fun matchesOnlyOneUidAndRejectsMixedUpdateInput() {
        assertEquals(true, CookieIdentity.matchesUid(listOf("_uid=123; route=new"), "123"))
        assertEquals(false, CookieIdentity.matchesUid(listOf("_uid=123; _uid=456"), "123"))
        assertEquals(false, CookieIdentity.matchesUid(listOf("_uid=0; _uid=123"), "123"))
        assertEquals(false, CookieIdentity.matchesUid(listOf("route=new"), "123"))
    }

    @Test
    fun allowsUidlessHostCookiesButRejectsForeignOrInvalidIdentity() {
        assertEquals(true, CookieIdentity.belongsToUidOrHasNoUid(listOf("route=course"), "123"))
        assertEquals(true, CookieIdentity.belongsToUidOrHasNoUid(listOf("_uid=123; route=course"), "123"))
        assertEquals(false, CookieIdentity.belongsToUidOrHasNoUid(listOf("_uid=456"), "123"))
        assertEquals(false, CookieIdentity.belongsToUidOrHasNoUid(listOf("_uid=123; _uid=456"), "123"))
        assertEquals(false, CookieIdentity.belongsToUidOrHasNoUid(listOf("_uid=0"), "123"))
    }

    @Test
    fun onlyAcceptsExactAllowedHttpsHosts() {
        assertEquals("mooc1.chaoxing.com", CookieIdentity.normalizeAllowedHost("https://mooc1.chaoxing.com/path"))
        assertEquals("mooc1.chaoxing.com", CookieIdentity.normalizeAllowedHost("mooc1.chaoxing.com"))
        assertNull(CookieIdentity.normalizeAllowedHost("http://mooc1.chaoxing.com"))
        assertNull(CookieIdentity.normalizeAllowedHost("https://evil.example/mooc1.chaoxing.com"))
        assertNull(CookieIdentity.normalizeAllowedHost("https://user:pass@mooc1.chaoxing.com"))
        assertNull(CookieIdentity.normalizeAllowedHost("https://sub.mooc1.chaoxing.com"))
    }

    @Test
    fun cookieLookupDoesNotBroadenToAnotherHost() {
        val jars = mapOf(
            "mooc1.chaoxing.com" to "_uid=123; route=course",
            "i.chaoxing.com" to "_uid=123; route=home"
        )
        assertEquals("_uid=123; route=course", CookieIdentity.cookieForExactHost("mooc1.chaoxing.com", jars))
        assertEquals(null, CookieIdentity.cookieForExactHost("mooc2.chaoxing.com", jars))
        assertEquals(null, CookieIdentity.cookieForExactHost("evil.example", jars))
    }
}
