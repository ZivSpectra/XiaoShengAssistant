package com.zhizhou.xxtassistant

import android.app.DatePickerDialog
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhizhou.xxtassistant.data.AccountManager
import com.zhizhou.xxtassistant.data.CourseStore
import com.zhizhou.xxtassistant.data.DeadlineOverrideStore
import com.zhizhou.xxtassistant.data.HiddenStore
import com.zhizhou.xxtassistant.data.Homework
import com.zhizhou.xxtassistant.data.HomeworkCache
import com.zhizhou.xxtassistant.data.HomeworkCachePolicy
import com.zhizhou.xxtassistant.data.ManualHomeworkStore
import com.zhizhou.xxtassistant.data.ManualDeadlinePromptStore
import com.zhizhou.xxtassistant.data.LocalCompletionStore
import com.zhizhou.xxtassistant.data.PinnedStore
import com.zhizhou.xxtassistant.data.ViewSettings
import com.zhizhou.xxtassistant.data.XueXiTongApi
import com.zhizhou.xxtassistant.data.UnknownDeadlinePromptStore
import com.zhizhou.xxtassistant.remind.ReminderScheduler
import com.zhizhou.xxtassistant.remind.ReminderNotifier
import com.zhizhou.xxtassistant.util.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 主页：当前账号作业列表（按截止时间从近到远排序）。
 *
 * 功能：
 *  - 顶部显示当前账号（换账号从设置退出登录后重新登录）
 *  - 刷新按钮：重新抓取当前账号的作业
 *  - 提醒设置：选择多个提前提醒节点（如截止前 1 天 / 3 小时 / 30 分钟）
 *  - 作业列表：未完成排前面（红），已完成排后面（绿）；点击跳转到学习通对应作业页
 */
@SuppressLint("SetTextI18n", "DiscouragedApi")
class MainActivity : AppCompatActivity() {

    private lateinit var accountManager: AccountManager
    private lateinit var api: XueXiTongApi
    private lateinit var adapter: HomeworkAdapter
    private lateinit var viewSettings: ViewSettings
    private lateinit var pinnedStore: PinnedStore
    private lateinit var hiddenStore: HiddenStore
    private lateinit var cache: HomeworkCache
    private lateinit var courseStore: CourseStore
    private lateinit var deadlineOverrides: DeadlineOverrideStore
    private lateinit var unknownDeadlinePrompts: UnknownDeadlinePromptStore
    private lateinit var localCompletionStore: LocalCompletionStore
    private lateinit var manualDeadlinePrompts: ManualDeadlinePromptStore

    private val handler = Handler(Looper.getMainLooper())
    private var uidList: List<String> = emptyList()
    private var currentUid: String? = null
    private var currentHomework: List<Homework> = emptyList()
    private var accountsInitialized = false
    private val loadGeneration = AtomicInteger(0)
    private var loadingUid: String? = null
    private var activeLoadThread: Thread? = null
    private val enteredUids = mutableSetOf<String>()
    private val deadlinePromptQueue = ArrayDeque<DeadlineOverrideStore.Prompt>()
    private var deadlinePromptShowing = false
    private var deadlinePromptDialog: AlertDialog? = null
    private var unknownPromptShowing = false
    private var unknownDeadlineDialog: AlertDialog? = null
    private var permissionOnboardingDialog: AlertDialog? = null

