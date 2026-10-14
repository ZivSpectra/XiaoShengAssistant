package com.zhizhou.xxtassistant

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.util.DebugLog

/**
 * 登录页 —— 单账号登录；退出后可更换账号。
 *
 * 原理：WebView 加载学习通官方登录页，你正常登录，
 * 登录成功后 App 把 Cookie 保存到【本机私有存储】（绝不外传），
 * 账号本地作业记录按 uid 隔离。退出后可以登录另一个账号。
 *
 * 安全红线：Cookie 只存本地；日志不打印任何账号信息；只读数据不提交作业。
 */
@SuppressLint("SetJavaScriptEnabled")
class LoginActivity : AppCompatActivity() {

    companion object {
        // 学习通官方登录页（2026-10 起 passport 旧域名返回 404，已切换 passport2 新域名）
        private const val LOGIN_URL =
            "https://passport2.chaoxing.com/mlogin?fid=&newversion=true&refer=https%3A%2F%2Fi.chaoxing.com"

        // 登录成功后依次访问主站、课程站和课程 API，把各域会话都种上。
        // API 放在最后，避免其专用 route/JSESSIONID 被其它域的同名 Cookie 覆盖。
        private const val SEED_URL_MAIN = "https://i.chaoxing.com"
        private const val SEED_URL_COURSE = "https://mooc1.chaoxing.com/mooc-ans/mycourse"
        private const val SEED_URL_API = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata"

        // 需要捕获 Cookie 的学习通域名（作业接口在 mooc1/mooc2 上）
        private val COOKIE_DOMAINS = listOf(
            "https://passport2.chaoxing.com",
            "https://passport.chaoxing.com",
            "https://i.chaoxing.com",
            "https://mooc1.chaoxing.com",
            "https://mooc2.chaoxing.com",
            "https://mooc2-ans.chaoxing.com",
            "https://mooc1-api.chaoxing.com"
        )

        private const val CHECK_INTERVAL_MS = 2000L
        private const val NAV_TIMEOUT_MS = 20000L
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvAccountHint: TextView
    private lateinit var accountManager: AccountManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loginReady = false
    // 登录捕获状态机：
    //   0 = 等待登录
    //   1 = 已登录，正在/等待访问主站种 Cookie
    //   2 = 课程站已加载，正在/等待访问课程 API 种 Cookie
    //   3 = API 已加载，收集各域 Cookie 并保存
    private var capturePhase = 0
    private var lastNavTime = 0L

    private val checkLoginRunnable = object : Runnable {
        override fun run() {
            if (!checkAndHandleLogin(null)) {
                mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        accountManager = AccountManager(this)
        // 只有当前仍有有效会话时才直接进入主页；历史本地数据不等于已登录。
        if (accountManager.hasActiveSession()) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_login)

        webView = findViewById(R.id.webview_login)
        progressBar = findViewById(R.id.progress_loading)
        tvAccountHint = findViewById(R.id.tv_account_hint)
        tvAccountHint.text = "请登录学习通账号，登录后将恢复该账号的本地作业记录"
        tvAccountHint.visibility = View.VISIBLE

        setupWebView()
        loginReady = true
        webView.loadUrl(LOGIN_URL)
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                checkAndHandleLogin(url)
            }
        }
    }

    /**
     * 登录状态机：
     *  - 检测到已登录（Cookie 里有 _uid）→ 依次访问主站、课程站，把各域 Cookie 种全
     *  - 最后收集全部域 Cookie 保存账号（这样抓取作业时才拿得到课程列表）
     * @param pageUrl 有值 = 刚加载完的页面；null = 定时轮询
     */
    private fun checkAndHandleLogin(pageUrl: String?): Boolean {
        if (!loginReady) return false
        val now = System.currentTimeMillis()
        when (capturePhase) {
            0 -> {
                val cookie = collectCookie()
                if (hasUid(cookie)) {
                    capturePhase = 1
                    lastNavTime = now
                    webView.loadUrl(SEED_URL_MAIN)
                }
                return false
            }
            1 -> {
                // 主站加载完成（onPageFinished 触发）→ 去课程站种 mooc1 Cookie
                if (pageUrl != null) {
                    capturePhase = 2
                    lastNavTime = now
                    webView.loadUrl(SEED_URL_COURSE)
                } else if (lastNavTime > 0 && now - lastNavTime > NAV_TIMEOUT_MS) {
                    // 页面一直没加载完（网络卡）→ 重试
                    lastNavTime = now
                    webView.loadUrl(SEED_URL_MAIN)
                }
                return false
            }
            2 -> {
                // 课程站加载完成后再访问 API，确保 API 域 route/JSESSIONID 已建立。
                if (pageUrl != null) {
                    capturePhase = 3
                    lastNavTime = now
                    webView.loadUrl(SEED_URL_API)
                } else if (lastNavTime > 0 && now - lastNavTime > NAV_TIMEOUT_MS) {
                    lastNavTime = now
                    webView.loadUrl(SEED_URL_COURSE)
                }
                return false
            }
            3 -> {
                // API 加载完成 → 收集所有域 Cookie 并保存账号
                if (pageUrl != null) {
                    capturePhase = 4
                    val cookie = collectCookie()
                    // 安全红线：只记 Cookie 字段名（不记值），排查作业接口 400 是否因缺字段
                    val keys = cookie.split(";").map { it.trim().substringBefore('=').trim() }
                        .filter { it.isNotEmpty() }.distinct().sorted()
                    DebugLog.log(this, "登录Cookie字段(${keys.size}): ${keys.joinToString(",")}")
                    val uid = accountManager.addAccount(cookie)
                    if (uid != null) {
                        mainHandler.removeCallbacks(checkLoginRunnable)
                        goToMain(uid)
                        return true
                    }
                }
                return false
            }
            else -> return false
        }
    }

    /** 是否已登录成功：Cookie 里含有效的 _uid */
    private fun hasUid(cookie: String): Boolean {
        val m = Regex("""_uid=([^;\s]*)""").find(cookie) ?: return false
        val v = m.groupValues[1]
        return v.isNotEmpty() && v != "0"
    }

    private fun collectCookie(): String {
        val sb = StringBuilder()
        for (domain in COOKIE_DOMAINS) {
            CookieManager.getInstance().getCookie(domain)?.let {
                sb.append(it).append("; ")
            }
        }
        return sb.toString()
    }

    private fun goToMain(selectedUid: String? = null) {
        if (::progressBar.isInitialized) progressBar.visibility = View.GONE
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            selectedUid?.let { putExtra("selectedUid", it) }
        })
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized) return
        webView.onResume()
        mainHandler.post(checkLoginRunnable)
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
        mainHandler.removeCallbacks(checkLoginRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(checkLoginRunnable)
    }
}
