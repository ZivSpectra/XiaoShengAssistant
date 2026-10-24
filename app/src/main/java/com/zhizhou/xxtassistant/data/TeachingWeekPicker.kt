package com.zhizhou.xxtassistant.data

/** The compact teaching-week picker is intentionally limited to five rows. */
object TeachingWeekPicker {
    const val MIN_WEEK = 1
    const val MAX_WEEK = 25
    const val COLUMN_COUNT = 5
    val rows: List<List<Int>> = (MIN_WEEK..MAX_WEEK).chunked(COLUMN_COUNT)

    fun clamp(week: Int): Int = week.coerceIn(MIN_WEEK, MAX_WEEK)
    fun next(week: Int): Int = clamp(week + 1)
    fun previous(week: Int): Int = clamp(week - 1)

    fun isTodayColumn(displayedWeek: Int, currentWeek: Int, columnDay: Int, todayDay: Int): Boolean =
        displayedWeek == currentWeek && columnDay == todayDay

    /** A left swipe advances a week; a right swipe returns to the previous week. */
    fun afterHorizontalSwipe(week: Int, dragAmountPx: Float, thresholdPx: Float = 72f): Int = when {
        dragAmountPx <= -thresholdPx -> next(week)
        dragAmountPx >= thresholdPx -> previous(week)
        else -> clamp(week)
    }
}
