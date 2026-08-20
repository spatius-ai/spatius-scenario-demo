import SwiftUI

/// Height split between the stem area and the options area; together they take up all
/// of the available height in the left content area.
private let stemRatio: CGFloat = 0.4
private let optionsRatio: CGFloat = 0.6

/// The three visual states of an option.
private enum OptionState {
    case idle, correct, wrong
}

/// The left content area: header row + stem + options.
///
/// Height is split by fixed ratios and neither block scrolls:
/// - once the options area's height is fixed, the N options divide it evenly, so row
///   height is constant and everything always fits on one screen;
/// - the stem area's height is fixed and the font scales to fill it, so long questions
///   shrink automatically.
struct QuestionPanel: View {
    let question: Question
    let questionIndex: Int
    let totalCount: Int
    let selectedIndex: Int?
    let onSelect: (Int) -> Void
    let onPrev: () -> Void
    let onNext: () -> Void

    var body: some View {
        GeometryReader { geo in
            VStack(alignment: .leading, spacing: 12) {
                header
                Text(question.stem)
                    .font(.system(size: 22, weight: .medium))
                    .lineSpacing(6)
                    // The stem gets a fixed share of the height; overly long text
                    // shrinks instead of stretching the layout.
                    .minimumScaleFactor(0.5)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(height: (geo.size.height - 60) * stemRatio, alignment: .top)

                VStack(spacing: 10) {
                    ForEach(question.options.indices, id: \.self) { index in
                        optionRow(index: index)
                    }
                }
                .frame(height: (geo.size.height - 60) * optionsRatio)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 20))
        }
    }

    /// Header row: topic + the "previous · n/N · next" pager.
    ///
    /// Going back uses the system gesture rather than spending a button on it; the
    /// progress sits between the two buttons so the three read as one pager — their
    /// arrangement carries the meaning.
    private var header: some View {
        HStack(spacing: 10) {
            Text(question.topic)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Color.accentColor)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Color.accentColor.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .lineLimit(1)
            Spacer()
            Button(Localization.shared.t.prevQuestion, action: onPrev)
                .font(.system(size: 14))
                .disabled(questionIndex <= 0)
            Text("\(questionIndex + 1)/\(totalCount)")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
            Button(Localization.shared.t.nextQuestion, action: onNext)
                .font(.system(size: 14))
                .disabled(questionIndex >= totalCount - 1)
        }
    }

    private func optionRow(index: Int) -> some View {
        let state = optionState(index)
        let accent: Color = {
            switch state {
            case .idle: return Color(.separator)
            case .correct: return Color(red: 0.18, green: 0.62, blue: 0.36)
            case .wrong: return Color(red: 0.84, green: 0.27, blue: 0.27)
            }
        }()

        return Button {
            onSelect(index)
        } label: {
            HStack(spacing: 12) {
                Text(question.optionLabel(index))
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(state == .idle ? Color.primary : .white)
                    .frame(width: 26, height: 26)
                    .background(state == .idle ? Color(.systemBackground) : accent)
                    .clipShape(Circle())
                Text(question.options[index])
                    .font(.system(size: 17))
                    .foregroundStyle(Color.primary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                Spacer()
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(state == .idle ? Color(.tertiarySystemFill) : accent.opacity(0.12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(accent.opacity(0.6), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    /// Everything is idle until an answer is given.
    ///
    /// A wrong answer only marks the chosen option red and **does not reveal the
    /// correct one** — students can change their pick and try again, and marking the
    /// right option green would make retrying pointless.
    private func optionState(_ index: Int) -> OptionState {
        guard let selected = selectedIndex, index == selected else { return .idle }
        return index == question.answerIndex ? .correct : .wrong
    }
}
