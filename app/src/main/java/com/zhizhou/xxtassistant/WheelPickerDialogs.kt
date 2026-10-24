package com.zhizhou.xxtassistant

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import java.util.Calendar

/** Compact wheel controls backed by NumberPicker, whose center value is also editable by keyboard. */
internal object WheelPickerDialogs {
    fun date(context: Context, initial: Long, onSelected: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initial }
        val year = picker(context, 2000, 2100, calendar.get(Calendar.YEAR))
        val month = picker(context, 1, 12, calendar.get(Calendar.MONTH) + 1, pad = true)
        val day = picker(context, 1, calendar.getActualMaximum(Calendar.DAY_OF_MONTH), calendar.get(Calendar.DAY_OF_MONTH))
        month.setOnValueChangedListener { _, _, newMonth ->
            val maxDay = Calendar.getInstance().getActualMaximumFor(calendar.get(Calendar.YEAR), newMonth)
            day.maxValue = maxDay
            if (day.value > maxDay) day.value = maxDay
        }
        val content = columns(context, listOf("年", "月", "日"), year, month, day)
        AlertDialog.Builder(context)
            .setTitle("选择开学日期")
            .setView(content)
            .setPositiveButton("确定") { _, _ ->
                val selected = Calendar.getInstance().apply {
                    clear()
                    set(year.value, month.value - 1, day.value)
                }
                onSelected(selected.timeInMillis)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun time(context: Context, hour: Int, minute: Int, onSelected: (Int, Int) -> Unit) {
        val hours = picker(context, 0, 23, hour.coerceIn(0, 23), pad = true)
        val minutes = picker(context, 0, 59, minute.coerceIn(0, 59), pad = true)
        AlertDialog.Builder(context)
            .setTitle("设置提醒时间")
            .setView(columns(context, listOf("时", "分"), hours, minutes))
            .setPositiveButton("确定") { _, _ -> onSelected(hours.value, minutes.value) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun picker(context: Context, min: Int, max: Int, value: Int, pad: Boolean = false) = NumberPicker(context).apply {
        minValue = min
        maxValue = max
        this.value = value.coerceIn(min, max)
        if (pad) setFormatter { number -> "%02d".format(number) }
        wrapSelectorWheel = false
        descendantFocusability = NumberPicker.FOCUS_AFTER_DESCENDANTS
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun columns(context: Context, labels: List<String>, vararg pickers: NumberPicker) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(20, 8, 20, 0)
        pickers.forEachIndexed { index, picker ->
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(android.widget.TextView(context).apply {
                    text = labels[index]
                    gravity = android.view.Gravity.CENTER
                })
                picker.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(picker)
            }
            addView(column)
        }
    }

    private fun Calendar.getActualMaximumFor(year: Int, month: Int): Int =
        Calendar.getInstance().apply { clear(); set(Calendar.YEAR, year); set(Calendar.MONTH, month - 1) }
            .getActualMaximum(Calendar.DAY_OF_MONTH)
}
