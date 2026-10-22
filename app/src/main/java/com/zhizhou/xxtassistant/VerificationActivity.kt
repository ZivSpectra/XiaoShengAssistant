package com.zhizhou.xxtassistant

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.XueXiTongApi
import com.zhizhou.xxtassistant.util.WebViewCookieTarget
import java.util.concurrent.atomic.AtomicInteger

/**
 * 学习通风控验证页。
 *
 * 用户在这里亲自完成验证码，点击“完成验证”后才把 WebView Cookie 同步回当前账号。
 * 不自动抓取、不自动填写验证码，也不尝试绕过平台风控。
 */
@SuppressLint("SetJavaScriptEnabled")
class VerificationActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var accountId: String = ""
    private var syncing = false

    private val cookieDomains = listOf(
        "https://passport2.chaoxing.com",
        "https://passport.chaoxing.com",
        "https://i.chaoxing.com",
        "https://mooc1.chaoxing.com",
        "https://mooc2.chaoxing.com",
        "https://mooc2-ans.chaoxing.com",
        // API 最后，保留验证后课程接口域最新的会话 Cookie。
        "https://mooc1-api.chaoxing.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verification)
        title = "学习通验证"

        accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
        val accounts = AccountManager(this)
        val accountCookie = accounts.getCookie(accountId).orEmpty()
        val accountCookiesByDomain = accounts.getCookiesByDomain(accountId)
        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
            .takeIf { WebViewCookieTarget.isSecureLearningPlatformUrl(it) }
            ?: "https://mooc1.chaoxing.com/mooc-ans/mycourse"

        webView = findViewById(R.id.webview_verification)
        progress = findViewById(R.id.progress_verification)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                return !WebViewCookieTarget.isSecureLearningPlatformUrl(request.url.toString())
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
            }
        }

        findViewById<Button>(R.id.btn_verification_done).setOnClickListener {
            if (!syncing) syncCookies(accountCookie)
        }

        restoreCookies(accountCookie, accountCookiesByDomain) { webView.loadUrl(targetUrl) }
    }

    private fun restoreCookies(cookie: String, byDomain: Map<String, String>, after: () -> Unit) {
        val manager = CookieManager.getInstance()
        // CookieManager is process-wide. Do not clear it here: verification is
        // an in-place refresh for the active account, and removeAllCookies()
        // can erase a different WebView's valid session mid-request.
        val domainCookies = if (byDomain.isNotEmpty()) byDomain else
            cookieDomains.associateWith { cookie }
        val writes = domainCookies.flatMap { (domain, raw) ->
            // Stored domain keys are host names, but CookieManager.setCookie
            // only accepts an absolute URL. Without this conversion the
            // verification page appeared to sync while the session was not
            // restored on the next open.
            val target = WebViewCookieTarget.fromDomain(domain)
            raw.split(';').map { it.trim() }.filter { it.contains('=') }.map { target to it }
        }
        val writesRemaining = AtomicInteger(writes.size)
        if (writesRemaining.get() == 0) {
            manager.flush()
            runOnUiThread(after)
            return
        }
        writes.forEach { (domain, pair) ->
            manager.setCookie(domain, pair) {
                if (writesRemaining.decrementAndGet() == 0) {
                    manager.flush()
                    runOnUiThread { after() }
                }
            }
        }
    }

    private fun syncCookies(previous: String) {
        syncing = true
        val button = findViewById<Button>(R.id.btn_verification_done)
        button.isEnabled = false
        button.text = "正在同步并确认…"
        val manager = CookieManager.getInstance()
        manager.flush()
        val currentByDomain = buildMap {
            cookieDomains.forEach { domain ->
                manager.getCookie(domain)?.takeIf { it.isNotBlank() }?.let { put(domain, it) }
            }
        }
        val accounts = AccountManager(this)
        val current = currentByDomain.values.joinToString("; ")
        val saved = if (accountId.isBlank()) false else if (currentByDomain.isNotEmpty()) {
            accounts.updateCookiesByDomain(accountId, currentByDomain)
        } else {
            accounts.updateCookie(accountId, mergeCookieText(previous, current))
        }
        if (!saved) {
            syncing = false
            button.isEnabled = true
            button.text = getString(R.string.verification_done)
            Toast.makeText(this, "验证 Cookie 尚未同步成功，请保持页面验证完成后再试", Toast.LENGTH_LONG).show()
            return
        }
        val savedCookie = accounts.getCookie(accountId).orEmpty()
        Thread {
            val result = XueXiTongApi(this).probeSession(savedCookie, accountId)
            runOnUiThread {
                syncing = false
                when (result) {
                    XueXiTongApi.SessionProbeResult.AUTHENTICATED -> {
                        setResult(RESULT_OK, intent.putExtra(EXTRA_ACCOUNT_ID, accountId))
                        Toast.makeText(this, "学习通已确认登录状态，请返回后手动点击“重新检查”。", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    XueXiTongApi.SessionProbeResult.NEEDS_VERIFICATION -> {
                        button.isEnabled = true
                        button.text = "验证后再次确认"
                        Toast.makeText(this, "学习通仍要求完成验证，请先确认网页验证成功，再点此按钮。", Toast.LENGTH_LONG).show()
                    }
                    XueXiTongApi.SessionProbeResult.SESSION_EXPIRED -> {
                        button.isEnabled = true
                        button.text = getString(R.string.verification_done)
                        Toast.makeText(this, "当前会话仍未登录，请在页面完成登录或重新验证。", Toast.LENGTH_LONG).show()
                    }
                    XueXiTongApi.SessionProbeResult.EMPTY_OR_UNKNOWN -> {
                        button.isEnabled = true
                        button.text = getString(R.string.verification_done)
                        Toast.makeText(this, "暂时无法确认学习通已接受验证，请稍后重试。", Toast.LENGTH_LONG).show()
                    }
                    XueXiTongApi.SessionProbeResult.NETWORK_ERROR -> {
                        button.isEnabled = true
                        button.text = getString(R.string.verification_done)
                        Toast.makeText(this, "网络探测失败，Cookie 已同步；请检查网络后重试确认。", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun mergeCookieText(old: String, fresh: String): String {
        val values = LinkedHashMap<String, String>()
        (old + ";" + fresh).split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
            val index = it.indexOf('=')
            values[it.substring(0, index).trim()] = it.substring(index + 1).trim()
        }
        return values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_URL = "verification_url"
    }
}
