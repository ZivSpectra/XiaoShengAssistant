package com.zhizhou.xxtassistant.data

data class FocusSettings(
    val enabled: Boolean = false,
    val entertainmentPackages: Set<String> = emptySet(),
    val studyPackage: String? = null,
    val dailyLimitMinutes: Int = 30
) {
    val configured: Boolean
        get() = entertainmentPackages.isNotEmpty() &&
            !studyPackage.isNullOrBlank() && studyPackage !in entertainmentPackages
}

object FocusLimitPolicy {
    fun reached(usedMillis: Long, limitMinutes: Int): Boolean =
        limitMinutes > 0 && usedMillis >= limitMinutes.toLong() * 60_000L
}

class FocusRedirectGate {
    private var sourcePackage: String? = null
    private var targetPackage: String? = null
    private var leftSource = false

    val isPending: Boolean get() = targetPackage != null

    fun begin(source: String, target: String): Boolean {
        if (isPending) return false
        sourcePackage = source
        targetPackage = target
        leftSource = false
        return true
    }

    fun onForeground(packageName: String, isHome: Boolean = false) {
        if (!isPending) return
        when {
            packageName == targetPackage -> clear()
            packageName == sourcePackage && leftSource -> clear()
            packageName != sourcePackage && !isHome -> leftSource = true
        }
    }

    fun reset() = clear()

    private fun clear() {
        sourcePackage = null
        targetPackage = null
        leftSource = false
    }
}
