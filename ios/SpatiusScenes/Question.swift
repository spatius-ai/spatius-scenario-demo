import Foundation

/// A question shown in the content area on the left.
///
/// The demo implements multiple choice only, with the data hard-coded in
/// `sampleQuestions(for:)`. To support other formats — a free-response area, say —
/// turn this into an enum here.
struct Question {
    /// The question text
    let stem: String
    /// Option texts, in A/B/C/D order
    let options: [String]
    /// Index of the correct option
    let answerIndex: Int
    /// The topic this question belongs to, shown at the top
    let topic: String

    /// Option labels: A, B, C…
    func optionLabel(_ index: Int) -> String {
        String(UnicodeScalar(UInt8(65 + index)))
    }

    /// What gets read aloud when a question appears: number + stem + each option.
    ///
    /// This text goes straight to the avatar to be spoken (it does not pass through
    /// the LLM), so it has to read well: options are separated so they are not run
    /// together into one sentence. The phrasing differences (Chinese 「第一题」 vs
    /// English "Question 1") live in Localization; this only picks the current
    /// language's version.
    ///
    /// The leading question number is spoken but not displayed — the UI already shows
    /// `N/total`, so repeating it in the stem would be redundant; speech has no such
    /// context, so it needs to be announced.
    ///
    /// - Parameter number: question number, starting at 1
    @MainActor
    func readAloudText(number: Int) -> String {
        Localization.shared.t.readAloud(stem, options, number)
    }
}

/// Hard-coded question data for the demo.
///
/// There is one question bank per language: switching languages swaps whole questions
/// rather than wrapping a Chinese stem in an English shell. The count and the answer
/// indices match across both, so answers already given do not shift when switching.
/// Same content as the Web client.
func sampleQuestions(for lang: Lang) -> [Question] {
    switch lang {
    case .zh:
        return [
            Question(
                stem: "小明买了 3 本练习册和 1 支钢笔，一共花了 47 元。已知钢笔的单价是 11 元，那么每本练习册多少元？",
                options: ["10 元", "12 元", "14 元", "16 元"],
                answerIndex: 1,
                topic: "数与代数"
            ),
            Question(
                stem: "一个三角形的两个内角分别是 55° 和 65°，那么第三个内角的度数是多少？",
                options: ["50°", "60°", "70°", "80°"],
                answerIndex: 1,
                topic: "图形与几何"
            ),
        ]
    case .en:
        return [
            Question(
                stem: "Sam bought 3 workbooks and 1 pen for $47 in total. The pen costs $11. How much does each workbook cost?",
                options: ["$10", "$12", "$14", "$16"],
                answerIndex: 1,
                topic: "Numbers and algebra"
            ),
            Question(
                stem: "Two interior angles of a triangle measure 55° and 65°. What is the third angle?",
                options: ["50°", "60°", "70°", "80°"],
                answerIndex: 1,
                topic: "Shape and geometry"
            ),
        ]
    }
}
