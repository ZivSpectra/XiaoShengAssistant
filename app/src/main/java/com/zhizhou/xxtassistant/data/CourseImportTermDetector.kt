package com.zhizhou.xxtassistant.data

/**
 * Extracts the currently selected teaching term from an academic-system page.
 *
 * A page often contains every historical term in a selector, so callers must
 * pass the selected option texts collected from the live DOM when available.
 * We only return a term when the evidence identifies exactly one value; this
 * prevents silently putting a table into the wrong local semester.
 */
object CourseImportTermDetector {
    private val selectedOptionPattern = Regex(
        "<option\\b[^>]*selected(?:\\s*=\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s>]+))?[^>]*>(.*?)</option>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val labelPattern = Regex(
        "(?<!\\d)(20\\d{2})\\s*[-－—至/]\\s*(20\\d{2})\\s*(?:学年)?\\s*(?:第\\s*)?([12一二]|春|秋)(?:季)?\\s*学期",
        RegexOption.IGNORE_CASE
    )
    private val valuePattern = Regex(
        "(?<!\\d)(20\\d{2})\\s*[-－—至/]\\s*(20\\d{2})\\s*[-/]\\s*([12])(?:\\D|$)",
        RegexOption.IGNORE_CASE
    )
    private val yearPairPattern = Regex("(?<!\\d)(20\\d{2})\\s*[-－—至/]\\s*(20\\d{2})(?!\\d)")
    private val semesterOnlyPattern = Regex("^\\s*(?:第\\s*)?([12一二]|春|秋)\\s*(?:学期)?\\s*$", RegexOption.IGNORE_CASE)

    fun detect(pageHtml: String, selectedTerms: List<String> = emptyList()): String? {
        if (pageHtml.isBlank()) return null
        val optionTexts = selectedOptionPattern.findAll(pageHtml)
            .map { decodePageText(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .toList()
        val selectedTexts = selectedTerms.map(::decodePageText).filter { it.isNotBlank() }
        val selectedMatches = selectedTexts.flatMap(::findTerms).distinct().let { matches ->
            if (matches.isNotEmpty()) matches else combineSelectedParts(selectedTexts)
        }
        if (selectedMatches.size == 1) return selectedMatches.single()

        // If selectedTerms was supplied but it names multiple different terms,
        // refusing to guess is safer than taking the first selector on the page.
        if (selectedTerms.isNotEmpty() && selectedMatches.size > 1) return null

        val optionMatches = optionTexts.flatMap(::findTerms).distinct().let { matches ->
            if (matches.isNotEmpty()) matches else combineSelectedParts(optionTexts)
        }
        if (optionMatches.size == 1) return optionMatches.single()
        if (optionMatches.size > 1) return null

        val pageMatches = findTerms(decodePageText(pageHtml)).distinct()
        return pageMatches.singleOrNull()
    }

    private fun findTerms(text: String): List<String> = (
        labelPattern.findAll(text).map {
            "${it.groupValues[1]}-${it.groupValues[2]}-${semesterNumber(it.groupValues[3])}"
        } + valuePattern.findAll(text).map {
            "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}"
        }
    ).distinct().toList()

    /** 正方把学年和学期拆成两个 select；把两个当前选项合并成一个 key。 */
    private fun combineSelectedParts(values: List<String>): List<String> {
        val years = values.flatMap { value ->
            yearPairPattern.findAll(value).map { "${it.groupValues[1]}-${it.groupValues[2]}" }.toList()
        }.distinct()
        val semesters = values.mapNotNull { value ->
            semesterOnlyPattern.matchEntire(value)?.groupValues?.getOrNull(1)?.let(::semesterNumber)
        }.distinct()
        return if (years.size == 1 && semesters.size == 1) {
            listOf("${years.single()}-${semesters.single()}")
        } else {
            emptyList()
        }
    }

    private fun semesterNumber(value: String): String = when (value.lowercase()) {
        "一" -> "1"
        "二" -> "2"
        "春" -> "2"
        "秋" -> "1"
        else -> value
    }

    private fun decodePageText(value: String): String = value
        .replace(Regex("<script.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<style.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&#160;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
        .trim()
}
