package ai.spatialwalk.scenes

/**
 * A question shown in the content area on the left.
 *
 * This demo only implements multiple choice; the data is hardcoded in [sampleQuestions].
 * To support other shapes later (a free-answer area, say), turn this into a sealed class
 * and have the content area dispatch to a different Composable per type.
 */
data class Question(
    /** Question text */
    val stem: String,
    /** Option texts, in A/B/C/D order */
    val options: List<String>,
    /** Index of the correct option */
    val answerIndex: Int,
    /** Topic this question belongs to, shown above the question text */
    val topic: String,
) {
    /** Option labels: A, B, C… */
    fun optionLabel(index: Int): String = ('A' + index).toString()

    /**
     * What gets read aloud when the question appears: number + question text + each option.
     *
     * This text goes straight to the avatar to speak (it does not pass through the LLM),
     * so it has to read naturally: break between options so they aren't run together into
     * one sentence. The per-language phrasing (Chinese "第一题", English "Question 1")
     * lives in [Strings.readAloud]; this only picks the current language's version.
     *
     * The leading question number is spoken but not displayed — the UI already shows
     * `N/total` progress, so repeating it in the question text would be redundant; speech
     * has no such context, so it has to be announced.
     *
     * @param number question number, 1-based
     */
    fun readAloudText(number: Int): String =
        Localization.t.readAloud(stem, options, number)
}

/**
 * Hardcoded question data for the demo.
 *
 * One question bank per language: switching languages swaps whole questions rather than
 * wrapping a Chinese question text in an English shell. The count and the answer indices
 * match across both so already-answered state doesn't get misaligned on switch. Same
 * content as the Web and iOS clients.
 */
fun sampleQuestions(lang: Lang): List<Question> = when (lang) {
    Lang.ZH -> listOf(
        Question(
            topic = "数与代数",
            stem = "小明买了 3 本练习册和 1 支钢笔，一共花了 47 元。已知钢笔的单价是 11 元，那么每本练习册多少元？",
            options = listOf("10 元", "12 元", "14 元", "16 元"),
            answerIndex = 1,
        ),
        Question(
            topic = "图形与几何",
            stem = "一个三角形的两个内角分别是 55° 和 65°，那么第三个内角的度数是多少？",
            options = listOf("50°", "60°", "70°", "80°"),
            answerIndex = 1,
        ),
    )

    Lang.EN -> listOf(
        Question(
            topic = "Numbers and algebra",
            stem = "Sam bought 3 workbooks and 1 pen for \$47 in total. The pen costs \$11. How much does each workbook cost?",
            options = listOf("\$10", "\$12", "\$14", "\$16"),
            answerIndex = 1,
        ),
        Question(
            topic = "Shape and geometry",
            stem = "Two interior angles of a triangle measure 55° and 65°. What is the third angle?",
            options = listOf("50°", "60°", "70°", "80°"),
            answerIndex = 1,
        ),
    )
}
