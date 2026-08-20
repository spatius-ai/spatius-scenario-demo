package ai.spatialwalk.scenes

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Height split between the question text area and the options area; together they take
 * all the height available in the left content area. */
private const val StemWeight = 0.4f
private const val OptionsWeight = 0.6f

/** Gap between options. */
private val OptionGap = 10.dp

/** Lower and upper bounds for the question text's auto-sized font. */
private val StemMinFontSize = 13.sp
private val StemMaxFontSize = 26.sp

/**
 * Left content area: question text on top, multiple-choice options below.
 *
 * Height is split by fixed ratio (question text [StemWeight] / options [OptionsWeight]),
 * and neither block scrolls:
 * - once the options area's height is fixed, the N options divide it evenly, so row
 *   height is constant and everything always fits on one screen;
 * - once the question area's height is fixed, the text uses `autoSize` to scale within
 *   the range and fill it, so long questions shrink automatically.
 *
 * Options have three visual states: unselected, selected and correct, selected and wrong.
 * Nothing gives the answer away before it is answered; once answered, the correct option
 * is highlighted too so it can be walked through side by side.
 */
@Composable
fun QuestionPanel(
    question: Question,
    questionIndex: Int,
    totalCount: Int,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelHeader(
            topic = question.topic,
            questionIndex = questionIndex,
            totalCount = totalCount,
            canGoPrev = questionIndex > 0,
            canGoNext = questionIndex < totalCount - 1,
            onPrev = onPrev,
            onNext = onNext,
        )
        QuestionStem(
            question = question,
            modifier = Modifier.weight(StemWeight),
        )
        Column(
            modifier = Modifier.weight(OptionsWeight),
            verticalArrangement = Arrangement.spacedBy(OptionGap),
        ) {
            question.options.forEachIndexed { index, option ->
                OptionRow(
                    label = question.optionLabel(index),
                    text = option,
                    state = optionStateOf(index, selectedIndex, question.answerIndex),
                    onClick = { onSelect(index) },
                    // Each option takes an equal share of the options area; row height
                    // comes from the ratio, not from padding.
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Panel top row: topic tag on the left, the "previous · progress · next" pager group on
 * the right.
 *
 * All on one row — giving each its own line would eat into the question text's height.
 * The progress indicator sits between the two buttons; the three together form one pager
 * control, and their arrangement carries that meaning.
 */
@Composable
private fun PanelHeader(
    topic: String,
    questionIndex: Int,
    totalCount: Int,
    canGoPrev: Boolean,
    canGoNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = topic,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onPrev,
            enabled = canGoPrev,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(text = Localization.t.prevQuestion, fontSize = 14.sp, maxLines = 1)
        }
        Text(
            text = "${questionIndex + 1}/$totalCount",
            fontSize = 13.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onNext,
            enabled = canGoNext,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(text = Localization.t.nextQuestion, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/**
 * Question area: the question text. The topic tag has moved into [PanelHeader].
 *
 * The text uses [BasicText]'s autoSize to scale between [StemMinFontSize] and
 * [StemMaxFontSize], so long questions shrink to fill the fixed-height question area
 * without overflowing or scrolling.
 */
@Composable
private fun QuestionStem(question: Question, modifier: Modifier = Modifier) {
    BasicText(
        text = question.stem,
        modifier = modifier.fillMaxWidth(),
        style = TextStyle(
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 1.45.em,
        ),
        autoSize = TextAutoSize.StepBased(
            minFontSize = StemMinFontSize,
            maxFontSize = StemMaxFontSize,
            stepSize = 0.5.sp,
        ),
    )
}

private enum class OptionState { Idle, Correct, Wrong }

/**
 * Always [OptionState.Idle] before the question is answered.
 *
 * A wrong answer only marks the chosen option red and **does not reveal the correct
 * answer** — the student can change the selection and retry, and marking the right one
 * green early would make retrying pointless. The green mark only appears on a correct
 * answer.
 */
private fun optionStateOf(index: Int, selectedIndex: Int?, answerIndex: Int): OptionState = when {
    selectedIndex == null || index != selectedIndex -> OptionState.Idle
    index == answerIndex -> OptionState.Correct
    else -> OptionState.Wrong
}

@Composable
private fun OptionRow(
    label: String,
    text: String,
    state: OptionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val correct = Color(0xFF2E9E5B)
    val wrong = Color(0xFFD64545)
    val accent = when (state) {
        OptionState.Idle -> MaterialTheme.colorScheme.outlineVariant
        OptionState.Correct -> correct
        OptionState.Wrong -> wrong
    }
    val containerColor by animateColorAsState(
        targetValue = when (state) {
            OptionState.Idle -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            OptionState.Correct -> correct.copy(alpha = 0.12f)
            OptionState.Wrong -> wrong.copy(alpha = 0.12f)
        },
        label = "optionContainer",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    if (state == OptionState.Idle) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        accent
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (state == OptionState.Idle) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.White
                },
            )
        }
        BasicText(
            text = text,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 2,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 12.sp,
                maxFontSize = 18.sp,
                stepSize = 0.5.sp,
            ),
        )
    }
}
