package ai.spatialwalk.scenes

import ai.spatialwalk.scenes.rtc.AvatarRtcSession
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Minimum gap between two speak requests, to avoid hammering the backend. */
private const val SpeakIntervalMs = 2_000L

/**
 * Encouragement after a wrong answer, picked at random.
 *
 * Not a fixed line: the same question can be re-answered repeatedly, and hearing the
 * exact same sentence every time breaks the illusion.
 */

/** Gap between the two video tiles, kept in sync with VideoPanel's own value. */
private val VideoGap = 12.dp

/** Upper bound on the tile's side length, so it doesn't eat too much space on large screens. */
private val MaxVideoTileSize = 360.dp

/**
 * Classroom main screen: content area on the left, video area on the right, as a
 * two-column landscape layout.
 *
 * The left side takes all the remaining width; the right video column has no fixed width
 * and lets [VideoPanel] derive the square's side from the available height and size
 * itself — in landscape it is the height that is the tight constraint.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ClassroomScreen(onExit: () -> Unit) {
    var questionIndex by remember { mutableIntStateOf(0) }
    // One recorded answer per question so paging back and forth loses nothing: questions
    // can be flipped through freely and previously chosen options stay highlighted.
    val t = Localization.t
    // The question set for the current language. Switching languages swaps the whole set;
    // the question count and answer indices match across both, so already-answered state
    // does not get misaligned.
    val questions = sampleQuestions(Localization.lang)
    val selections = remember { mutableStateListOf<Int?>().apply { repeat(questions.size) { add(null) } } }
    val selectedIndex = selections[questionIndex]
    val question = questions[questionIndex]

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The session is created and held here: AvatarStage is rebuilt on rotation, so a
    // session living inside it would go away with it.
    val session = remember { AvatarRtcSession(context, scope) }

    // Free talk: the student opens the mic and talks to the teacher. The button toggles —
    // tap again to hang up.
    var freeTalk by remember { mutableStateOf(false) }

    // The student can turn off their own video. This only affects the local preview — the
    // student's video is never sent upstream anyway.
    var cameraOn by remember { mutableStateOf(true) }

    /** All answers correct — the gate for free talk. */
    val allCorrect = questions.indices.all { selections[it] == questions[it].answerIndex }

    // Going back means switching teachers; use the system back gesture/button rather than
    // spending an on-screen button on it.
    BackHandler(onBack = onExit)

    // Stop the session only on a real exit from the classroom (rotation does not trigger
    // this, since the key is Unit).
    DisposableEffect(Unit) {
        onDispose {
            // rememberCoroutineScope won't do: it is already cancelled by the time we
            // leave the composition tree, so the stop request never goes out and the agent
            // keeps billing. Use an independent scope to carry this cleanup through.
            CoroutineScope(Dispatchers.IO).launch { session.stop() }
        }
    }

    /**
     * Pending text to speak, **keeping only the newest**.
     *
     * Rapid taps do not queue up: a later text overwrites the previous one and everything
     * in between is dropped — queueing would make the avatar read out stale feedback one
     * item at a time, while the student only cares about their last answer.
     */
    val pendingSpeech = remember { MutableStateFlow<String?>(null) }

    fun speak(text: String) {
        pendingSpeech.value = text
    }

    // A single send loop: send as soon as there is content, and only wait out the
    // remainder when less than [SpeakIntervalMs] has passed since the last send. The first
    // text after an idle period goes out immediately with no pointless wait; anything
    // arriving during the wait just overwrites the slot.
    LaunchedEffect(Unit) {
        var lastSentAt = 0L
        while (true) {
            // Suspend until there is something to send, to avoid spinning.
            pendingSpeech.first { it != null }

            val elapsed = System.currentTimeMillis() - lastSentAt
            if (elapsed < SpeakIntervalMs) delay(SpeakIntervalMs - elapsed)

            // It may have been overwritten again during the wait, so take the value as of now.
            val text = pendingSpeech.value ?: continue
            pendingSpeech.value = null
            lastSentAt = System.currentTimeMillis()
            session.speak(text)
        }
    }

    // Read the current question once the avatar is connected, and again when the question
    // changes.
    //
    // Questions already answered correctly are not read again — paging back to them is
    // just reviewing, and re-reading would interrupt the current conversation instead.
    // selections is deliberately not a dependency: at the moment an answer is correct the
    // click branch plays the feedback, and this should not fire a second time.
    var connected by remember { mutableStateOf(false) }
    LaunchedEffect(connected, questionIndex) {
        if (!connected) return@LaunchedEffect
        if (selections[questionIndex] != question.answerIndex) {
            speak(question.readAloudText(questionIndex + 1))
        } else {
            // Correctly answered questions are not re-read, but the interrupt is
            // mandatory: otherwise the previous question's text keeps being read to the
            // end, out of sync with the content the UI has already moved away from.
            // Clear the pending slot too, so the throttling loop doesn't re-send the very
            // line that was just interrupted.
            pendingSpeech.value = null
            session.interrupt()
        }
    }

    // The camera is for the student's own video. The record-audio permission is only
    // requested when free talk starts — ask at the point of use, rather than throwing two
    // dialogs at the student the moment they enter the classroom.
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }
    val cameraGranted = cameraPermission.status.isGranted

    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    // Once denied, stop prompting automatically (the system only gives one chance anyway)
    // and let the button's copy point the student at system settings.
    var micPrompted by remember { mutableStateOf(false) }
    val micGranted = micPermission.status.isGranted
    val micDenied = freeTalk && micPrompted && !micGranted
    // Conditions for publishing the mic: free talk started and permission granted. On the
    // session side AvatarStage does it once the connection is ready.
    val micEnabled = freeTalk && micGranted

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        // No top bar, so all the height goes to the content and video areas; the question
        // number and "next" controls fold into the left panel. Landscape puts content left
        // and video right; portrait puts video on top and content below.
        //
        // Hoisting both panels into Composable variables lets the two layouts reuse the
        // same declaration. Compose keeps the subtree identity from that, so rotation does
        // not rebuild AvatarStage and the session is not interrupted.
        val questionPanel: @Composable (Modifier) -> Unit = { panelModifier ->
            QuestionPanel(
                question = question,
                questionIndex = questionIndex,
                totalCount = questions.size,
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    // A wrong answer can be changed and retried — that is the core
                    // interaction of a tutoring scene and must not lock after one shot.
                    // Once correct, taps stop being handled so feedback isn't replayed.
                    if (selectedIndex != question.answerIndex) {
                        // The selection takes effect immediately, unaffected by speech
                        // throttling.
                        selections[questionIndex] = index
                        val correct = index == question.answerIndex
                        // Free talk only opens once everything is correct: finishing the
                        // whole set is exactly when a student wants to follow up, while
                        // getting one question right mid-way just earns feedback without
                        // breaking their rhythm.
                        //
                        // Recomputed here rather than using allCorrect: that value comes
                        // from this composition and does not yet reflect the answer just
                        // written into selections.
                        val finished =
                            questions.indices.all { selections[it] == questions[it].answerIndex }
                        when {
                            // All correct and the mic is not open yet: send the feedback
                            // and the opening line as one message, so two messages
                            // arriving back to back at the agent don't interrupt each
                            // other.
                            finished && !freeTalk -> {
                                speak(t.sayAllCorrect)
                                freeTalk = true
                                if (!micGranted) {
                                    micPrompted = true
                                    micPermission.launchPermissionRequest()
                                }
                                scope.launch { session.startFreeTalk() }
                            }
                            // All correct but the mic is already open: no need to walk
                            // them into opening it, just wrap up and invite follow-ups.
                            finished -> speak(t.sayCorrectThenChat)
                            correct -> speak(t.sayCorrect)
                            else -> speak(t.wrongReplies.random())
                        }
                    }
                },
                onPrev = { if (questionIndex > 0) questionIndex-- },
                onNext = { if (questionIndex < questions.size - 1) questionIndex++ },
                modifier = panelModifier,
            )
        }

        val videoPanel: @Composable (Modifier, Dp, Boolean) -> Unit = { panelModifier, tileSize, horizontal ->
            VideoPanel(
                modifier = panelModifier,
                tileSize = tileSize,
                horizontal = horizontal,
                freeTalk = freeTalk,
                micDenied = micDenied,
                onToggleFreeTalk = {
                    when {
                        freeTalk -> {
                            // Hanging up only closes the mic; the prompt is already the
                            // free-talk one, so re-entering needs no second switch.
                            freeTalk = false
                        }
                        // No mic until the questions are done: free talk is the reward for
                        // finishing, and opening the mic mid-way would lead the student off
                        // track and rob the "all correct" line of its meaning.
                        !allCorrect -> speak(t.sayFinishFirst)
                        else -> {
                            freeTalk = true
                            if (!micGranted) {
                                micPrompted = true
                                micPermission.launchPermissionRequest()
                            }
                            // The transition line is sent by the client: the server only
                            // switches the prompt and never speaks on its own.
                            speak(t.sayFreeTalkOpen)
                            scope.launch { session.startFreeTalk() }
                        }
                    }
                },
                cameraOn = cameraOn,
                onToggleCamera = { cameraOn = !cameraOn },
                session = session,
                cameraGranted = cameraGranted,
                micEnabled = micEnabled,
                onConnectedChange = { connected = it },
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val landscape = maxWidth > maxHeight
            // Two squares plus the gap have to fit exactly along one edge: landscape
            // stacks them vertically and divides the height, portrait lays them out
            // horizontally and divides the width. The side length is computed once here
            // and passed down rather than re-derived from constraints in the child — two
            // separate derivations tend to disagree.
            val tileSize = if (landscape) {
                (maxHeight - VideoGap) / 2
            } else {
                (maxWidth - VideoGap) / 2
            }.coerceAtMost(MaxVideoTileSize)

            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    questionPanel(Modifier.weight(1f).fillMaxHeight())
                    videoPanel(Modifier, tileSize, false)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    videoPanel(Modifier, tileSize, true)
                    questionPanel(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }

        // The language switch is overlaid at the top left. It sits at the outermost Box
        // rather than inside the layout tree above: that tree preserves subtree identity
        // across rotation by reusing the same Composable declaration, and adding nodes
        // into it can easily bring AvatarStage down with it.
        LangToggle(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 4.dp, top = 4.dp),
        )

        // Fixed to the bottom-right rather than placed in each scene's header: the four
        // scenes lay their top bars out differently, and an expanding panel dropped into
        // those rows distorts them.
        PerfPanel(
            session = session,
            modifier = Modifier
                .align(Alignment.TopEnd)
                // Below the status bar and the scene's own title row.
                .statusBarsPadding()
                .padding(top = 44.dp, end = 12.dp),
        )
    }
}
