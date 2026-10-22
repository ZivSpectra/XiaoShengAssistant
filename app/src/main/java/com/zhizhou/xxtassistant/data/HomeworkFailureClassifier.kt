package com.zhizhou.xxtassistant.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONException

/** Classifies fetch failures without copying exception messages that may contain session URLs. */
object HomeworkFailureClassifier {
    fun classify(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList().asReversed()
        return causes.firstNotNullOfOrNull(::classifyOne) ?: "parse_or_logic"
    }

    private fun classifyOne(error: Throwable): String? = when (error) {
        is XueXiTongApi.AntiSpiderException -> "verification_required"
        is XueXiTongApi.SessionExpiredException -> "session_expired"
        is UnknownHostException -> "dns"
        is SocketTimeoutException -> "timeout"
        is ConnectException -> "connection"
        is SSLException -> "tls"
        is JSONException -> "response_parse"
        is InterruptedException -> "cancelled"
        is IOException -> "io"
        else -> if (error.javaClass.simpleName == "HttpStatusException") "http_status" else null
    }
}
