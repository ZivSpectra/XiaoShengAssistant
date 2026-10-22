package com.zhizhou.xxtassistant.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * 视图设置：记录用户对列表展示方式的偏好（只存手机本地）。
 *
 *  - 显示模式：默认列表（全部作业混排）/ 按课程分组（点开课程查看）
 *  - 列表布局：单列 / 双列
 */
class ViewSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xxt_view", Context.MODE_PRIVATE)

    companion object {
        const val MODE_FLAT = 0      // 默认：全部作业混排
        const val MODE_GROUP = 1     // 按课程分组
        const val LAYOUT_SINGLE = 0  // 单列
        const val LAYOUT_DOUBLE = 1  // 双列

        private const val KEY_MODE = "view_mode"
        private const val KEY_LAYOUT = "view_layout"
        private const val KEY_FILTER_START = "filter_start_day"
        private const val KEY_FILTER_END = "filter_end_day"
        private const val KEY_FILTER_ALL = "filter_all"
        private const val KEY_FILTER_RANGE_CUSTOMIZED = "filter_range_customized"
        private const val KEY_COURSE_SORT_RECENT = "course_sort_recent"

        fun startOfDay(time: Long = System.currentTimeMillis()): Long =
            Calendar.getInstance().apply {
                timeInMillis = time
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        fun endOfDay(time: Long): Long =
            Calendar.getInstance().apply {
                timeInMillis = time
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis
    }

    var mode: Int
        get() = prefs.getInt(KEY_MODE, MODE_FLAT)
        set(v) = prefs.edit().putInt(KEY_MODE, v).apply()

    var layout: Int
        get() = prefs.getInt(KEY_LAYOUT, LAYOUT_SINGLE)
        set(v) = prefs.edit().putInt(KEY_LAYOUT, v).apply()

    var filterStartDay: Long
        get() = prefs.getLong(KEY_FILTER_START, 0L).takeIf { it > 0L } ?: startOfDay()
        set(v) = prefs.edit().putLong(KEY_FILTER_START, startOfDay(v)).apply()

    var filterEndDay: Long
        get() = prefs.getLong(KEY_FILTER_END, 0L).takeIf { it > 0L }
            ?: startOfDay(System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L)
        set(v) = prefs.edit().putLong(KEY_FILTER_END, startOfDay(v)).apply()

    var filterAll: Boolean
        get() = prefs.getBoolean(KEY_FILTER_ALL, false)
        set(v) = prefs.edit().putBoolean(KEY_FILTER_ALL, v).apply()

    /** True after the user explicitly chooses a date range. */
    var filterRangeCustomized: Boolean
        get() = prefs.getBoolean(
            KEY_FILTER_RANGE_CUSTOMIZED,
            prefs.contains(KEY_FILTER_START) || prefs.contains(KEY_FILTER_END)
        )
        set(v) = prefs.edit().putBoolean(KEY_FILTER_RANGE_CUSTOMIZED, v).apply()

    /** 课程选择器排序：true=最近使用，false=拼音首字母。 */
    var courseSortRecent: Boolean
        get() = prefs.getBoolean(KEY_COURSE_SORT_RECENT, true)
        set(v) = prefs.edit().putBoolean(KEY_COURSE_SORT_RECENT, v).apply()
}
