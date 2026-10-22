package com.zhizhou.xxtassistant.data

import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeworkFailureClassifierTest {
    @Test
    fun distinguishesNetworkFailuresFromParsingAndUnexpectedFailures() {
        assertEquals("dns", HomeworkFailureClassifier.classify(UnknownHostException()))
        assertEquals("connection", HomeworkFailureClassifier.classify(ConnectException()))
        assertEquals("timeout", HomeworkFailureClassifier.classify(SocketTimeoutException()))
        assertEquals("parse_or_logic", HomeworkFailureClassifier.classify(IllegalStateException()))
    }

    @Test
    fun identifiesVerificationAndExpiredSessionSeparately() {
        assertEquals(
            "verification_required",
            HomeworkFailureClassifier.classify(XueXiTongApi.AntiSpiderException("verification required"))
        )
        assertEquals(
            "session_expired",
            HomeworkFailureClassifier.classify(XueXiTongApi.SessionExpiredException("session expired"))
        )
    }

    @Test
    fun classifiesWrappedNetworkCausesInsteadOfMislabelingThemAsLogicErrors() {
        assertEquals(
            "dns",
            HomeworkFailureClassifier.classify(IOException("request failed", UnknownHostException("secret host")))
        )
    }
}
