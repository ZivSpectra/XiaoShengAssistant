package com.zhizhou.xxtassistant

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import com.zhizhou.xxtassistant.data.AcademicYear
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CourseSchedule
import com.zhizhou.xxtassistant.data.CourseScheduleStore
import com.zhizhou.xxtassistant.data.CoursePeriod
import com.zhizhou.xxtassistant.data.CoursePeriodStore
import com.zhizhou.xxtassistant.data.CourseImportPageDetector
import com.zhizhou.xxtassistant.data.CourseImportLoadFailure
import com.zhizhou.xxtassistant.data.CourseImportTermDetector
import com.zhizhou.xxtassistant.data.CourseTableParser
import com.zhizhou.xxtassistant.remind.ReminderDispatch
import com.zhizhou.xxtassistant.util.WebViewCookieTarget
import com.zhizhou.xxtassistant.util.DebugLog
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * 通用教务系统导入页：用户自行打开学校教务系统并完成登录，
 * App 只读取当前页面的 HTML 表格，不记录密码，也不绕过验证码。
 */
@SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
class CourseWebImportActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TERM = "course_table_term"
        private const val PAGE_READ_RETRY_LIMIT = 20
        private const val PAGE_READ_RETRY_DELAY_MS = 500L
    }

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var readButton: Button
    private lateinit var accountId: String
    private lateinit var store: CourseScheduleStore
    private var clearing = false
    private var targetTerm: String = ""
    private var pageReady = false
    /** Prevent repeatedly bouncing between a portal shell and the same timetable iframe. */
    private var frameNavigationAttempted = false
    /** Changes only on a top-level navigation; DOM/AJAX updates keep it stable. */
    private var pageGeneration = 0L
    /** Exact top-level origins visited by this import WebView, including SSO redirects. */
    private val visitedAcademicOrigins = linkedSetOf<String>()
    private val visitedAcademicUrls = linkedSetOf<String>()
    private val pageReadHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountId = AccountManager(this).getActiveUid().orEmpty()
        if (accountId.isBlank()) {
            finish()
            return
        }
        targetTerm = intent.getStringExtra(EXTRA_TERM).orEmpty().ifBlank { currentTerm() }
        store = CourseScheduleStore(this)
        setContentView(buildContent())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishImportSession()
            }
        })
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        toolbar.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finishImportSession() }
        }, LinearLayout.LayoutParams(dp(72), dp(48)))
        toolbar.addView(TextView(this).apply {
            text = "教务系统导入"
            textSize = 19f
            setTextColor(Color.rgb(28, 27, 31))
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(toolbar)

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        urlInput = EditText(this).apply {
            hint = getString(R.string.course_import_url_hint)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(getPreferences(MODE_PRIVATE).getString("last_academic_url", "").orEmpty())
        }
        addressRow.addView(urlInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        addressRow.addView(Button(this).apply {
            text = "打开"
            setOnClickListener { loadAddress() }
        }, LinearLayout.LayoutParams(dp(76), dp(52)))
        root.addView(addressRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(2))
        }
        readButton = Button(this).apply {
            text = "读取当前页面"
            isEnabled = false
            setOnClickListener { readCurrentPage() }
        }
        actions.addView(readButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply {
            text = "退出导入"
            setOnClickListener { finishImportSession() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions)

        status = TextView(this).apply {
            text = getString(R.string.course_import_status_initial)
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        root.addView(status)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        webView = WebView(this)
        setupWebView()
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun setupWebView() {
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        // SSO portals and their menu pages occasionally use target=_blank or
        // window.open.  Keep navigation in this WebView instead of silently
        // sending the user to an unhandled secondary window.
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        // Some otherwise-HTTPS academic portals still load their timetable
        // grid or legacy JS from an HTTP subresource. Keep top-level navigation
        // HTTPS-only, but allow WebView's compatibility mode to load those
        // legacy subresources so the page does not remain an empty shell.
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                if (request?.isForMainFrame != true) return false
                val target = request.url?.toString().orEmpty()
                if (WebViewCookieTarget.isSafeAcademicTopLevelUrl(target)) return false
                Toast.makeText(this@CourseWebImportActivity, "教务导入仅允许 HTTPS 页面", Toast.LENGTH_SHORT).show()
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageGeneration++
                DebugLog.log(this@CourseWebImportActivity, "[COURSE_IMPORT] page started host=${safeHost(url)} generation=$pageGeneration")
                if (!WebViewCookieTarget.isSafeAcademicTopLevelUrl(url.orEmpty())) {
                    view?.stopLoading()
                    pageReady = false
                    readButton.isEnabled = false
                    progress.visibility = View.GONE
                    status.text = getString(R.string.course_import_https_blocked)
                    return
                }
                rememberAcademicHost(url)
                pageReady = false
                readButton.isEnabled = false
                progress.visibility = View.VISIBLE
                status.text = "页面加载中……请在教务页面完成登录，并导航到个人课表页面。"
            }

            @Suppress("SetTextI18n")
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!WebViewCookieTarget.isSafeAcademicTopLevelUrl(url.orEmpty())) {
                    pageReady = false
                    readButton.isEnabled = false
                    progress.visibility = View.GONE
                    status.text = getString(R.string.course_import_https_blocked)
                    return
                }
                rememberAcademicHost(url)
                pageReady = !url.isNullOrBlank() && !url.equals("about:blank", ignoreCase = true)
                DebugLog.log(this@CourseWebImportActivity, "[COURSE_IMPORT] page finished host=${safeHost(url)} ready=$pageReady generation=$pageGeneration")
                readButton.isEnabled = pageReady
                progress.visibility = View.GONE
                status.text = if (pageReady) {
                    "请确认当前页面已显示完整个人课表，再点击“读取当前页面”。"
                } else {
                    getString(R.string.course_import_status_initial)
                }
            }

            @Suppress("SetTextI18n")
            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                val code = error?.errorCode ?: -1
                val description = error?.description?.toString().orEmpty()
                val isMainFrame = request?.isForMainFrame == true
                val failure = CourseImportLoadFailure.from(code)
                DebugLog.log(
                    this@CourseWebImportActivity,
                    "[COURSE_IMPORT] load failed mainFrame=$isMainFrame code=$code kind=${failure.kind} host=${safeHost(request?.url?.toString())} detail=$description"
                )
                if (!isMainFrame) return
                pageReady = false
                readButton.isEnabled = false
                progress.visibility = View.GONE
                status.text = failure.userMessage
            }

            @Suppress("SetTextI18n")
            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                val code = errorResponse?.statusCode ?: -1
                val isMainFrame = request?.isForMainFrame == true
                if (isMainFrame || code >= 400) {
                    DebugLog.log(
                        this@CourseWebImportActivity,
                        "[COURSE_IMPORT] HTTP response mainFrame=$isMainFrame status=$code host=${safeHost(request?.url?.toString())}"
                    )
                }
                if (!isMainFrame) return
                pageReady = false
                readButton.isEnabled = false
                progress.visibility = View.GONE
                status.text = when (code) {
                    401, 403 -> "教务页面返回 HTTP $code，登录可能已失效或当前页面无访问权限。请重新登录后再读取。"
                    in 400..599 -> "教务页面返回 HTTP $code。请确认当前页面地址和登录状态。"
                    else -> "教务页面返回异常 HTTP 状态（$code）。"
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                // Never bypass certificate warnings.  Cancel and explain the
                // reason instead of leaving a blank WebView that looks like a
                // parser failure.
                handler?.cancel()
                DebugLog.log(
                    this@CourseWebImportActivity,
                    "[COURSE_IMPORT] SSL failure primary=${error?.primaryError ?: -1} host=${safeHost(webView.url)}"
                )
                pageReady = false
                readButton.isEnabled = false
                progress.visibility = View.GONE
                status.text = "教务页面证书校验失败，已停止加载。请使用官方 HTTPS 地址。"
            }
        }
        webView.loadUrl("about:blank")
    }

    private fun loadAddress() {
        val normalized = WebViewCookieTarget.normalizeAcademicUrl(urlInput.text.toString())
        if (normalized == null) {
            Toast.makeText(this, "请输入有效的 HTTPS 教务系统网址", Toast.LENGTH_SHORT).show()
            return
        }
        urlInput.setText(normalized)
        getPreferences(MODE_PRIVATE).edit().putString("last_academic_url", normalized).apply()
        frameNavigationAttempted = false
        pageReady = false
        readButton.isEnabled = false
        rememberAcademicHost(normalized)
        webView.loadUrl(normalized)
    }

    private fun readCurrentPage() {
        if (!pageReady || !readButton.isEnabled) {
            Toast.makeText(this, "页面还在加载，请稍候再读取", Toast.LENGTH_SHORT).show()
            return
        }
        readButton.isEnabled = false
        status.text = "正在读取当前页面中的课表……"
        DebugLog.log(this, "[COURSE_IMPORT] DOM read requested host=${safeHost(webView.url)} generation=$pageGeneration")
        readPageAttempt(
            attempt = 0,
            expectedUrl = webView.url.orEmpty(),
            expectedGeneration = pageGeneration
        )
    }

    private fun readPageAttempt(attempt: Int, expectedUrl: String, expectedGeneration: Long) {
        if (isFinishing || clearing || pageGeneration != expectedGeneration ||
            !sameAcademicDocument(expectedUrl, webView.url.orEmpty())
        ) {
            status.text = "页面已切换，请回到个人课表查询页面后重新读取。"
            readButton.isEnabled = pageReady
            return
        }
        // A WebView may finish on a login/menu page or render the actual table
        // inside a same-origin iframe. Validate the page boundary before passing
        // any HTML to the timetable parser.
        webView.evaluateJavascript("""
            (function() {
              var pages = [];
              var selectedTerms = [];
              var frameUrls = [];
              var crossOriginFrameCount = 0;
              var linkUrls = [];
              function collect(doc) {
                if (!doc || !doc.documentElement) return;
                pages.push(doc.documentElement.outerHTML);
                var selects = doc.querySelectorAll('select');
                for (var s = 0; s < selects.length; s++) {
                  var select = selects[s];
                  var option = select.options && select.options[select.selectedIndex];
                  if (option) {
                    var text = (option.textContent || option.value || '').trim();
                    if (text) selectedTerms.push(text);
                  }
                }
                var frames = doc.querySelectorAll('iframe,frame');
                for (var i = 0; i < frames.length; i++) {
                  try {
                    var frameSrc = frames[i].src || frames[i].getAttribute('src') || '';
                    if (frameSrc) {
                      frameUrls.push(frameSrc);
                      try {
                        if (new URL(frameSrc, doc.location.href).origin !== doc.location.origin) crossOriginFrameCount++;
                      } catch (ignored) {}
                    }
                    collect(frames[i].contentDocument);
                  } catch (ignored) {}
                }
                var links = doc.querySelectorAll('a[href]');
                for (var a = 0; a < links.length; a++) {
                  var href = links[a].href || '';
                  if (href) linkUrls.push(href);
                }
              }
              collect(document);
              return {
                html: pages.join('\\n<!--XS_FRAME_BOUNDARY-->\\n'),
                selectedTerms: selectedTerms,
                frameUrls: frameUrls,
                crossOriginFrameCount: crossOriginFrameCount,
                linkUrls: linkUrls
              };
            })()
        """.trimIndent()) { raw ->
            // The JavaScript callback can arrive after the user has followed a
            // link. Never parse a page from a previous navigation or show a
            // preview for a different top-level URL.
            if (isFinishing || clearing || pageGeneration != expectedGeneration ||
                !sameAcademicDocument(expectedUrl, webView.url.orEmpty())
            ) {
                status.text = "页面已切换，请回到个人课表查询页面后重新读取。"
                readButton.isEnabled = pageReady
                return@evaluateJavascript
            }
            val payload = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
            // A few WebView versions JSON-encode an object return value one
            // extra time. Decode that nested object as well, otherwise the
            // parser would receive the JSON wrapper instead of the page HTML.
            val objectPayload = when (payload) {
                is JSONObject -> payload
                is String -> runCatching { JSONTokener(payload).nextValue() as? JSONObject }.getOrNull()
                else -> null
            }
            val html: String
            val selectedTerms: List<String>
            val frameUrls: List<String>
            val crossOriginFrameCount: Int
            val linkUrls: List<String>
            when {
                objectPayload != null -> {
                    html = objectPayload.optString("html")
                    val values = objectPayload.optJSONArray("selectedTerms")
                    selectedTerms = buildList {
                        if (values != null) {
                            for (index in 0 until values.length()) {
                                values.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }
                    frameUrls = jsonStringArray(objectPayload.optJSONArray("frameUrls"))
                    crossOriginFrameCount = objectPayload.optInt("crossOriginFrameCount", 0)
                    linkUrls = jsonStringArray(objectPayload.optJSONArray("linkUrls"))
                }
                // Keep compatibility with WebView implementations that return
                // the previous plain-string result after a process restart.
                payload is String -> {
                    html = payload
                    selectedTerms = emptyList()
                    frameUrls = emptyList()
                    crossOriginFrameCount = 0
                    linkUrls = emptyList()
                }
                else -> {
                    html = ""
                    selectedTerms = emptyList()
                    frameUrls = emptyList()
                    crossOriginFrameCount = 0
                    linkUrls = emptyList()
                }
            }
            if (html.isBlank()) {
                DebugLog.log(this@CourseWebImportActivity, "[COURSE_IMPORT] DOM read empty generation=$expectedGeneration jsPayload=${payload?.javaClass?.simpleName ?: "null"}")
                retryOrFinishPageRead(
                    attempt,
                    expectedUrl,
                    expectedGeneration,
                    "没有读取到页面内容，请确认课表已经完整打开。"
                )
                return@evaluateJavascript
            }
            DebugLog.log(
                this@CourseWebImportActivity,
                "[COURSE_IMPORT] DOM read complete htmlLength=${html.length} selectedTerms=${selectedTerms.size} frames=${frameUrls.size} crossOriginFrames=$crossOriginFrameCount links=${linkUrls.size}"
            )
            val decision = CourseImportPageDetector.inspect(html, webView.url)
            DebugLog.log(
                this@CourseWebImportActivity,
                "[COURSE_IMPORT] page inspection allowed=${decision.allowed} retryable=${decision.retryable} reason=${decision.reason}"
            )
            if (!decision.allowed) {
                val candidate = (frameUrls + linkUrls)
                    .mapNotNull { normalizeTimetableCandidate(it, webView.url.orEmpty()) }
                    .firstOrNull()
                if (decision.retryable && candidate != null && !frameNavigationAttempted) {
                    frameNavigationAttempted = true
                    status.text = "已找到课表子页面，正在打开个人课表……"
                    webView.loadUrl(candidate)
                    return@evaluateJavascript
                }
                if (decision.retryable) {
                    retryOrFinishPageRead(
                        attempt,
                        expectedUrl,
                        expectedGeneration,
                        getString(R.string.course_import_read_timeout)
                    )
                } else {
                    status.text = getString(R.string.course_import_page_rejected, decision.reason)
                    readButton.isEnabled = pageReady
                }
                return@evaluateJavascript
            }
            val result = CourseTableParser.parseHtml(html)
            DebugLog.log(
                this@CourseWebImportActivity,
                "[COURSE_IMPORT] parser rows=${result.rows.size} errors=${result.errors.size} periods=${result.periodTimes.size}"
            )
            if (result.rows.isEmpty()) {
                status.text = if (crossOriginFrameCount > 0) {
                    "页面已打开，但检测到课表位于跨域框架中，浏览器安全限制不允许读取。请在教务系统内打开个人课表，或使用表格导入。"
                } else {
                    getString(R.string.course_import_no_courses)
                }
                readButton.isEnabled = pageReady
                return@evaluateJavascript
            }
            val pageTerm = CourseImportTermDetector.detect(html, selectedTerms)
            // Never label an unverified page with the caller's target term. The
            // preview remains non-importable until the live page identifies the
            // selected academic year and semester.
            val term = pageTerm.orEmpty()
            val termErrors = when {
                pageTerm == null -> listOf(
                    "无法从当前教务页面确认学年和学期；请先在个人课表页面选择明确学期后再读取，避免课程被归档到错误学期。"
                )
                pageTerm != targetTerm -> listOf(
                    "当前页面显示的是 $pageTerm，但你选择导入的是 $targetTerm；请先在教务系统切换到目标学期后再读取。"
                )
                else -> emptyList()
            }
            val schedules = CourseTableParser.toSchedules(
                accountId,
                result.rows,
                CourseSchedule.SOURCE_ACADEMIC_WEB,
                termOverride = term
            )
            showPreview(schedules, result.errors + termErrors, term, result.periodTimes)
            readButton.isEnabled = pageReady
        }
    }

    private fun retryOrFinishPageRead(
        attempt: Int,
        expectedUrl: String,
        expectedGeneration: Long,
        finalMessage: String
    ) {
        if (attempt < PAGE_READ_RETRY_LIMIT) {
            status.text = "课表正在加载，请稍候……"
            pageReadHandler.postDelayed(
                { readPageAttempt(attempt + 1, expectedUrl, expectedGeneration) },
                PAGE_READ_RETRY_DELAY_MS
            )
        } else {
            status.text = finalMessage
            readButton.isEnabled = pageReady
        }
    }

    private fun jsonStringArray(array: org.json.JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun safeHost(raw: String?): String = runCatching {
        val uri = java.net.URI(raw.orEmpty())
        uri.host?.takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")

    private fun normalizeTimetableCandidate(raw: String, currentUrl: String): String? {
        val candidate = runCatching { Uri.parse(raw).toString() }.getOrNull().orEmpty()
        val absolute = when {
            candidate.startsWith("https://", ignoreCase = true) -> candidate
            candidate.startsWith("//") -> "https:$candidate"
            candidate.startsWith("/") -> runCatching {
                // Keep the query string: Zhengfang routes commonly carry a
                // function code in the query, and dropping it opens only the
                // portal shell again.
                java.net.URI(currentUrl).resolve(candidate).toString()
            }.getOrNull()
            else -> null
        } ?: return null
        if (!WebViewCookieTarget.isHttpsUrl(absolute)) return null
        val path = Uri.parse(absolute).path.orEmpty().lowercase(Locale.ROOT)
        val looksLikeTimetable = listOf("kbcx", "xskbcx", "timetable", "course-table", "course_table").any(path::contains)
        return absolute.takeIf { looksLikeTimetable }
    }

    private fun showPreview(
        schedules: List<CourseSchedule>,
        errors: List<String>,
        term: String,
        periodTimes: Map<Int, Pair<Int, Int>>
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("教务课表预览")
            .setMessage(buildString {
                append("识别到 ${schedules.size} 节课程，将写入学期：${term.ifBlank { "未识别" }}\n\n")
                if (errors.isNotEmpty()) {
                    append("以下行需要检查：\n")
                    errors.take(8).forEach { append("• ").append(it).append('\n') }
                    if (errors.size > 8) append("其余错误已省略\n")
                } else {
                    append("未发现格式错误。")
                    if (periodTimes.isNotEmpty()) {
                        append("已从教务课表读取 ${periodTimes.size} 个节次的真实起止时间。")
                    }
                    append("确认后才会替换本学期相同来源的课程。")
                }
            })
            .setPositiveButton("确认导入") { _, _ ->
                if (schedules.isEmpty()) {
                    Toast.makeText(this, "没有可导入的课程，请回到课表页面后再读取", Toast.LENGTH_LONG).show()
                } else if (errors.isNotEmpty()) {
                    Toast.makeText(this, "解析存在错误，已保留原课表；请修正页面后再试", Toast.LENGTH_LONG).show()
                } else {
                    if (periodTimes.isNotEmpty()) {
                        val periodStore = CoursePeriodStore(this)
                        val imported = periodTimes.map { (period, times) ->
                            CoursePeriod(period, times.first, times.second)
                        }
                        // Real bell times from the current academic page win
                        // over the default schedule; untouched periods keep
                        // the user's existing settings as a fallback.
                        val merged = (imported + periodStore.list(accountId))
                            .distinctBy { it.period }
                            .sortedBy { it.period }
                        periodStore.save(accountId, merged)
                    }
                    store.replaceImported(accountId, term, schedules, CourseSchedule.SOURCE_ACADEMIC_WEB)
                    DebugLog.log(this, "[COURSE_IMPORT] import confirmed term=$term courses=${schedules.size}")
                    ReminderDispatch.enqueueCourses(
                        this,
                        accountId,
                        schedules = { store.list(accountId) }
                    ) { result ->
                        if (!isFinishing && !isDestroyed) {
                            val reminderText = result.fold(
                                onSuccess = { scheduled ->
                                    if (scheduled.failed == 0) "课程提醒已安排"
                                    else "课程提醒有 ${scheduled.failed} 项未登记，请检查系统提醒权限"
                                },
                                onFailure = { "课程已导入，但提醒安排失败，请返回课表页重试" }
                            )
                            Toast.makeText(this, reminderText, Toast.LENGTH_LONG).show()
                        }
                    }
                    Toast.makeText(this, "已导入 ${schedules.size} 节课程，正在安排提醒", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finishImportSession()
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = schedules.isNotEmpty() && errors.isEmpty()
        }
        dialog.show()
    }

    private fun finishImportSession() {
        if (clearing) return
        clearing = true
        clearAcademicCookies {
            runCatching {
                webView.clearHistory()
                webView.clearCache(true)
                webView.destroy()
                finish()
            }.onFailure {
                runCatching { webView.destroy() }
                finish()
            }
        }
    }

    override fun onDestroy() {
        pageReadHandler.removeCallbacksAndMessages(null)
        if (!clearing && !isChangingConfigurations) {
            clearing = true
            runCatching { clearAcademicCookies {} }
        }
        if (::webView.isInitialized) runCatching { webView.destroy() }
        super.onDestroy()
    }

    private fun currentTerm(): String {
        return AcademicYear.currentTerm()
    }

    private fun rememberAcademicHost(url: String?) {
        if (!WebViewCookieTarget.isHttpsUrl(url.orEmpty())) return
        val host = runCatching { Uri.parse(url.orEmpty()).host?.lowercase(Locale.ROOT) }.getOrNull()
        if (host.isNullOrBlank() || host == "localhost" || host == "127.0.0.1") return
        if (WebViewCookieTarget.isLearningPlatformHost(host)) return
        val origin = WebViewCookieTarget.originOf(url.orEmpty()) ?: return
        visitedAcademicOrigins += origin
        val safeUrl = runCatching {
            Uri.parse(url.orEmpty()).buildUpon().clearQuery().fragment(null).build().toString()
        }.getOrNull() ?: return
        visitedAcademicUrls += safeUrl
    }

    /**
     * CookieManager has no per-WebView jar. Clear only cookie names belonging to
     * hosts visited by this import flow; never call removeAllCookies(), which
     * would log the user out of the learning platform WebViews.
     */
    private fun clearAcademicCookies(done: () -> Unit) {
        val manager = android.webkit.CookieManager.getInstance()
        fun finishAfterStorageCleanup() {
            // DOM storage is separate from cookies. Remove only exact origins
            // visited by this import, including a cross-domain SSO redirect.
            val originsToClear = visitedAcademicOrigins.toSet()
            if (originsToClear.isEmpty()) {
                done()
                return
            }
            runCatching {
                WebStorage.getInstance().getOrigins { origins ->
                    origins.keys.mapNotNull { it as? String }
                        .filter { origin -> WebViewCookieTarget.originOf(origin) in originsToClear }
                        .forEach { origin -> WebStorage.getInstance().deleteOrigin(origin) }
                    runOnUiThread(done)
                }
            }.onFailure {
                // Some old WebView implementations do not expose origin
                // enumeration; cookie cleanup still completed, so finish.
                runOnUiThread(done)
            }
        }
        val writes = visitedAcademicUrls.flatMap { pageUrl ->
            val uri = Uri.parse(pageUrl)
            val host = uri.host.orEmpty()
            val paths = cookiePathPrefixes(uri.path.orEmpty())
            val cookieNames = manager.getCookie(pageUrl).orEmpty().split(';')
                .map { it.trim() }
                .mapNotNull { item -> item.substringBefore('=').trim().takeIf { it.isNotBlank() } }
                .distinct()
            cookieNames.flatMap { name ->
                paths.flatMap { path ->
                    // CookieManager hides each cookie's Domain and Path. Expire
                    // observed names at this exact visited host and its paths;
                    // do not guess a parent from the final two DNS labels.
                    val value = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path"
                    listOf(pageUrl to value) +
                        listOf(pageUrl to "$value; Domain=$host")
                }
            }
        }
        if (writes.isEmpty()) {
            finishAfterStorageCleanup()
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(writes.size)
        writes.forEach { (domain, value) ->
            manager.setCookie(domain, value) {
                if (remaining.decrementAndGet() == 0) {
                    manager.flush()
                    finishAfterStorageCleanup()
                }
            }
        }
    }

    private fun cookiePathPrefixes(path: String): List<String> {
        val segments = path.split('/').filter { it.isNotBlank() }
        return buildList {
            add("/")
            for (index in segments.indices) add("/${segments.take(index + 1).joinToString("/")}")
        }.distinct()
    }

    /**
     * A term selector may update the query string without a new document. Treat
     * that as the same page, but reject a callback for another host/path.
     */
    private fun sameAcademicDocument(expected: String, current: String): Boolean {
        if (!WebViewCookieTarget.isHttpsUrl(expected) || !WebViewCookieTarget.isHttpsUrl(current)) {
            return expected.equals(current, ignoreCase = true)
        }
        val expectedUri = runCatching { Uri.parse(expected) }.getOrNull() ?: return false
        val currentUri = runCatching { Uri.parse(current) }.getOrNull() ?: return false
        return expectedUri.scheme.equals(currentUri.scheme, ignoreCase = true) &&
            expectedUri.host.equals(currentUri.host, ignoreCase = true) &&
            expectedUri.port == currentUri.port &&
            expectedUri.path.orEmpty() == currentUri.path.orEmpty()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