    companion object {
        /** 仅用于记录缓存新鲜度；缓存存在时不会因为 onResume 自动请求。 */
        private const val CACHE_MAX_AGE_MS = 15L * 60L * 1000L
        /** 网络失败后的自动重试冷却；仅无缓存首次进入使用。 */
        private const val AUTO_FETCH_RETRY_COOLDOWN_MS = 5L * 60L * 1000L
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        fun formatTime(ts: Long): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(ts)

        fun formatDate(ts: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(ts)

        fun formatDateTime(ts: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(ts)
    }

    private val verificationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // 验证页只负责同步会话。不要在这里立刻深度抓取，否则刚完成
                // 验证就再次连续请求，容易重新触发风控；用户确认后手动点一次检查。
                currentUid?.let { uid ->
                    api.resetTransientSession(uid)
                    Toast.makeText(this, "验证结果已同步，请点击“重新检查”核对作业。", Toast.LENGTH_LONG).show()
                    reloadVisibleHomework(uid)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        accountManager = AccountManager(this)
        api = XueXiTongApi(this)
        viewSettings = ViewSettings(this)
        pinnedStore = PinnedStore(this)
        hiddenStore = HiddenStore(this)
        cache = HomeworkCache(this)
        courseStore = CourseStore(this)
        deadlineOverrides = DeadlineOverrideStore(this)
        unknownDeadlinePrompts = UnknownDeadlinePromptStore(this)
        localCompletionStore = LocalCompletionStore(this)
        manualDeadlinePrompts = ManualDeadlinePromptStore(this)

        // 一个账号都没有 → 去登录页
        if (!accountManager.hasActiveSession()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 先创建通知频道，首次进入时再由逐项引导流程申请权限；
        // 不在这里一次性弹出多个系统页面，避免用户不知道该开哪一项。
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        if (notificationManager != null) {
            com.zhizhou.xxtassistant.remind.ReminderNotifier.createChannel(notificationManager)
        }

        // 从登录页带回来的目标账号（onNewIntent 负责后续切换，这里负责首次创建进入）
        intent.getStringExtra("selectedUid")?.let { currentUid = it }

        setupSpinner()
        setupList()
        setupButtons()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::adapter.isInitialized && adapter.isSelectionMode()) {
                    adapter.clearSelection()
                } else {
                    finish()
                }
            }
        })
        updateFilterButton()
    }

    override fun onResume() {
        super.onResume()
        // 返回详情页/验证页绝不触发网络请求；重新检查必须由用户点击。
        reloadAccounts(allowAutoFetch = false)
        // 从系统设置返回时，精确闹钟权限可能刚刚被授予；重新登记一次，
        // 避免权限恢复前创建的回退任务继续生效。
        if (currentUid != null && ReminderScheduler.isExactMode(this) &&
            ReminderScheduler.canScheduleExact(this)) {
            rescheduleCurrentAccount()
        }
        handler.post { maybeGuideReminderPermissions() }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("selectedUid")?.let {
            if (it != currentUid) resetAccountUiState()
            currentUid = it
            accountsInitialized = false
        }
    }

    override fun onDestroy() {
        cancelActiveLoad()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- 账号下拉框 ----------

    private fun setupSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_account)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (uidList.isNotEmpty() && uidList[position] != currentUid) {
                    currentUid = uidList[position]
                    resetAccountUiState()
                    loadHomework(currentUid!!)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun reloadAccounts(allowAutoFetch: Boolean = true) {
        val nextUids = accountManager.getActiveUid()?.let { listOf(it) } ?: emptyList()
        val changed = uidList != nextUids
        val previousUid = currentUid
        uidList = nextUids
        val spinner = findViewById<Spinner>(R.id.spinner_account)
        if (uidList.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        val labels = uidList.map { "账号…${it.takeLast(4)}" }
        if (changed || !accountsInitialized) {
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        spinner.isEnabled = false
        // 副标题：显示当前账号
        val current = currentUid?.takeIf { it in uidList } ?: uidList.firstOrNull()
        if (current != previousUid) resetAccountUiState()
        currentUid = current
        if (current != null) {
            findViewById<TextView>(R.id.tv_main_subtitle).text = "当前账号 …${current.takeLast(4)}"
        }
        // 保持当前选中
        val idx = uidList.indexOf(current)
        if (idx >= 0 && (changed || !accountsInitialized)) {
            spinner.setSelection(idx)
            current?.let { loadHomework(it) }
        } else if (allowAutoFetch && current != null && cache.load(current).isEmpty()) {
            // 只有没有缓存的首次进入允许自动抓取；缓存过期只等待用户手动重新检查。
            loadHomework(current)
        }
        accountsInitialized = true
    }

    // ---------- 作业列表 ----------

    private fun setupList() {
        val recycler = findViewById<RecyclerView>(R.id.recycler_homework)
        applyLayoutManager(recycler)   // 按设置选单列/双列
        adapter = HomeworkAdapter(
            onClick = { homework ->
                if (homework.isManual()) {
                    // 手动作业没有学习通链接
                    Toast.makeText(this, "手动添加的作业，请在截止前完成", Toast.LENGTH_SHORT).show()
                } else {
                    // 自动作业 → 打开学习通对应作业页（亲手完成，绝不自动提交）
                    startActivity(Intent(this, HomeworkDetailActivity::class.java).apply {
                        putExtra("url", homework.url)
                        putExtra("title", homework.title)
                        putExtra("accountId", homework.accountId)
                    })
                }
            },
            onPin = { homework -> togglePin(homework) },
            onToggleCompleted = { homework -> toggleLocalCompleted(homework) },
            onEditDeadline = { homework -> showDeadlineOverrideDialog(homework) },
            onHideCourse = { _, homeworks -> confirmHideCourse(homeworks) },
            onDelete = { homework ->
                if (homework.isManual()) confirmHideManual(homework)
                else confirmHideAuto(homework)
            },
            onSelectionChanged = { count ->
                updateBatchActionBar(count)
            },
            settings = viewSettings,
            pinned = pinnedStore,
            accountId = { currentUid }
        )
        recycler.adapter = adapter
        findViewById<Button>(R.id.btn_batch_complete).setOnClickListener { completeSelectedHomework() }
        findViewById<Button>(R.id.btn_batch_hide_bottom).setOnClickListener {
            confirmHideSelected(adapter.selectedItems())
        }
        findViewById<Button>(R.id.btn_batch_pin).setOnClickListener { pinSelectedHomework() }
        findViewById<Button>(R.id.btn_batch_cancel).setOnClickListener { adapter.clearSelection() }
    }

    private fun updateBatchActionBar(count: Int) {
        val bar = findViewById<View>(R.id.batch_action_bar) ?: return
        bar.visibility = if (count > 0) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tv_batch_selected)?.text = getString(R.string.batch_selected_count, count)
    }

    private fun completeSelectedHomework() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        selected.forEach { homework ->
            if (homework.isManual()) {
                ManualHomeworkStore(this).setCompleted(homework.accountId, homework.id, true)
            } else {
                localCompletionStore.setCompleted(homework.accountId, homework, true)
            }
        }
        adapter.clearSelection()
        currentUid?.let { reloadVisibleHomework(it) }
        Toast.makeText(this, "已将所选作业标记为完成", Toast.LENGTH_SHORT).show()
    }

    private fun pinSelectedHomework() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        selected.forEach { homework ->
            if (!pinnedStore.isPinned(homework.accountId, homework.id, homework.courseKey)) {
                pinnedStore.toggle(homework.accountId, homework.id, homework.courseKey)
            }
        }
        adapter.clearSelection()
        adapter.submit(currentHomework)
        Toast.makeText(this, "已将所选作业置顶", Toast.LENGTH_SHORT).show()
    }

    /** 按设置切换列表布局：单列 LinearLayout / 双列 GridLayoutManager(2) */
    private fun applyLayoutManager(recycler: RecyclerView) {
        val useDouble = viewSettings.mode == ViewSettings.MODE_GROUP &&
            viewSettings.layout == ViewSettings.LAYOUT_DOUBLE
        recycler.layoutManager = if (useDouble) {
            GridLayoutManager(this, 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        adapter.spanSizeForPosition(position)
                }
            }
        } else {
            LinearLayoutManager(this)
        }
    }

    /** 本地完成状态：适用于手动作业和欠账区自动作业，不会向学习通提交。 */
    private fun toggleLocalCompleted(homework: Homework) {
        val completed = homework.isUnfinished()
        if (homework.isManual()) {
            ManualHomeworkStore(this).setCompleted(homework.accountId, homework.id, completed)
        } else {
            localCompletionStore.setCompleted(homework.accountId, homework, completed)
        }
        Toast.makeText(
            this,
            if (completed) "已本地标记完成，提醒已停止" else "已恢复为未完成，提醒会重新安排",
            Toast.LENGTH_SHORT
        ).show()
        reloadVisibleHomework(homework.accountId)
    }

    /** 右滑置顶/取消置顶 */
    private fun togglePin(homework: Homework) {
        val uid = homework.accountId
        val nowPinned = pinnedStore.toggle(uid, homework.id, homework.courseKey)
        Toast.makeText(
            this,
            if (nowPinned) "已置顶：${homework.title}" else "已取消置顶",
            Toast.LENGTH_SHORT
        ).show()
        adapter.submit(currentHomework)   // 重新排序（置顶排最前）
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            currentUid?.let { loadHomework(it, force = true) }
        }
        findViewById<Button>(R.id.btn_date_filter).setOnClickListener {
            showDateFilterDialog()
        }
        findViewById<Button>(R.id.btn_add_homework).setOnClickListener {
            showAddHomeworkDialog()
        }
        findViewById<Button>(R.id.btn_remind_setting).setOnClickListener {
            showRemindSettingDialog()
        }
        findViewById<android.widget.ImageButton>(R.id.btn_settings).setOnClickListener {
            showSettingsDialog()
        }
    }

    // ---------- 设置（显示模式 / 布局） ----------

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val rgMode = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_mode)
        val rgLayout = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_layout)
        val rbDouble = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_layout_double)
        val btnExpandAll = dialogView.findViewById<Button>(R.id.btn_expand_all)
        val btnCollapseAll = dialogView.findViewById<Button>(R.id.btn_collapse_all)
        rgMode.check(if (viewSettings.mode == ViewSettings.MODE_GROUP) R.id.rb_mode_group else R.id.rb_mode_flat)
        rgLayout.check(if (viewSettings.layout == ViewSettings.LAYOUT_DOUBLE) R.id.rb_layout_double else R.id.rb_layout_single)

        fun syncLayoutChoices(modeId: Int) {
            val groupMode = modeId == R.id.rb_mode_group
            rbDouble.visibility = if (groupMode) View.VISIBLE else View.GONE
            if (!groupMode) rgLayout.check(R.id.rb_layout_single)
            btnExpandAll.visibility = if (groupMode) View.VISIBLE else View.GONE
            btnCollapseAll.visibility = if (groupMode) View.VISIBLE else View.GONE
        }
        syncLayoutChoices(rgMode.checkedRadioButtonId)
        rgMode.setOnCheckedChangeListener { _, checkedId -> syncLayoutChoices(checkedId) }

        // 学习交流群：点击复制群号
        dialogView.findViewById<TextView>(R.id.tv_group_code).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("学习交流群", "243863924"))
            Toast.makeText(this, "群号已复制：243863924", Toast.LENGTH_SHORT).show()
        }

        // 联调日志：排查"抓不到作业"等真机问题
        dialogView.findViewById<TextView>(R.id.tv_debug_log).setOnClickListener {
            showDebugLogDialog()
        }
        btnExpandAll.setOnClickListener {
            adapter.setAllGroupsExpanded(true)
        }
        btnCollapseAll.setOnClickListener {
            adapter.setAllGroupsExpanded(false)
        }
        dialogView.findViewById<Button>(R.id.btn_manage_courses).setOnClickListener {
            showManualCourseManager()
        }
        dialogView.findViewById<TextView>(R.id.tv_permission_status).text = reminderSystemStatus()
        dialogView.findViewById<Button>(R.id.btn_notification_permission).setOnClickListener {
            openNotificationSettings()
        }
        dialogView.findViewById<Button>(R.id.btn_exact_alarm_permission).setOnClickListener {
            openExactAlarmSettings()
        }
        dialogView.findViewById<Button>(R.id.btn_battery_permission).setOnClickListener {
            openBatterySettings()
        }

        val hiddenCount = currentUid?.let { hiddenStore.list(it).size } ?: 0
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val mode = if (rgMode.checkedRadioButtonId == R.id.rb_mode_group) {
                    ViewSettings.MODE_GROUP
                } else {
                    ViewSettings.MODE_FLAT
                }
                val layout = if (mode == ViewSettings.MODE_GROUP &&
                    rgLayout.checkedRadioButtonId == R.id.rb_layout_double) {
                    ViewSettings.LAYOUT_DOUBLE
                } else {
                    ViewSettings.LAYOUT_SINGLE
                }
                viewSettings.mode = mode
                viewSettings.layout = layout
                applyViewSettings()
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("已隐藏作业（$hiddenCount）") { _, _ ->
                showRestoreHiddenDialog()
            }
            .setNegativeButton("取消", null)
            .show()

        dialogView.findViewById<Button>(R.id.btn_logout).setOnClickListener {
            dialog.dismiss()
            confirmLogout()
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("只清除登录凭证，当前账号的作业、课程、隐藏和截止时间设置都会保留。")
            .setPositiveButton("退出") { _, _ ->
                performLogout()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 直接申请通知权限；权限已有但频道被静音时，进入具体频道页面。 */
    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                "android.permission.POST_NOTIFICATIONS"
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 100)
            return
        }
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ReminderNotifier.CHANNEL_ID)
            })
        }.onFailure {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
        }
    }

    /** 打开系统的精确闹钟授权页；低版本没有该权限模型。 */
    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "当前系统无需单独授权精确闹钟", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }.onFailure {
            Toast.makeText(this, "系统未提供精确闹钟设置页面，请在应用权限中手动开启", Toast.LENGTH_LONG).show()
        }
    }

    /** 优先打开本应用的电池优化授权页，厂商不支持时回退到系统列表。 */
    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        runCatching {
            val power = getSystemService(android.os.PowerManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                power != null && !power.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } else {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }.onFailure {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    /**
     * 首次进入时按顺序引导系统权限。每一步只记录一次，不会在每次启动时重复骚扰；
     * 用户选择“稍后设置”后仍可从设置页的三个入口重新打开。
     */
    private fun maybeGuideReminderPermissions() {
        if (permissionOnboardingDialog != null || isFinishing || isDestroyed || currentUid == null) return
        val prefs = getSharedPreferences("xxt_reminder_onboarding", MODE_PRIVATE)
        var step = prefs.getInt("step", 0)
        if (step == 0 && ReminderNotifier.canNotify(this) &&
            !ReminderNotifier.channelNeedsUserAttention(this)) {
            step = 1
            prefs.edit().putInt("step", step).apply()
        }
        if (step == 1 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ReminderScheduler.canScheduleExact(this))) {
            step = 2
            prefs.edit().putInt("step", step).apply()
        }
        if (step == 2 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations())) {
            step = 3
            prefs.edit().putInt("step", step).apply()
        }
        if (step >= 3) return

        val (title, message, openAction) = when (step) {
            0 -> Triple(
                getString(R.string.reminder_onboarding_notification_title),
                getString(R.string.reminder_onboarding_notification_message),
                { openNotificationSettings() }
            )
            1 -> Triple(
                getString(R.string.reminder_onboarding_exact_title),
                getString(R.string.reminder_onboarding_exact_message),
                { openExactAlarmSettings() }
            )
            else -> Triple(
                getString(R.string.reminder_onboarding_battery_title),
                getString(R.string.reminder_onboarding_battery_message),
                { openBatterySettings() }
            )
        }
        val currentStep = step
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.reminder_onboarding_open) { _, _ ->
                prefs.edit().putInt("step", currentStep + 1).apply()
                openAction()
            }
            .setNegativeButton(R.string.reminder_onboarding_later) { _, _ ->
                prefs.edit().putInt("step", currentStep + 1).apply()
                handler.postDelayed({ maybeGuideReminderPermissions() }, 250L)
            }
            .create()
        permissionOnboardingDialog = dialog
        dialog.setOnDismissListener { permissionOnboardingDialog = null }
        dialog.show()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = getSystemService(android.os.PowerManager::class.java)
        return power?.isIgnoringBatteryOptimizations(packageName) == true
    }

    /** 退出登录的实际执行部分，供用户主动退出和会话过期提示共用。 */
    private fun performLogout() {
        currentUid?.let { ReminderScheduler.pauseAccount(this, it) }
        cancelActiveLoad()
        accountManager.logout()
        android.webkit.CookieManager.getInstance().removeAllCookies {
            android.webkit.CookieManager.getInstance().flush()
            runOnUiThread {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }
    }

    private fun showSessionExpiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("学习通登录状态已过期")
            .setMessage("本地保存的作业、课程和自定义截止时间仍然保留。重新登录后，小笙会继续使用这些数据。")
            .setPositiveButton("重新登录") { _, _ -> performLogout() }
            .setNegativeButton("稍后处理", null)
            .show()
    }

    private fun showAntiSpiderDialog(requestUrl: String = "") {
        AlertDialog.Builder(this)
            .setTitle("学习通需要验证码验证")
            .setMessage(
                "学习通暂时要求完成图片验证码，这不一定代表登录失效。\n\n" +
                "请在小笙内打开刚才被拦截的学习通页面并亲自完成验证。验证完成后返回主页，小笙会自动重新检查一次。" +
                    "小笙不会自动填写验证码，也不会在返回页面时重复请求。"
            )
            .setPositiveButton("打开验证页面") { _, _ ->
                val uid = currentUid ?: return@setPositiveButton
                verificationLauncher.launch(
                    Intent(this, VerificationActivity::class.java)
                        .putExtra(VerificationActivity.EXTRA_ACCOUNT_ID, uid)
                        .putExtra(VerificationActivity.EXTRA_URL, requestUrl)
                )
            }
            .setNegativeButton("稍后处理", null)
            .show()
    }

    private fun updateFilterButton() {
        if (viewSettings.filterAll) {
            findViewById<Button>(R.id.btn_date_filter)?.text = "时间范围：全部"
            return
        }
        val start = viewSettings.filterStartDay
        val end = viewSettings.filterEndDay
        findViewById<Button>(R.id.btn_date_filter)?.text =
            "时间范围：${formatDate(start)} - ${formatDate(end)}"
    }

    private fun showDateFilterDialog() {
        val labels = arrayOf("全部时间", "今天", "未来 7 天", "本周", "本月", "自定义")
        AlertDialog.Builder(this)
            .setTitle("选择作业时间范围")
            .setItems(labels) { _, which ->
                val today = ViewSettings.startOfDay()
                when (which) {
                    0 -> applyAllDateFilter()
                    1 -> applyDateFilter(today, today)
                    2 -> applyDateFilter(today, today + 6L * DAY_MS)
                    3 -> {
                        val cal = Calendar.getInstance().apply { timeInMillis = today }
                        val day = cal.get(Calendar.DAY_OF_WEEK)
                        val delta = (day + 5) % 7 // 周一作为一周第一天
                        applyDateFilter(today - delta * DAY_MS, today + (6 - delta) * DAY_MS)
                    }
                    4 -> {
                        val cal = Calendar.getInstance().apply { timeInMillis = today }
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        val first = cal.timeInMillis
                        cal.add(Calendar.MONTH, 1)
                        cal.add(Calendar.DAY_OF_MONTH, -1)
                        applyDateFilter(first, cal.timeInMillis)
                    }
                    else -> showCustomDateFilter()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomDateFilter() {
        pickDate(viewSettings.filterStartDay, "选择开始日期") { start ->
            pickDate(viewSettings.filterEndDay, "选择结束日期") { end ->
                applyDateFilter(start, end)
            }
        }
    }

    private fun pickDate(initial: Long, title: String, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(this, { _, year, month, day ->
            val selected = Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            onPicked(selected)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            setTitle(title)
        }.show()
    }

    private fun applyDateFilter(start: Long, end: Long) {
        if (start > end) {
            Toast.makeText(this, "开始日期不能晚于结束日期", Toast.LENGTH_SHORT).show()
            return
        }
        viewSettings.filterAll = false
        viewSettings.filterStartDay = start
        viewSettings.filterEndDay = end
        updateFilterButton()
        currentUid?.let { reloadVisibleHomework(it) }
    }

    private fun applyAllDateFilter() {
        viewSettings.filterAll = true
        updateFilterButton()
        currentUid?.let { reloadVisibleHomework(it) }
    }

    /** 联调日志：读取本地抓取日志，方便排查"抓不到作业"这类真机问题 */
    private fun showDebugLogDialog() {
        val content = try {
            File(filesDir, "debug_log.txt").readText()
        } catch (_: Exception) { "" }
        if (content.isBlank()) {
            Toast.makeText(this, "暂无日志（先点一次「刷新作业」再回来看）", Toast.LENGTH_SHORT).show()
            return
        }
        val tv = TextView(this).apply {
            text = content
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1.15f)
            setTextColor(getColorCompat(R.color.text_primary))
        }
        val scroll = ScrollView(this).apply {
            addView(tv)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("联调日志（不含账号敏感信息）")
            .setView(scroll)
            .setPositiveButton("复制日志") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("联调日志", content))
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showManualCourseManager() {
        val uid = currentUid ?: return
        val courses = courseStore.list(uid).filter { it.manual }
        if (courses.isEmpty()) {
            Toast.makeText(this, "暂时没有手动课程", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("管理手动课程")
            .setItems(courses.map { it.name }.toTypedArray()) { _, which ->
                val course = courses[which]
                AlertDialog.Builder(this)
                    .setTitle("删除课程快捷项？")
                    .setMessage("只删除课程库里的快捷选项，不会删除已经添加的作业。")
                    .setPositiveButton("删除") { _, _ ->
                        courseStore.removeManual(uid, course.key)
                        Toast.makeText(this, "已删除课程快捷项", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 已隐藏作业管理：按隐藏时间从新到旧排序，可单独恢复（默认）或批量恢复 */
    private fun showRestoreHiddenDialog() {
        val uid = currentUid ?: return
        val items = hiddenStore.list(uid)
        if (items.isEmpty()) {
            Toast.makeText(this, "当前没有已隐藏的作业", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_restore_hidden, null)
        val listBox = dialogView.findViewById<LinearLayout>(R.id.restore_list)
        val cbBatch = dialogView.findViewById<CheckBox>(R.id.cb_batch_mode)
        val btnRestore = dialogView.findViewById<Button>(R.id.btn_restore_selected)
        val selected = mutableSetOf<String>()
        var dialog: AlertDialog? = null

        fun rebuildRows() {
            listBox.removeAllViews()
            items.forEachIndexed { idx, item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    isClickable = true
                }
                // 序号（从新到旧 1、2、3…）
                val tvNo = TextView(this).apply {
                    text = "${idx + 1}."
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 13f
                }
                // 内容：标题 + 课程 · 截止
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(dp(10), 0, 0, 0)
                }
                val tvTitle = TextView(this).apply {
                    text = item.title
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 14f
                    maxLines = 1
                }
                val tvMeta = TextView(this).apply {
                    val src = if (item.source == HiddenStore.SOURCE_MANUAL) " · 手动" else ""
                    val scope = if (item.permanent) "永久" else "当前范围"
                    val deadline = if (item.endTime > 0L) formatTime(item.endTime) else "未知"
                    text = "${item.course} · 截止 $deadline · ${formatDateTime(item.hiddenAt)} 隐藏 · $scope$src"
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 12f
                }
                content.addView(tvTitle)
                content.addView(tvMeta)
                // 批量模式复选框
                val cb = CheckBox(this).apply {
                    visibility = if (cbBatch.isChecked) View.VISIBLE else View.GONE
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selected.add(item.id) else selected.remove(item.id)
                        btnRestore.text = "恢复所选（${selected.size}）"
                    }
                }
                row.addView(tvNo)
                row.addView(content)
                row.addView(cb)
                // 单选模式（默认）：点击整行恢复这一条（手动作业先写回手动列表）
                row.setOnClickListener {
                    if (cbBatch.isChecked) {
                        cb.isChecked = !cb.isChecked
                    } else {
                        restoreHiddenItem(uid, item)
                        Toast.makeText(this, "已恢复：${item.title}", Toast.LENGTH_SHORT).show()
                        loadHomework(uid)
                        dialog?.dismiss()
                    }
                }
                listBox.addView(row)
            }
            btnRestore.text = "恢复所选（${selected.size}）"
        }

        cbBatch.setOnCheckedChangeListener { _, isChecked ->
            selected.clear()
            btnRestore.visibility = if (isChecked) View.VISIBLE else View.GONE
            rebuildRows()
        }
        btnRestore.setOnClickListener {
            if (selected.isEmpty()) {
                Toast.makeText(this, "请先勾选要恢复的作业", Toast.LENGTH_SHORT).show()
            } else {
                // 手动作业先写回手动列表，再统一移除隐藏记录
                items.filter { it.id in selected }.forEach { restoreHiddenItem(uid, it) }
                Toast.makeText(this, "已恢复 ${selected.size} 条作业", Toast.LENGTH_SHORT).show()
                loadHomework(uid)
                dialog?.dismiss()
            }
        }

        rebuildRows()
        dialog = AlertDialog.Builder(this)
            .setTitle("已隐藏作业（${items.size}）")
            .setView(dialogView)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun getColorCompat(res: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, res)

    /** 应用视图设置：切换布局 + 刷新适配器 */
    private fun applyViewSettings() {
        val recycler = findViewById<RecyclerView>(R.id.recycler_homework)
        applyLayoutManager(recycler)
        adapter.setViewSettings(viewSettings.mode, viewSettings.layout)
        adapter.submit(currentHomework)
    }

    /** 取消旧账号的抓取，并使它已经排队的 UI 回调全部失效。 */
    private fun cancelActiveLoad() {
        loadGeneration.incrementAndGet()
        activeLoadThread?.interrupt()
        activeLoadThread = null
        loadingUid = null
        if (::adapter.isInitialized) {
            setRefreshEnabled(true)
        }
    }

    private fun setRefreshEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.btn_refresh)?.isEnabled = enabled
    }

    /** 账号切换时丢弃旧账号的展开状态和截止时间提示，避免跨账号串界面。 */
    private fun resetAccountUiState() {
        if (::adapter.isInitialized) adapter.resetAccountState()
        deadlinePromptQueue.clear()
        deadlinePromptDialog?.dismiss()
        unknownDeadlineDialog?.dismiss()
        unknownPromptShowing = false
    }

    /** 抓取当前账号的作业（学习通自动检测 + 本地手动作业），后台线程不卡界面 */
    private fun loadHomework(uid: String, force: Boolean = false) {
        val cookie = accountManager.getAccounts()[uid] ?: return

        // 同一账号只能有一个任务；切换账号时立即中断旧任务。
        if (activeLoadThread?.isAlive == true) {
            if (loadingUid == uid) return
            cancelActiveLoad()
        }

        // ① 先把上次抓到的缓存立刻显示出来（不用等网络），手动作业永远在
        val manual = ManualHomeworkStore(this).list(uid)
        val cached = cache.load(uid)
        currentHomework = (cached + manual)
            .map(deadlineOverrides::apply)
            .map(::applyLocalCompletion)
            .filter(::isInCurrentScope)
            .filterNot(::isCompletedWithUnknownDeadline)
            .filterNot { hiddenStore.isHidden(uid, it, currentScopeKey()) }
            .distinctBy(::homeworkKey)
        adapter.submit(currentHomework)
        updateStats(currentHomework)

        // 无缓存时首次抓取；之后仅手动刷新或缓存过期才访问网络。
        val firstEntryWithoutCache = enteredUids.add(uid) && cached.isEmpty()
        val wantsAutoFetch = firstEntryWithoutCache || cache.isStale(uid, CACHE_MAX_AGE_MS)
        // 首次进入无缓存，或缓存确实过期时才自动请求；其余情况只由用户
        // 点击“重新检查”触发，避免返回页面/切换窗口反复刷新。
        val shouldFetch = force || (
            wantsAutoFetch &&
                !cache.isFetchAttemptCoolingDown(uid, AUTO_FETCH_RETRY_COOLDOWN_MS)
        )
        if (!shouldFetch) {
            hideFetchProgress()
            setRefreshEnabled(true)
            rescheduleCurrentAccount()
            showUnknownDeadlinePromptIfNeeded()
            showManualExpiredPromptIfNeeded()
            return
        }

        // 记录尝试时间而不是只在成功后记录，失败时也要抑制短时间内的自动重试。
        cache.markFetchAttempt(uid)

        val generation = loadGeneration.incrementAndGet()
        loadingUid = uid
        setRefreshEnabled(false)
        showFetchProgress(0, 0, "", true)   // 阶段一：正在获取课程列表
        var finishMsg = ""
        val hadFailure = AtomicBoolean(false)
        val detailChecked = AtomicInteger(0)
        val detailFixed = AtomicInteger(0)
        val detailTotal = AtomicInteger(0)
        val fetchedCourseKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val worker = Thread {
            try {
                // 抓取完整自动作业；隐藏和时间筛选只在本地展示/提醒层处理。
                val auto = api.fetchAll(
                    cookie, uid,
                    inspectDetails = force,
                    onProgress = { done, total, name, ok ->
                        if (!ok) hadFailure.set(true)
                        runOnUiThread {
                            if (generation == loadGeneration.get() && currentUid == uid) {
                                showFetchProgress(done, total, name, ok)
                            }
                        }
                    },
                    onDetailProgress = { done, total, courseName, title, ok ->
                        detailChecked.incrementAndGet()
                        detailTotal.updateAndGet { old -> maxOf(old, total) }
                        if (ok) detailFixed.incrementAndGet()
                        runOnUiThread {
                            if (generation == loadGeneration.get() && currentUid == uid) {
                                showDetailProgress(done, total, courseName, title, ok)
                            }
                        }
                    },
                    onCourse = { course, hws, ok ->
                        // 先在线程回调入口记录成功课程，避免缓存保存先于主线程 UI 回调执行。
                        if (ok) fetchedCourseKeys.add(course.key)
                        // ② 边抓边显示：抓完一门课，立刻把这门课的作业刷上界面
                        runOnUiThread {
                            if (generation == loadGeneration.get() && currentUid == uid && ok) {
                                // 先把本次抓取的官方原始值写入缓存，截止时间恢复提示可以立即读到最新值。
                                // 空解析不能覆盖旧数据：验证码、空页面或改版时保留上一份有效缓存。
                                val mergedHomeworks = hws.map { cache.upsert(uid, it) }
                                courseStore.upsertAutomatic(
                                    uid,
                                    CourseStore.CourseItem(
                                        key = course.key,
                                        accountId = uid,
                                        name = course.name.ifBlank { "未命名课程" },
                                        className = course.className,
                                        term = course.term,
                                        courseId = course.courseId,
                                        classId = course.classId,
                                        cpi = course.cpi,
                                        manual = false
                                    )
                                )
                                mergeCourse(course, mergedHomeworks)
                            }
                        }
                    }
                )
                // ③ 全部抓完：自动作业写入本地缓存（下次进应用秒显示）
                if (generation == loadGeneration.get() && currentUid == uid) {
                    // 解析为空时保留该课程旧缓存，避免验证码/空页面把作业从列表抹掉。
                    // 成功返回 0 条作业的课程也要替换掉旧缓存；否则已结束学期的旧作业会永久残留。
                    // 失败课程不进入 fetchedCourseKeys 的有效保存路径，仍由 preserved 保留旧数据。
                    // 缓存保存完整抓取结果，时间筛选只影响界面，切换区间不能丢历史数据。
                    // 列表暂时漏项不能删除缓存作业；只有明确的用户操作才能移除。
                    cache.save(uid, HomeworkCachePolicy.mergeSnapshot(cache.load(uid), auto))
                    if (!hadFailure.get()) {
                        courseStore.removeStaleAutomatic(uid, fetchedCourseKeys)
                    }
                    if (hadFailure.get()) {
                        DebugLog.log(this@MainActivity, "部分课程抓取失败：已保存成功课程，保留失败课程旧缓存")
                    }
                }
                val (msg, idx) = if (hadFailure.get()) {
                    pickRandom(failDoneMsgs, lastFailDoneIdx)
                } else {
                    pickRandom(doneMsgs, lastDoneIdx)
                }
                if (hadFailure.get()) lastFailDoneIdx = idx else lastDoneIdx = idx
                finishMsg = msg
                if (force && detailTotal.get() > 0) {
                    finishMsg = "截止时间核对完成：已逐条查看 ${detailChecked.get()} 条，得到可靠时间 ${detailFixed.get()} 条，仍需人工确认 ${detailChecked.get() - detailFixed.get()} 条。"
                }
            } catch (e: Exception) {
                handler.post {
                    if (generation != loadGeneration.get() || currentUid != uid) return@post
                    if (e is XueXiTongApi.AntiSpiderException) {
                        showAntiSpiderDialog(e.requestUrl)
                    } else if (e is XueXiTongApi.SessionExpiredException) {
                        showSessionExpiredDialog()
                    } else {
                        val (msg, _) = pickRandom(errorMsgs, lastErrorIdx)
                        Toast.makeText(
                            this,
                            fillTemplate(msg, 0, 0, "", e.message ?: "未知错误"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                val (msg, idx) = pickRandom(failDoneMsgs, lastFailDoneIdx)
                lastFailDoneIdx = idx
                finishMsg = msg
            } finally {
                handler.post {
                    if (generation != loadGeneration.get() || currentUid != uid) return@post
                    loadingUid = null
                    activeLoadThread = null
                    setRefreshEnabled(true)
                    showUnknownDeadlinePromptIfNeeded()
                    showManualExpiredPromptIfNeeded()
                    // 抓取完成：重新安排提醒 + 定格显示完成状态卡 1 秒再隐藏
                    rescheduleCurrentAccount()
                    showFetchDone(finishMsg)
                    handler.postDelayed({ hideFetchProgress() }, 1000L)
                }
            }
        }
        activeLoadThread = worker
        worker.start()
    }

    /** 增量合并：把刚抓完的一门课作业合进列表（去重、过滤隐藏、保留手动作业），立即刷新界面 */
    private fun mergeCourse(course: XueXiTongApi.Course, hws: List<Homework>) {
        val uid = currentUid
        val manual = uid?.let { account ->
            ManualHomeworkStore(this).list(account)
                .filter(::isInCurrentScope)
                .filterNot { hiddenStore.isHidden(account, it, currentScopeKey()) }
        } ?: emptyList()
        val visible = hws.map { homework ->
            deadlineOverrides.observe(homework)?.let { prompt ->
                enqueueDeadlinePrompt(prompt)
            }
            applyLocalCompletion(deadlineOverrides.apply(homework))
        }.filter(::isInCurrentScope).filterNot(::isCompletedWithUnknownDeadline).let { list ->
            if (uid != null) list.filterNot { hiddenStore.isHidden(uid, it, currentScopeKey()) } else list
        }
        // 用最新页面信息更新课程；本次列表漏项不代表作业已被平台移除。
        val oldCourseItems = currentHomework.filter {
            !it.isManual() && (it.courseKey == course.key || it.courseName == course.displayName)
        }
        val preservedMissing = HomeworkCachePolicy.missingFrom(oldCourseItems, hws)
        val base = currentHomework - oldCourseItems.toSet()
        currentHomework = (base + visible + preservedMissing + manual)
            .distinctBy(::homeworkKey)
        adapter.submit(currentHomework)
        updateStats(currentHomework)
    }

    /** 严格时间筛选：当前范围外记录不进入主列表；全部时间才显示所有记录。 */
    private fun isInCurrentScope(hw: Homework): Boolean {
        if (viewSettings.filterAll) return true
        val start = viewSettings.filterStartDay
        val end = ViewSettings.endOfDay(viewSettings.filterEndDay)
        return com.zhizhou.xxtassistant.data.HomeworkScope.contains(hw, false, start, end)
    }

    private fun isCompletedWithUnknownDeadline(hw: Homework): Boolean =
        hw.status == Homework.DONE && hw.endTime <= 0L

    private fun applyLocalCompletion(hw: Homework): Homework =
        if (!hw.isManual() && localCompletionStore.isCompleted(hw.accountId, hw)) {
            hw.copy(status = Homework.DONE)
        } else hw

    private fun currentScopeKey(): String =
        if (viewSettings.filterAll) "all" else "${viewSettings.filterStartDay}:${viewSettings.filterEndDay}"

    private fun homeworkKey(homework: Homework): String =
        "${homework.accountId}:${homework.courseKey}:${homework.id}"

    /** 提醒不受日期筛选影响，只排除当前隐藏的作业。 */
    private fun rescheduleCurrentAccount() {
        val uid = currentUid ?: return
        val all = (cache.load(uid) + ManualHomeworkStore(this).list(uid))
            .map(deadlineOverrides::apply)
            .map(::applyLocalCompletion)
            .filterNot { hiddenStore.isHidden(uid, it, currentScopeKey()) }
            .distinctBy(::homeworkKey)
        ReminderScheduler.reschedule(this, all)
    }

    /** 抓取完成后的定格卡（满格进度 + 小笙收工语），保证进度条不会一闪而过 */
    private fun showFetchDone(msg: String) {
        val layout = findViewById<View>(R.id.layout_progress)
        val bar = findViewById<android.widget.ProgressBar>(R.id.progress_fetch)
        val tv = findViewById<TextView>(R.id.tv_progress)
        val tvMsg = findViewById<TextView>(R.id.tv_progress_msg)
        layout.visibility = View.VISIBLE
        bar.isIndeterminate = false
        bar.max = 1
        bar.progress = 1
        tvMsg.text = msg
        tv.text = ""
    }

    // ---------- 小笙人格：抓取时的拟人化文案（随机切换，不重复） ----------

    /** 开工（获取课程列表） */
    private val startMsgs = arrayOf(
        "小主人不要着急，小笙正在努力工作中…",
        "开工啦！小笙这就去把作业都翻出来～",
        "哼，交给小笙就放心吧，马上好！",
        "别急别急，小笙已经在跑腿啦～"
    )

    /** 全部抓完：收工求夸 */
    private val doneMsgs = arrayOf(
        "全部搞定！小笙完成任务啦～",
        "搞定收工！主人可以放心啦～",
        "作业都搬完啦，小笙是不是很厉害！"
    )

    /** 整体失败后的定格文案（进度条消失前那一下） */
    private val failDoneMsgs = arrayOf(
        "呜…这次没抓到，主人再按一次刷新试试？",
        "网络不听话…小笙对不起主人…",
        "唔…失败了，但小笙会陪着主人想办法的！"
    )

    /** 每抓完一门课：小傲娇求夸奖（{n}=已完成数 {total}=总数 {course}=课程名） */
    private val progressMsgs = arrayOf(
        "哼～第 {n} 门搞定，主人快夸夸小笙！",
        "又拿下一门，小笙是不是超厉害的～",
        "嘿嘿，{course} 也被小笙抓到啦，求表扬！",
        "主人看！{n}/{total} 啦，小笙可没偷懒哦～",
        "搞定一门！快说小笙最棒！",
        "这门课小笙也拿到手了，是不是该有奖励呀～"
    )

    /** 单门课抓取失败：小笙安慰（不会中断整体抓取） */
    private val courseFailMsgs = arrayOf(
        "呜…{course} 没抓成，主人别急，小笙再试试别的！",
        "唔，{course} 有点傲娇不理小笙，但小笙不会放弃的！",
        "网络又闹脾气了…不过 {n}/{total} 已完成，小笙继续加油！"
    )

    /** 整体抓取失败：安慰主人 */
    private val errorMsgs = arrayOf(
        "呜…网络又闹脾气了：{course}。主人别难过，小笙会一直陪着你的！",
        "唔…这次没抓到：{course}。主人不要着急，稍后再试试哦～",
        "对不起主人…小笙尽力了：{course}。要不要再按一次刷新？"
    )

    private var lastStartIdx = -1
    private var lastProgressIdx = -1
    private var lastFailIdx = -1
    private var lastErrorIdx = -1
    private var lastDoneIdx = -1
    private var lastFailDoneIdx = -1

    /** 随机挑一条文案，避免与上一条重复 */
    private fun pickRandom(arr: Array<String>, avoid: Int): Pair<String, Int> {
        var idx = (arr.indices).random()
        if (arr.size > 1) {
            var guard = 0
            while (idx == avoid && guard++ < 6) idx = (arr.indices).random()
        }
        return arr[idx] to idx
    }

    /** 把文案模板里的 {n}/{total}/{course} 占位符替换成真实值 */
    private fun fillTemplate(tpl: String, done: Int, total: Int, course: String, err: String = ""): String =
        tpl.replace("{n}", done.toString())
            .replace("{total}", total.toString())
            .replace("{course}", course)
            .replace("{err}", err)

    /** 抓取进度显示：done/total 为 0/0 时显示"获取课程列表"（不确定进度条） */
    @SuppressLint("SuspiciousIndentation")
    private fun showFetchProgress(done: Int, total: Int, courseName: String, ok: Boolean) {
        val layout = findViewById<View>(R.id.layout_progress)
        val bar = findViewById<android.widget.ProgressBar>(R.id.progress_fetch)
        val tv = findViewById<TextView>(R.id.tv_progress)
        val tvMsg = findViewById<TextView>(R.id.tv_progress_msg)
        layout.visibility = View.VISIBLE
        if (total <= 0) {
            bar.isIndeterminate = true
            tv.text = ""
            val (msg, idx) = pickRandom(startMsgs, lastStartIdx)
            lastStartIdx = idx
            tvMsg.text = msg
        } else {
            bar.isIndeterminate = false
            bar.max = total
            bar.progress = done
            tv.text = if (courseName.isNotBlank()) "正在抓取 $done/$total：$courseName" else "正在抓取 $done/$total"
            val pool = if (ok) progressMsgs to lastProgressIdx else courseFailMsgs to lastFailIdx
            val (msg, idx) = pickRandom(pool.first, pool.second)
            if (ok) lastProgressIdx = idx else lastFailIdx = idx
            tvMsg.text = fillTemplate(msg, done, total, courseName)
        }
    }

    /** 用户主动刷新时显示逐条详情核对进度，不把它混同为普通课程抓取进度。 */
    private fun showDetailProgress(done: Int, total: Int, courseName: String, title: String, ok: Boolean) {
        val layout = findViewById<View>(R.id.layout_progress)
        val bar = findViewById<android.widget.ProgressBar>(R.id.progress_fetch)
        val tv = findViewById<TextView>(R.id.tv_progress)
        val tvMsg = findViewById<TextView>(R.id.tv_progress_msg)
        layout.visibility = View.VISIBLE
        bar.isIndeterminate = false
        bar.max = total.coerceAtLeast(1)
        bar.progress = done
        tv.text = "正在核对截止时间 $done/$total"
        tvMsg.text = if (ok) "已核对：$courseName · $title" else "暂未读到：$courseName · $title"
    }

    private fun hideFetchProgress() {
        findViewById<View>(R.id.layout_progress).visibility = View.GONE
    }

    /** 更新顶部统计卡片（未完成 / 已完成）与空状态提示 */
    private fun updateStats(list: List<Homework>) {
        val unfinished = list.count { it.isUnfinished() }
        val done = list.count { !it.isUnfinished() && !it.isMissed() }
        val missed = list.count { it.isMissed() }
        findViewById<TextView>(R.id.tv_stat_unfinished).text = unfinished.toString()
        findViewById<TextView>(R.id.tv_stat_done).text = done.toString()
        findViewById<TextView>(R.id.tv_stat_missed).text = missed.toString()
        findViewById<TextView>(R.id.tv_empty).visibility =
            if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 将官方时间变化提示排队，同一时刻只显示一个，避免连续刷新弹出多个窗口。 */
    private fun enqueueDeadlinePrompt(prompt: DeadlineOverrideStore.Prompt) {
        if (deadlinePromptQueue.none {
                it.homework.accountId == prompt.homework.accountId &&
                    it.homework.courseKey == prompt.homework.courseKey &&
                    it.homework.id == prompt.homework.id
            }) {
            deadlinePromptQueue.addLast(prompt)
        }
        showNextDeadlinePrompt()
    }

    private fun showNextDeadlinePrompt() {
        if (deadlinePromptShowing || deadlinePromptQueue.isEmpty() || isFinishing || isDestroyed) return
        val prompt = deadlinePromptQueue.removeFirst()
        deadlinePromptShowing = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("官方截止时间有更新")
            .setMessage(
                "${prompt.homework.courseName}\n${prompt.homework.title}\n\n" +
                    "学习通检测到：${formatDateTime(prompt.officialEndTime)}\n" +
                    "当前自定义时间：${formatDateTime(prompt.customEndTime)}"
            )
            .setPositiveButton("恢复官方时间") { _, _ ->
                deadlineOverrides.clearOverride(prompt.homework)
                reloadVisibleHomework(prompt.homework.accountId)
            }
            .setNegativeButton("继续使用自定义时间") { _, _ ->
                deadlineOverrides.keepCustom(prompt.homework.copy(officialEndTime = prompt.officialEndTime))
            }
            .setNeutralButton("暂不处理", null)
            .setOnDismissListener {
                deadlinePromptShowing = false
                deadlinePromptDialog = null
                handler.postDelayed({ showNextDeadlinePrompt() }, 250L)
            }
            .show()
        deadlinePromptDialog = dialog
    }

    private val unknownPromptMessages = arrayOf(
        "不好意思，我的过失让您受累了：这条作业的截止时间暂时没有读到。要不要现在帮它补上时间呢？",
        "呜……小笙这次没把截止时间看清楚，让您多费心了。设置一个时间后，我就能准确提醒您。",
        "对不起主人，这条作业的时间信息躲起来了。小笙先来道歉，您可以现在补充，也可以明天再提醒。",
        "小笙检讨一下：这次没有可靠地读到截止时间，辛苦您帮忙确认一下啦。",
        "不好意思给您添麻烦了，这条作业的截止时间未知。补上时间后，提醒就不会漏掉了。"
    )

    /** 按账号一次汇总所有待处理未知时间作业，避免一条一条连续弹窗。 */
    @SuppressLint("SuspiciousIndentation")
    private fun showUnknownDeadlinePromptIfNeeded() {
        if (unknownPromptShowing || currentUid == null || isFinishing || isDestroyed) return
        val uid = currentUid ?: return
        // 严格时间范围下未知作业不进入主列表，但仍在独立提示中汇总，避免用户以为它们消失了。
        val promptSource = (cache.load(uid) + ManualHomeworkStore(this).list(uid))
            .map(deadlineOverrides::apply)
            .map(::applyLocalCompletion)
            .distinctBy(::homeworkKey)
            .filterNot { hiddenStore.isHidden(uid, it, currentScopeKey()) }
        val unknown = promptSource.filter {
            it.accountId == uid && !it.isManual() &&
                it.status == Homework.PENDING && it.endTime <= 0L
        }
        if (unknown.isEmpty() || !unknownDeadlinePrompts.shouldPrompt(uid)) return
        unknownDeadlinePrompts.snooze(uid)
        unknownPromptShowing = true
        val grouped = unknown.groupBy { it.courseName }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        content.addView(TextView(this).apply {
            text = "共 ${grouped.size} 门课程，${unknown.size} 条作业待核对。\n${unknownPromptMessages.random()}"
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        })
        grouped.forEach { (courseName, courseItems) ->
            content.addView(TextView(this).apply {
                text = "$courseName（${courseItems.size} 条）"
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(2))
            })
            courseItems.forEach { homework ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                row.addView(TextView(this).apply {
                    text = "${homework.title}\n状态：${homework.statusText()}"
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this).apply {
                    text = "设置"
                    minWidth = 0
                    setPadding(dp(10), 0, dp(10), 0)
                    setOnClickListener {
                        showDeadlineDatePickerWithContext(homework)
                    }
                })
                content.addView(row)
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("截止时间需要确认")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("明天再提醒", null)
            .setOnDismissListener { unknownPromptShowing = false; unknownDeadlineDialog = null }
            .show()
        unknownDeadlineDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
    }

    private fun showDeadlineDatePickerWithContext(homework: Homework) {
        AlertDialog.Builder(this)
            .setTitle("设置截止时间")
            .setMessage(
                "课程：${homework.courseName}\n" +
                    "作业：${homework.title}\n" +
                    "状态：${homework.statusText()}\n\n" +
                    "请选择这条作业的截止日期和时间。"
            )
            .setPositiveButton("选择时间") { _, _ ->
                showDateTimePicker(initial = null) { selected ->
                    deadlineOverrides.setOverride(homework, selected)
                    reloadVisibleHomework(homework.accountId)
                    Toast.makeText(this, "已设置截止时间", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 手动作业过期后只询问一次，避免每次进入应用都重复打扰。 */
    private fun showManualExpiredPromptIfNeeded() {
        val uid = currentUid ?: return
        if (isFinishing || isDestroyed || deadlinePromptShowing || unknownPromptShowing) return
        val expired = ManualHomeworkStore(this).list(uid)
            .map(deadlineOverrides::apply)
            .filter { it.status == Homework.PENDING && it.endTime > 0L && it.endTime < System.currentTimeMillis() }
            .firstOrNull { !manualDeadlinePrompts.wasPrompted(uid, it.id, it.endTime) }
            ?: return
        manualDeadlinePrompts.markPrompted(uid, expired.id, expired.endTime)
        AlertDialog.Builder(this)
            .setTitle("这项手动作业已经截止")
            .setMessage(
                "${expired.courseName}\n${expired.title}\n\n" +
                    "不好意思，我的过失让您受累了：这项手动作业的截止时间已经过去。" +
                    "如果您已经完成，请在小笙里标记完成；如果还没有完成，也可以稍后继续处理。"
            )
            .setPositiveButton("已完成") { _, _ ->
                ManualHomeworkStore(this).setCompleted(uid, expired.id, true)
                reloadVisibleHomework(uid)
            }
            .setNegativeButton("还没完成", null)
            .show()
    }

    private fun reloadVisibleHomework(uid: String) {
        if (currentUid != uid) return
        val manual = ManualHomeworkStore(this).list(uid)
        currentHomework = (cache.load(uid) + manual)
            .map(deadlineOverrides::apply)
            .map(::applyLocalCompletion)
            .filter(::isInCurrentScope)
            .filterNot(::isCompletedWithUnknownDeadline)
            .filterNot { hiddenStore.isHidden(uid, it, currentScopeKey()) }
            .distinctBy(::homeworkKey)
        if (currentUid == uid) {
            adapter.submit(currentHomework)
            updateStats(currentHomework)
            rescheduleCurrentAccount()
            showUnknownDeadlinePromptIfNeeded()
            showManualExpiredPromptIfNeeded()
        }
    }

    private fun showDeadlineOverrideDialog(homework: Homework) {
        if (homework.deadlineOverridden) {
            deadlineOverrides.clearOverride(homework)
            reloadVisibleHomework(homework.accountId)
            Toast.makeText(this, "已恢复官方截止时间", Toast.LENGTH_SHORT).show()
            return
        }
        val current = homework.endTime.takeIf { it > 0L } ?: homework.officialEndTime
        showDateTimePicker(initial = current) { selected ->
            deadlineOverrides.setOverride(homework, selected)
            reloadVisibleHomework(homework.accountId)
            Toast.makeText(this, "已使用自定义截止时间", Toast.LENGTH_SHORT).show()
        }
    }

    /** 手动作业在抓取进行中也要立即出现在列表里，不能重新读旧缓存覆盖掉当前进度。 */
    private fun refreshAfterManualChange(uid: String) {
        val manual = ManualHomeworkStore(this).list(uid)
        val auto = if (currentUid == uid) {
            currentHomework.filterNot { it.isManual() }
        } else {
            cache.load(uid)
        }
        currentHomework = (auto + manual)
            .map(deadlineOverrides::apply)
            .map(::applyLocalCompletion)
            .filter(::isInCurrentScope)
            .filterNot(::isCompletedWithUnknownDeadline)
            .filterNot { hiddenStore.isHidden(uid, it, currentScopeKey()) }
            .distinctBy(::homeworkKey)
        if (currentUid == uid) {
            adapter.submit(currentHomework)
            updateStats(currentHomework)
            rescheduleCurrentAccount()
            showUnknownDeadlinePromptIfNeeded()
        }
    }

    // ---------- 手动添加作业 ----------

    /** 弹窗表单：输入作业名/课程名，选择截止时间 */
    private fun showAddHomeworkDialog() {
        val uid = currentUid ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_homework, null)
        val etTitle = dialogView.findViewById<EditText>(R.id.et_hw_title)
        val etNewCourse = dialogView.findViewById<EditText>(R.id.et_new_course)
        val tvDeadline = dialogView.findViewById<TextView>(R.id.tv_deadline)
        val holder = arrayOfNulls<Long>(1)  // 保存用户从日历里选中的截止时间
        val sortButton = dialogView.findViewById<Button>(R.id.btn_course_sort)
        var selectedCourse: CourseStore.CourseItem? = null
        var newCourseMode = false
        fun updateCourseButton() {
            sortButton.text = selectedCourse?.displayName ?:
                if (newCourseMode) "课程：新增课程" else "选择课程（点击打开最近使用/拼音索引）"
            etNewCourse.visibility = if (newCourseMode) View.VISIBLE else View.GONE
        }
        updateCourseButton()
        sortButton.setOnClickListener {
            showCoursePicker(uid) { course ->
                selectedCourse = course
                newCourseMode = course == null
                updateCourseButton()
            }
        }

        tvDeadline.setOnClickListener {
            showDateTimePicker { ts ->
                holder[0] = ts
                tvDeadline.text = "截止 ${formatDateTime(ts)}"
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("手动添加作业")
            .setMessage("适合老师线下布置、不在学习通里的作业")
            .setView(dialogView)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = etTitle.text.toString().trim()
                val deadline = holder[0] ?: 0L
                when {
                    title.isEmpty() ->
                        Toast.makeText(this, "请填写作业名称", Toast.LENGTH_SHORT).show()
                    deadline <= 0 ->
                        Toast.makeText(this, "请选择截止时间", Toast.LENGTH_SHORT).show()
                    else -> {
                        val chosenCourse = selectedCourse
                        val course = if (chosenCourse != null) {
                            courseStore.markUsed(uid, chosenCourse.key)
                            chosenCourse
                        } else {
                            courseStore.addManual(uid, etNewCourse.text.toString())
                        }
                        if (course == null) {
                            Toast.makeText(this, "请填写课程名称", Toast.LENGTH_SHORT).show()
                        } else {
                            ManualHomeworkStore(this).add(uid, title, course.displayName, deadline, course.key)
                            Toast.makeText(this, "已添加，提醒将自动生效", Toast.LENGTH_SHORT).show()
                            refreshAfterManualChange(uid)
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * 课程选择器：最近使用模式是紧凑列表；拼音模式按 #/A-Z 分组并提供右侧快速索引。
     * 课程名称的首字母由 CourseStore 统一计算，数字和特殊字符始终归入 #。
     */
    private fun showCoursePicker(
        uid: String,
        onSelected: (CourseStore.CourseItem?) -> Unit
    ) {
        var recentFirst = viewSettings.courseSortRecent
        val dialog = AlertDialog.Builder(this).setTitle("选择课程").create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        dialog.setView(root)

        fun render() {
            val courses = courseStore.listForManualSelection(uid, recentFirst)
            root.removeAllViews()
            val listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val sort = Button(this).apply {
                text = if (recentFirst) "排序：最近使用/添加（切换为拼音首字母）"
                else "排序：拼音首字母（切换为最近使用/添加）"
                setOnClickListener {
                    recentFirst = !recentFirst
                    viewSettings.courseSortRecent = recentFirst
                    render()
                }
            }
            listColumn.addView(sort)
            val add = Button(this).apply {
                text = "新增课程"
                setOnClickListener {
                    onSelected(null)
                    dialog.dismiss()
                }
            }
            listColumn.addView(add)

            val scroll = ScrollView(this)
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val anchors = LinkedHashMap<String, View>()
            val grouped = if (recentFirst) {
                linkedMapOf("" to courses)
            } else {
                courses.groupBy { courseStore.indexLetter(it.name) }
                    .toSortedMap(compareBy { if (it == "#") "0" else "1$it" })
                    .toMap(LinkedHashMap())
            }
            grouped.forEach { (letter, items) ->
                if (!recentFirst) {
                    val header = TextView(this).apply {
                        text = letter
                        textSize = 13f
                        setTextColor(getColorCompat(R.color.text_secondary))
                        setPadding(dp(8), dp(10), dp(8), dp(4))
                    }
                    content.addView(header)
                    anchors[letter] = header
                }
                items.forEach { course ->
                    val item = TextView(this).apply {
                        text = course.displayName
                        textSize = 15f
                        setTextColor(getColorCompat(R.color.text_primary))
                        setPadding(dp(10), dp(12), dp(8), dp(12))
                        minHeight = dp(48)
                        setBackgroundResource(android.R.drawable.list_selector_background)
                        setOnClickListener {
                            onSelected(course)
                            dialog.dismiss()
                        }
                    }
                    content.addView(item)
                }
            }
            if (courses.isEmpty()) {
                content.addView(TextView(this).apply {
                    text = "还没有可复用的课程，请点击“新增课程”"
                    setPadding(dp(10), dp(18), dp(8), dp(18))
                    setTextColor(getColorCompat(R.color.text_secondary))
                })
            }
            scroll.addView(content)
            listColumn.addView(scroll, LinearLayout.LayoutParams(0, dp(360), 1f))
            root.addView(listColumn, LinearLayout.LayoutParams(0, dp(420), 1f))

            if (!recentFirst) {
                val rail = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(3), dp(24), 0, 0)
                }
                val letters = listOf("#") + ('A'..'Z').map { it.toString() }
                letters.forEach { letter ->
                    rail.addView(TextView(this).apply {
                        text = letter
                        gravity = android.view.Gravity.CENTER
                        textSize = 10f
                        minWidth = dp(22)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(14)
                        )
                        val target = anchors[letter]
                        alpha = if (target == null) 0.35f else 1f
                        isEnabled = target != null
                        setOnClickListener {
                            target?.let { view -> scroll.post { scroll.smoothScrollTo(0, view.top) } }
                        }
                    })
                }
                root.addView(rail, LinearLayout.LayoutParams(dp(28), dp(420)))
            }
        }
        render()
        dialog.show()
    }

    /** 日期使用日历；时间使用可滚动的密码锁式小时/分钟选择，也支持直接输入。 */
    private fun showDateTimePicker(initial: Long? = null, onResult: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        if (initial != null && initial > 0L) cal.timeInMillis = initial
        else cal.add(Calendar.DAY_OF_YEAR, 1)                // 默认明天
        if (initial == null || initial <= 0L) {
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
        }
        cal.set(Calendar.SECOND, 0)
        DatePickerDialog(
            this,
            { _, y, m, d ->
                showTimeWheelDialog(y, m, d, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), onResult)
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimeWheelDialog(
        year: Int,
        month: Int,
        day: Int,
        initialHour: Int,
        initialMinute: Int,
        onResult: (Long) -> Unit
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(2))
        }
        root.addView(TextView(this).apply {
            text = "时间可上下滚动，也可以点击数字直接输入"
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        fun makePicker(max: Int, value: Int): NumberPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = max
            this.value = value.coerceIn(0, max)
            wrapSelectorWheel = true
            setFormatter { v -> "%02d".format(Locale.CHINA, v) }
            layoutParams = LinearLayout.LayoutParams(dp(86), dp(150))
        }
        val hourPicker = makePicker(23, initialHour)
        val minutePicker = makePicker(59, initialMinute)
        val syncing = booleanArrayOf(false)
        val pickerInputId = resources.getIdentifier("numberpicker_input", "id", "android")
        val hourInput = hourPicker.findViewById<EditText>(pickerInputId)
        val minuteInput = minutePicker.findViewById<EditText>(pickerInputId)
        fun configureInput(input: EditText, max: Int, next: EditText?) {
            input.inputType = InputType.TYPE_CLASS_NUMBER
            input.setSelectAllOnFocus(true)
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (syncing[0]) return
                    if (s?.length != 2) return
                    val value = s.toString().toIntOrNull()
                    if (value != null && value in 0..max) {
                        syncing[0] = true
                        if (input === hourInput) hourPicker.value = value else minutePicker.value = value
                        syncing[0] = false
                    }
                    next?.requestFocus()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        hourPicker.setOnValueChangedListener { _, _, new ->
            syncing[0] = true
            hourInput.setText("%02d".format(Locale.CHINA, new))
            syncing[0] = false
        }
        minutePicker.setOnValueChangedListener { _, _, new ->
            syncing[0] = true
            minuteInput.setText("%02d".format(Locale.CHINA, new))
            syncing[0] = false
        }
        configureInput(hourInput, 23, minuteInput)
        configureInput(minuteInput, 59, null)
        row.addView(hourPicker)
        row.addView(TextView(this).apply { text = ":"; textSize = 22f })
        row.addView(minutePicker)
        root.addView(row)
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择时间")
            .setView(root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hour = hourInput.text.toString().toIntOrNull() ?: hourPicker.value
                val minute = minuteInput.text.toString().toIntOrNull() ?: minutePicker.value
                if (hour !in 0..23 || minute !in 0..59) {
                    Toast.makeText(this, "请输入有效时间（小时 00-23，分钟 00-59）", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selected = Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }
                onResult(selected.timeInMillis)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 隐藏范围选择：说明文字和单选项必须放在同一个自定义视图中，避免 AlertDialog 同时设置 message/list 时丢失选项。 */
    private fun createHideScopeView(message: String, onPermanentChanged: (Boolean) -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        container.addView(TextView(this).apply {
            text = message
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
        })
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val current = RadioButton(this).apply {
            id = View.generateViewId()
            text = "仅当前时间范围"
            isChecked = true
        }
        val permanent = RadioButton(this).apply {
            id = View.generateViewId()
            text = "永久隐藏"
        }
        radioGroup.addView(current)
        radioGroup.addView(permanent)
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            onPermanentChanged(checkedId == permanent.id)
        }
        container.addView(radioGroup)
        return container
    }

    /** 自动作业隐藏 = 记入本地隐藏列表（学习通上的数据不动，可在设置里恢复） */
    private fun confirmHideAuto(hw: Homework) {
        var permanent = false
        val scopeView = createHideScopeView(
            "${hw.title}\n\n只是从列表里隐藏（学习通上的作业不受影响），可在设置里随时恢复。"
        ) { permanent = it }
        AlertDialog.Builder(this)
            .setTitle("隐藏这条作业？")
            .setView(scopeView)
            .setPositiveButton("隐藏") { _, _ ->
                hiddenStore.hide(
                    hw.accountId, hw.id, hw.title, hw.courseName, hw.endTime, hw.courseKey,
                    permanent = permanent,
                    scopeKey = currentScopeKey()
                )
                Toast.makeText(this, "已隐藏（可在设置里恢复）", Toast.LENGTH_SHORT).show()
                reloadVisibleHomework(hw.accountId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmHideCourse(homeworks: List<Homework>) {
        if (homeworks.isEmpty()) return
        var permanent = false
        val selected = homeworks.first()
        val courseName = selected.courseName
        val scopeView = createHideScopeView(
            "${courseName}\n\n将隐藏当前列表中的 ${homeworks.size} 条作业。永久隐藏时，该课程的其他本地作业也会一起隐藏。学习通上的内容不会被删除。"
        ) { permanent = it }
        AlertDialog.Builder(this)
            .setTitle("隐藏这个课程？")
            .setView(scopeView)
            .setPositiveButton("隐藏") { _, _ ->
                val source = if (permanent) {
                    (cache.load(selected.accountId) + ManualHomeworkStore(this).list(selected.accountId))
            .map(deadlineOverrides::apply)
            .map(::applyLocalCompletion)
                        .distinctBy(::homeworkKey)
                } else {
                    currentHomework
                }
                val targets = source.filter {
                    if (selected.courseKey.isNotBlank()) it.courseKey == selected.courseKey
                    else it.courseName == selected.courseName
                }
                targets.forEach { hw ->
                    if (permanent && hw.isManual()) {
                        ManualHomeworkStore(this).remove(hw.accountId, hw.id)
                    }
                    hiddenStore.hide(
                        hw.accountId, hw.id, hw.title, hw.courseName, hw.endTime, hw.courseKey,
                        permanent = permanent,
                        scopeKey = currentScopeKey()
                    )
                }
                reloadVisibleHomework(homeworks.first().accountId)
                Toast.makeText(this, "已隐藏课程作业", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按多选后的批量隐藏；仍支持仅当前范围或永久隐藏。 */
    private fun confirmHideSelected(homeworks: List<Homework>) {
        if (homeworks.isEmpty()) return
        var permanent = false
        val scopeView = createHideScopeView(
            "已选择 ${homeworks.size} 条作业\n\n只是从小笙列表隐藏，学习通上的作业不会被删除。"
        ) { permanent = it }
        AlertDialog.Builder(this)
            .setTitle("批量隐藏作业？")
            .setView(scopeView)
            .setPositiveButton("隐藏") { _, _ ->
                homeworks.forEach { hw ->
                    hiddenStore.hide(
                        hw.accountId, hw.id, hw.title, hw.courseName, hw.endTime, hw.courseKey,
                        source = if (hw.isManual()) HiddenStore.SOURCE_MANUAL else HiddenStore.SOURCE_AUTO,
                        permanent = permanent,
                        scopeKey = currentScopeKey()
                    )
                    if (permanent && hw.isManual()) ManualHomeworkStore(this).remove(hw.accountId, hw.id)
                }
                reloadVisibleHomework(homeworks.first().accountId)
                adapter.clearSelection()
                Toast.makeText(this, "已隐藏所选作业", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 手动作业隐藏 = 移出手动列表 + 记入隐藏记录（可在设置里恢复，恢复时写回手动列表） */
    private fun confirmHideManual(hw: Homework) {
        var permanent = false
        val scopeView = createHideScopeView(
            "${hw.title}\n\n隐藏后可在设置里的「已隐藏作业」中恢复。"
        ) { permanent = it }
        AlertDialog.Builder(this)
            .setTitle("隐藏这条作业？")
            .setView(scopeView)
            .setPositiveButton("隐藏") { _, _ ->
                if (permanent) ManualHomeworkStore(this).remove(hw.accountId, hw.id)
                hiddenStore.hide(
                    hw.accountId, hw.id, hw.title, hw.courseName, hw.endTime,
                    courseKey = hw.courseKey,
                    source = HiddenStore.SOURCE_MANUAL,
                    permanent = permanent,
                    scopeKey = currentScopeKey()
                )
                Toast.makeText(this, "已隐藏（可在设置里恢复）", Toast.LENGTH_SHORT).show()
                reloadVisibleHomework(hw.accountId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 恢复一条隐藏作业：手动作业先写回手动列表（自动作业直接放回列表即可） */
    private fun restoreHiddenItem(uid: String, item: HiddenStore.HiddenItem) {
        if (item.source == HiddenStore.SOURCE_MANUAL && item.permanent) {
            ManualHomeworkStore(this).add(uid, item.title, item.course, item.endTime, item.courseKey)
        }
        hiddenStore.restore(uid, item.id, item.courseKey)
    }

    /** 长按手动作业 → 彻底删除（不可恢复；普通隐藏走滑动按钮） */
    private fun confirmDeleteManual(hw: Homework) {
        AlertDialog.Builder(this)
            .setTitle("删除这条手动作业？")
            .setMessage("${hw.title}\n（彻底删除，不可恢复；提醒也会一并取消）")
            .setPositiveButton("删除") { _, _ ->
                ManualHomeworkStore(this).remove(hw.accountId, hw.id)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                reloadVisibleHomework(hw.accountId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 提醒节点设置 ----------

    /** 预设节点（分钟）→ 文案 */
    private val presetNodes = listOf(
        24 * 60 to "提前 1 天",
        12 * 60 to "提前 12 小时",
        3 * 60 to "提前 3 小时",
        60 to "提前 1 小时",
        30 to "提前 30 分钟",
        15 to "提前 15 分钟"
    )
    private val maxCustomReminderNodes = 8

    @SuppressLint("BatteryLife")
    private fun showRemindSettingDialog() {
        val current = ReminderScheduler.loadNodes(this).toMutableSet()
        val dialogView = layoutInflater.inflate(R.layout.dialog_remind, null)
        dialogView.findViewById<TextView>(R.id.tv_reminder_status).text = reminderSystemStatus()
        val exact = dialogView.findViewById<CheckBox>(R.id.cb_exact_alarm)
        exact.isChecked = ReminderScheduler.isExactMode(this)
        exact.setOnCheckedChangeListener { _, checked ->
            // 这个开关独立于弹窗的“确定”按钮：切换后立即落盘并重登记当前提醒，
            // 因此返回桌面、划掉最近任务或进入系统授权页都不会丢失用户选择。
            ReminderScheduler.saveExactMode(this, checked)
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarm = getSystemService(android.app.AlarmManager::class.java)
                if (alarm?.canScheduleExactAlarms() != true) {
                    Toast.makeText(this, "请在系统页面允许精确闹钟；未授权时会自动使用普通提醒", Toast.LENGTH_LONG).show()
                    openExactAlarmSettings()
                }
            }
            rescheduleCurrentAccount()
        }
        dialogView.findViewById<Button>(R.id.btn_test_notification).setOnClickListener {
            if (!com.zhizhou.xxtassistant.remind.ReminderNotifier.canNotify(this)) {
                Toast.makeText(this, "通知权限或系统通知总开关未开启，请先在系统设置中允许", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (ReminderNotifier.channelNeedsUserAttention(this)) {
                Toast.makeText(this, "作业提醒频道当前被静音或关闭，请在系统页面打开声音和锁屏显示后再测试", Toast.LENGTH_LONG).show()
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ReminderNotifier.CHANNEL_ID)
                    })
                }
                return@setOnClickListener
            }
            val button = dialogView.findViewById<Button>(R.id.btn_test_notification)
            button.isEnabled = false
            val exactScheduled = com.zhizhou.xxtassistant.remind.ReminderScheduler.scheduleTestNotification(
                this, currentUid ?: ""
            )
            Toast.makeText(
                this,
                if (exactScheduled) "请立即锁屏，10 秒后发送精确测试通知（含声音和振动）"
                else "精确闹钟未获系统授权，将使用可能延迟的测试通知；请先允许“闹钟与提醒”",
                Toast.LENGTH_LONG
            ).show()
            handler.postDelayed({ button.isEnabled = true }, 11_000L)
        }
        // 预设节点勾选状态
        val cbIds = mapOf(
            R.id.cb_1440 to 24 * 60, R.id.cb_720 to 12 * 60, R.id.cb_180 to 3 * 60,
            R.id.cb_60 to 60, R.id.cb_30 to 30, R.id.cb_15 to 15
        )
        cbIds.forEach { (id, min) ->
            dialogView.findViewById<CheckBox>(id).isChecked = min in current
        }

        // 自定义节点：当前已保存但不在预设里的
        val customSet = current.filter { min -> cbIds.values.none { it == min } }
            .toMutableList()
        val customBox = dialogView.findViewById<LinearLayout>(R.id.custom_nodes)

        fun renderCustomNodes() {
            customBox.removeAllViews()
            customSet.sorted().forEach { min ->
                val chip = TextView(this).apply {
                    text = "提前 ${formatMinutes(min)}"
                    contentDescription = "长按删除提前 ${formatMinutes(min)}"
                    setBackgroundResource(R.drawable.bg_spinner)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 13f
                }
                chip.setOnLongClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("删除这个提醒节点？")
                        .setMessage("提前 ${formatMinutes(min)}")
                        .setPositiveButton("删除") { _, _ ->
                            customSet.remove(min)
                            renderCustomNodes()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
                customBox.addView(chip)
                customBox.addView(TextView(this).apply {
                    text = "  "
                    textSize = 6f
                })
            }
        }
        renderCustomNodes()

        dialogView.findViewById<Button>(R.id.btn_add_custom).setOnClickListener {
            if (customSet.size >= maxCustomReminderNodes) {
                Toast.makeText(this, "自定义提醒最多保存 ${maxCustomReminderNodes} 个", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showCustomReminderPicker(customSet, cbIds.values.toSet(), ::renderCustomNodes)
        }

        AlertDialog.Builder(this)
            .setTitle("作业截止提醒节点")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val selected = mutableListOf<Int>()
                cbIds.forEach { (id, min) ->
                    if (dialogView.findViewById<CheckBox>(id).isChecked) selected.add(min)
                }
                selected += customSet
                ReminderScheduler.saveNodes(this, selected.toIntArray())
                ReminderScheduler.saveExactMode(this, exact.isChecked)
                rescheduleCurrentAccount()
                Toast.makeText(
                    this,
                    if (selected.isEmpty()) "已关闭所有作业提醒" else "提醒节点已更新",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 自定义提醒时间使用独立的小弹窗，避免把键盘输入框塞进主设置页。
     * 三个 NumberPicker 既支持上下滑动，也保留中间数字的手动输入能力。
     */
    private fun showCustomReminderPicker(
        customSet: MutableList<Int>,
        presetNodes: Set<Int>,
        onChanged: () -> Unit
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        val hint = TextView(this).apply {
            text = "上下滑动选择；也可以点击中间数字后手动输入"
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
        }
        root.addView(hint)

        fun createPicker(max: Int): NumberPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = max
            value = 0
            wrapSelectorWheel = false
            // NumberPicker 位于竖向 column 中，不能使用 0dp 宽度权重；
            // 否则它会被测量成零宽，滚轮数字完全不可见。
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(148)
            )
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            // NumberPicker 的输入框在不同系统版本上都是同名内部资源；找到后
            // 明确打开数字键盘，避免触摸时只能滚动、无法手动改值。
            val inputId = resources.getIdentifier("numberpicker_input", "id", "android")
            findViewById<EditText>(inputId)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                inputType = InputType.TYPE_CLASS_NUMBER
                setSelectAllOnFocus(true)
                setOnClickListener {
                    requestFocus()
                    selectAll()
                    post {
                        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                            ?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }

        val wheelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }

        fun addWheel(label: String, picker: NumberPicker) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            column.addView(picker)
            column.addView(TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 13f
            })
            wheelRow.addView(column)
        }
        val days = createPicker(7)
        val hours = createPicker(23)
        val minutes = createPicker(59).apply { value = 30 }
        addWheel("天", days)
        addWheel("小时", hours)
        addWheel("分钟", minutes)
        root.addView(wheelRow)

        val manualInput = EditText(this).apply {
            setHint("也可直接输入总分钟数，例如 90")
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }
        root.addView(manualInput)

        val preview = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 14f
            setPadding(0, dp(2), 0, dp(4))
        }
        fun updatePreview() {
            val total = days.value * 24 * 60 + hours.value * 60 + minutes.value
            preview.text = if (total == 0) "请选择一个大于 0 的提前时间" else "将添加：提前 ${formatMinutes(total)}"
        }
        val updateListener = NumberPicker.OnValueChangeListener { _, _, _ -> updatePreview() }
        days.setOnValueChangedListener(updateListener)
        hours.setOnValueChangedListener(updateListener)
        minutes.setOnValueChangedListener(updateListener)
        updatePreview()
        root.addView(preview)

        manualInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val typed = s?.toString()?.trim()?.toIntOrNull()
                if (!s.isNullOrBlank()) {
                    preview.text = if (typed == null || typed <= 0) {
                        "请输入大于 0 的分钟数"
                    } else {
                        "将添加：提前 ${formatMinutes(typed)}"
                    }
                } else {
                    updatePreview()
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("自定义提前时间")
            .setView(root)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 让 NumberPicker 先提交正在编辑的数字，再读取最终值。
                days.clearFocus()
                hours.clearFocus()
                minutes.clearFocus()
                val typed = manualInput.text.toString().trim()
                val total = if (typed.isNotEmpty()) {
                    typed.toIntOrNull() ?: -1
                } else {
                    days.value * 24 * 60 + hours.value * 60 + minutes.value
                }
                when {
                    total <= 0 || total > 7 * 24 * 60 ->
                        Toast.makeText(this, "请输入 1 分钟至 7 天之间的时间", Toast.LENGTH_SHORT).show()
                    total in customSet || total in presetNodes ->
                        Toast.makeText(this, "这个提醒时间已经在列表里了", Toast.LENGTH_SHORT).show()
                    else -> {
                        if (customSet.size >= maxCustomReminderNodes) {
                            Toast.makeText(this, "自定义提醒最多保存 ${maxCustomReminderNodes} 个", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        customSet.add(total)
                        onChanged()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * 把系统层面的提醒开关集中显示在设置页，避免用户只看到“已登记”却不知道
     * 通知总开关、通知频道、精确闹钟或电池优化是哪一项拦截了后台提醒。
     */
    private fun reminderSystemStatus(): String {
        val notification = if (ReminderNotifier.canNotify(this)) "通知已允许" else "通知未允许"
        val channel = if (ReminderNotifier.channelNeedsUserAttention(this)) "提醒频道需检查声音/锁屏" else "提醒频道正常"
        val exact = when {
            !ReminderScheduler.isExactMode(this) -> "普通提醒模式"
            ReminderScheduler.canScheduleExact(this) -> "精确闹钟已授权"
            else -> "精确闹钟待授权"
        }
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val power = getSystemService(android.os.PowerManager::class.java)
            if (power?.isIgnoringBatteryOptimizations(packageName) == true) "电池优化已豁免"
            else "电池优化可能延迟后台提醒"
        } else "电池优化不适用"
        return "$notification · $channel\n$exact · $battery"
    }

    /** 把分钟数转成好读的文案：1440 → "1 天"，90 → "90 分钟" */
    private fun formatMinutes(min: Int): String = when {
        min % 1440 == 0 -> "${min / 1440} 天"
        min % 60 == 0 -> "${min / 60} 小时"
        else -> "$min 分钟"
    }

}
