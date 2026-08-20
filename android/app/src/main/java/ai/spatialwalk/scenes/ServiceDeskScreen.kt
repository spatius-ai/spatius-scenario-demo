package ai.spatialwalk.scenes

import ai.spatialwalk.scenes.rtc.AvatarRtcSession
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bank customer service: the avatar over the bank hall, with the topics on offer and
 * whatever is currently being explained on a panel over the bottom of them.
 *
 * Stacked rather than the Web client's side-by-side desktop split: a phone in portrait has
 * no width to spare for a column beside the avatar. The avatar takes the upper band and
 * fades into the questions below it, which sit on their own outlined card.
 *
 * Two ways to ask. The menu is canned text read verbatim through `speak` — see
 * ServiceData.kt for why accuracy matters more here than in the other scenes — and
 * questions can be asked over and over, each one cutting off whatever is still playing.
 * The button just above the panel opens the mic instead and hands the whole thing to
 * the LLM, with the same business knowledge carried as its persona.
 */

/** The band the avatar occupies, measured from the top. The panel overlaps its lower
 *  part, so these deliberately add up to more than the screen. */
private const val AvatarHeightFraction = 0.7f

/** How much of the screen the question panel takes, anchored to the bottom. */
private const val PanelHeightFraction = 0.5f

/** How much of a landscape window the avatar column takes, leaving the rest to the menu. */
private const val LandscapeAvatarColumnFraction = 0.42f

/** The avatar's height in landscape, as a fraction of the window's width. */
private const val LandscapeAvatarHeightOfWidth = 0.8f

/** The gap between the button and the top edge of the panel below it. */
private val LiveButtonGap = 10.dp


/** Blue, matching the Web client's chips and cards. */
private val ServiceBlue = Color(0xFF1E63D0)
private val ServiceInk = Color(0xFF1A2740)
private val ServiceMuted = Color(0xFF6B7893)

/** One line of the exchange, shown on the panel above the question rows. */
private data class Turn(
    val id: Int,
    val fromCustomer: Boolean,
    val text: String,
    /** True while the reply is on its way: the bubble shows dots instead of the text. */
    val pending: Boolean,
)

private enum class Phase { CHATTING, LIVE }

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ServiceDeskScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = Localization.t
    val lang = Localization.lang
    val keyboard = LocalSoftwareKeyboardController.current

    // The session is created and held here: AvatarStage is rebuilt on rotation, so a
    // session living inside it would go away with it.
    val session = remember { AvatarRtcSession(context, scope) }
    var connected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }

    val turns = remember { mutableStateListOf<Turn>() }
    var nextTurnId by remember { mutableIntStateOf(1) }
    val listState = rememberLazyListState()

    /**
     * Where in the menu the customer is: null at the top showing categories, otherwise the
     * category whose questions are listed.
     *
     * Nothing is consumed by being asked. A question stays on the list after it has been
     * answered — someone who half caught a limit or a document name wants to hear it again,
     * and a menu that empties as it is used ends up blank in front of a customer who still
     * has questions.
     */
    var openCategory by remember { mutableStateOf<ServiceCategory?>(null) }

    /** The card that came with the current answer, or null when the reply was speech only. */
    var card by remember { mutableStateOf<AnswerCard?>(null) }

    /**
     * The search box.
     *
     * Matches across every category rather than the list on screen — someone typing 「挂失」
     * while inside the account category means they want that question, wherever it lives.
     *
     * Matched against the label, the question as asked, and the answer, so a word that only
     * appears in the reply still finds it.
     */
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    val results = if (!searching) emptyList() else {
        val needle = query.trim().lowercase()
        ServiceData.allQuestions(lang).filter {
            "${it.label} ${it.asked} ${it.answer}".lowercase().contains(needle)
        }
    }

    /** The questions listed right now: search results, or the open category's set. */
    val visibleQuestions = if (searching) results else openCategory?.questions.orEmpty()

    /** Categories show only at the top level — inside one, or while searching, the list is
     *  questions. */
    val visibleCategories =
        if (openCategory != null || searching) emptyList() else ServiceData.categories(lang)

    /** Whether the avatar is currently reading an answer. Only drives the indicator — it
     *  does not lock anything, since a new question is allowed to cut the current one off. */
    var speaking by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(Phase.CHATTING) }

    /**
     * Which answer is currently playing.
     *
     * Every ask takes a ticket. When the avatar finishes talking, the handler checks its
     * ticket is still the current one before clearing the speaking flag — otherwise a reply
     * that was interrupted three questions ago wakes up and clears a flag belonging to the
     * answer now playing.
     */
    var currentAsk by remember { mutableIntStateOf(0) }

    // Denial has to put the scene back on the menu. Left in the live phase the panel says
    // it is listening while the mic is shut and the persona was never switched, which is
    // the interface telling the customer something that is not true.
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO) { granted ->
        if (!granted) phase = Phase.CHATTING
    }

    fun addTurn(fromCustomer: Boolean, text: String, pending: Boolean = false): Int {
        val id = nextTurnId++
        turns += Turn(id, fromCustomer, text, pending)
        return id
    }

    /** Swap a pending bubble for the text it was holding a place for. */
    fun settleTurn(id: Int) {
        val index = turns.indexOfFirst { it.id == id }
        if (index >= 0) turns[index] = turns[index].copy(pending = false)
    }

    /** Drop a pending bubble whose reply never arrived, or was cut off by the next question. */
    fun dropTurn(id: Int) {
        turns.removeAll { it.id == id }
    }

    /**
     * Say one line, showing dots until it can be heard.
     *
     * Everything the avatar says goes through here so the caption and the voice stay
     * together — see `ask` for why that gap matters.
     */
    suspend fun say(text: String) {
        val id = addTurn(fromCustomer = false, text = text, pending = true)
        runCatching {
            session.speak(text)
            session.waitUntilAudible()
        }
        settleTurn(id)
    }

    /**
     * Read one answer out.
     *
     * Interrupting is the point: tapping a second question while the first is still being
     * read cuts it off and starts the new one. Waiting for a paragraph of bank policy to
     * finish before the menu responds makes the whole thing feel stuck, and a customer who
     * has heard the part they needed should be able to move on.
     */
    fun ask(question: ServiceQuestion) {
        if (phase != Phase.CHATTING) return

        val ticket = ++currentAsk
        speaking = true

        // Asking one is the end of that search: leaving the query in place would answer the
        // question and then still show a filtered list rather than where to go next.
        query = ""
        keyboard?.hide()
        addTurn(fromCustomer = true, text = question.asked)
        // The reply goes up as dots and stays that way until it can actually be heard: the
        // text is ready instantly, the voice is not, and a caption that lands a second or
        // two ahead of the audio reads as the avatar being out of sync with itself.
        val replyId = addTurn(fromCustomer = false, text = question.answer, pending = true)
        // The card belongs with the spoken answer, so it waits too.
        card = null

        scope.launch {
            runCatching {
                // Stop whatever is playing before starting this one, or the two overlap:
                // speak queues rather than replaces.
                session.interrupt()
                session.speak(question.answer)
                session.waitUntilAudible()
            }
            // Interrupted while the audio was on its way — the dots belong to a question the
            // customer has already moved on from, so take them down rather than filling them
            // in.
            if (ticket != currentAsk) {
                dropTurn(replyId)
                return@launch
            }
            settleTurn(replyId)
            card = question.card
            // Only the newest ask owns the flag — an interrupted one must not clear it.
            speaking = false
        }
    }

    /**
     * Open the mic and let them ask anything.
     *
     * The menu answers are canned text read verbatim, which is what keeps them accurate and
     * fast. This is the other half: the backend switches to the bank persona, the LLM starts
     * answering what is actually said, and everything in the menu becomes background the
     * avatar can draw on rather than a list to pick from.
     */
    suspend fun openMic() {
        // Whatever answer was playing belongs to the menu the customer has just left, so
        // its ticket must not settle a bubble once the mic is open.
        currentAsk++
        speaking = false

        runCatching {
            // Stop whatever answer is playing first — the transition line should not land
            // on top of a paragraph about transfer limits.
            session.interrupt()
            session.startFreeTalk("banker")
            session.publishMic()
            // The transition line is sent by the client: the server only switches the
            // prompt and never speaks on its own.
            say(t.serviceLiveOpen)
        }.onFailure {
            phase = Phase.CHATTING
            runCatching { session.unpublishMic() }
        }
    }

    fun startLive() {
        if (phase != Phase.CHATTING) return
        phase = Phase.LIVE
        // Asked for at the point of use rather than on entry: the menu answers need no mic,
        // and a permission dialog thrown at someone the moment they arrive gets dismissed
        // without being read. Opening is left to the effect below either way, so the tap
        // that raises the dialog is also the one that opens the mic — asking the customer
        // to tap a second time after granting reads as the button having failed.
        if (!micPermission.status.isGranted) micPermission.launchPermissionRequest()
    }

    // The only place the mic is opened: entering the live phase with permission in hand,
    // whether it was already granted or has just come back from the dialog.
    LaunchedEffect(phase, micPermission.status.isGranted) {
        if (phase == Phase.LIVE && micPermission.status.isGranted) openMic()
    }

    /** Back to the menu: close the mic and stop the LLM answering. */
    fun endLive() {
        if (phase != Phase.LIVE) return
        phase = Phase.CHATTING
        scope.launch { runCatching { session.unpublishMic() } }
    }

    // The greeting is only sent once the agent has joined — AvatarRtcSession.start waits
    // for that before reporting connected, so a line sent here is never dropped.
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        speaking = true
        say(t.serviceGreeting)
        speaking = false
    }

    // Keep the newest line in view. The transcript is short by design, so anything more
    // than a couple of exchanges scrolls.
    LaunchedEffect(turns.size, turns.lastOrNull()?.pending, card) {
        // The dots becoming a paragraph grows the bubble and pushes its own bottom out of
        // view, and the card adds its own height under it — hence keying on those too
        // rather than on the line count alone.
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex + 1)
    }

    // No rating step: leaving is leaving, and the system back gesture is how a phone does
    // it — spending an on-screen control on that as well would be one more thing over the
    // avatar's face.
    BackHandler(onBack = onExit)

    // Stop the session only on a real exit (rotation does not trigger this, since the key
    // is Unit).
    DisposableEffect(Unit) {
        onDispose {
            // rememberCoroutineScope won't do: it is already cancelled by the time we leave
            // the composition tree, so the stop request never goes out and the agent keeps
            // billing. Use an independent scope to carry this cleanup through.
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { session.unpublishMic() }
                runCatching { session.stop() }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1B2E))) {
        // Where the panel's top edge falls, measured up from the bottom of the screen —
        // what the fade and the button above it both position against.
        val panelTop = maxHeight * PanelHeightFraction
        // Landscape puts the avatar beside the menu instead of above it — stacked, a
        // landscape phone leaves the avatar a letterbox strip and the menu two rows.
        val landscape = maxWidth > maxHeight
        // The hall is the whole screen: it is the room the scene happens in, and cutting it
        // to a band behind the avatar would put a seam across it.
        //
        // A portrait photograph cropped to a landscape window keeps only a slice down its
        // middle, so each orientation gets a frame shot for it.
        Image(
            painter = painterResource(
                if (landscape) R.drawable.bank_hall_bg_landscape else R.drawable.bank_hall_bg
            ),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Dimmed and cooled so the avatar in front of it keeps contrast — at full
        // brightness the lit counter competes with the face.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x4D0A1A30), Color(0x8C0A1A30))
                    )
                )
        )

        // The avatar holds the upper band and the panel is laid over the bottom of the
        // screen, so the two overlap: the figure runs on behind the questions rather than
        // being cut off square where they begin.
        // The avatar itself fades out towards its foot, rather than a block of colour being
        // laid over it: the figure's own pixels lose their alpha down the gradient, so it
        // dissolves into whatever is behind it instead of being covered up.
        //
        // `compositingStrategy = Offscreen` is what makes that possible — it renders the
        // view into its own layer first, so the DstIn blend below erases the avatar's alpha
        // rather than punching a hole through everything already drawn underneath.
        Box(
            modifier = (
                if (landscape) {
                    // Height from the window's *width*, per the design: the avatar keeps a
                    // consistent presence across devices rather than growing with whatever
                    // height the phone happens to have.
                    Modifier
                        .fillMaxWidth(LandscapeAvatarColumnFraction)
                        .height(maxWidth * LandscapeAvatarHeightOfWidth)
                        .align(Alignment.BottomStart)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(AvatarHeightFraction)
                }
            )
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            // Opaque until three quarters down, then out to nothing over
                            // the last quarter.
                            colorStops = arrayOf(
                                0f to Color.Black,
                                0.75f to Color.Black,
                                1f to Color.Transparent,
                            ),
                            startY = 0f,
                            endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
        ) {
            AvatarStage(
                modifier = Modifier.fillMaxSize(),
                session = session,
                onConnectedChange = { connected = it },
                onFailure = { failure = it },
            )
        }

        ServicePanel(
            turns = turns,
            card = card,
            listState = listState,
            phase = phase,
            connected = connected,
            speaking = speaking,
            query = query,
            onQueryChange = { query = it },
            onSubmitSearch = {
                // The list filters as they type, so submitting has to do more than
                // re-run it: a single match is asked outright, since typing enough to
                // narrow it to one and then having to tap it is a step nobody wants.
                // Several matches are left on screen to choose from.
                if (results.size == 1) ask(results[0])
            },
            openCategory = openCategory,
            searching = searching,
            categories = visibleCategories,
            questions = visibleQuestions,
            onOpenCategory = { openCategory = it; query = "" },
            onBackToCategories = { openCategory = null; query = "" },
            onClearSearch = { query = "" },
            onAsk = ::ask,
            modifier = if (landscape) {
                Modifier
                    .fillMaxWidth(1f - LandscapeAvatarColumnFraction)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
            } else {
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(PanelHeightFraction)
                    .align(Alignment.BottomCenter)
            },
        )

        // Outside the panel, sitting just above it: it is the alternative to the whole
        // menu rather than one more control within it, so it stays clear of the card
        // holding the conversation.
        LiveButton(
            phase = phase,
            connected = connected,
            onToggleLive = { if (phase == Phase.LIVE) endLive() else startLive() },
            modifier = if (landscape) {
                // Under the avatar rather than over the menu: the menu now occupies its own
                // column, and a button floating above it would sit in the middle of the
                // conversation.
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    // Lifted clear of the panel: its own height plus a gap, measured from
                    // the panel's top edge, so the whole button sits above the card rather
                    // than straddling its border.
                    .padding(bottom = panelTop + LiveButtonGap)
            },
        )

        TitleBar(
            connected = connected,
            onExit = onExit,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (!connected) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xC2081426)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (failure.isEmpty()) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                }
                Text(
                    failure.ifEmpty { t.enteringService },
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp, start = 24.dp, end = 24.dp),
                )
            }
        }

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

/**
 * The bar across the top: leaving on the left, who is on the line in the middle, and the
 * mic on the right.
 *
 * The mic control is not here — it sits above the question panel. The
 * panel already owns the bottom half of a phone screen, and a floating button above it
 * would sit right where the avatar's hands are.
 */
@Composable
private fun TitleBar(
    connected: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Localization.t

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Fades out rather than ending on a line: a hard edge across the hall
            // photograph reads as a seam.
            .background(Brush.verticalGradient(listOf(Color(0xB8081426), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "✕",
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                // Labelled, or a screen reader announces the glyph rather than what it does.
                .clickable(onClickLabel = t.serviceLeave, onClick = onExit)
                .padding(top = 7.dp),
        )

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(t.serviceTitle, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3DDC84))
                )
                Text(t.serviceOnline, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
            }
        }

        // Balances the close button so the title sits centred between them.
        Box(modifier = Modifier.size(32.dp))
    }
}

/**
 * The other way to ask: opens the mic and hands the conversation to the LLM.
 *
 * Floats just above the panel rather than inside it — it is the alternative to the whole
 * menu rather than one more control within it,
 * as the alternative to picking one, and on the avatar it competes with the title bar.
 */
@Composable
private fun LiveButton(
    phase: Phase,
    connected: Boolean,
    onToggleLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Localization.t
    val live = phase == Phase.LIVE

    // Listening: red, and pulsing, so it is obvious at a glance the mic is open.
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    // Outlined and see-through rather than a solid slab: it sits on the avatar, and a
    // filled block there reads as a piece of UI dropped on top of the picture. The border
    // is what makes it legible as a control over a moving background.
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .border(
                width = 1.5.dp,
                color = if (live) Color(0xFFFF8A8A) else Color.White.copy(alpha = 0.9f),
                shape = shape,
            )
            .clip(shape)
            .background(
                if (live) Color(0xFFD64545).copy(alpha = 0.4f * pulse)
                else Color(0x66081426)
            )
            .clickable(enabled = connected, onClick = onToggleLive)
            .alpha(if (connected) 1f else 0.5f)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Text(if (live) "●" else "🎙", fontSize = 12.sp, color = Color.White)
        Text(
            if (live) t.serviceLiveOn else t.serviceLive,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The translucent sheet over the bottom of the avatar: the exchange so far, then either
 * the questions on offer or the listening indicator.
 */
@Composable
private fun ServicePanel(
    turns: List<Turn>,
    card: AnswerCard?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    phase: Phase,
    connected: Boolean,
    speaking: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    openCategory: ServiceCategory?,
    searching: Boolean,
    categories: List<ServiceCategory>,
    questions: List<ServiceQuestion>,
    onOpenCategory: (ServiceCategory) -> Unit,
    onBackToCategories: () -> Unit,
    onClearSearch: () -> Unit,
    onAsk: (ServiceQuestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Localization.t

    // Inset from the screen edges and outlined rather than a full-width slab: a sheet that
    // runs edge to edge has no boundary of its own and reads as the bottom of the window
    // rather than as a surface holding the conversation.
    Column(
        modifier = modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            // See-through, so the hall and the avatar's feet carry on behind the questions
            // rather than being walled off by a solid sheet.
            .background(Color.White.copy(alpha = 0.62f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The conversation is its own surface inside the panel, outlined and inset, so it
        // reads as a transcript rather than as bubbles floating loose above the questions.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                // Roughly a third of the panel, so a long exchange scrolls inside it
                // instead of pushing the questions off the bottom.
                .weight(0.9f)
                .border(1.dp, Color(0x1F1A2740), RoundedCornerShape(12.dp))
                .border(1.dp, ServiceBlue.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(turns, key = { it.id }) { turn -> TurnBubble(turn) }

            // The card that came with the answer, laid out like the account-type list in a
            // real assistant: a heading, the explanation, then the call to action.
            item {
                if (card != null) AnswerCardBlock(card)
            }
        }

        if (phase == Phase.CHATTING) {
            // Searching the whole bank, not the offered set: someone who types a word wants
            // that question wherever it sits in the tree.
            SearchRow(
                query = query,
                onQueryChange = onQueryChange,
                onSubmit = onSubmitSearch,
                enabled = connected,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                when {
                    // Inside a category, the heading doubles as the way back out.
                    openCategory != null && !searching -> Text(
                        "‹ ${openCategory.label}",
                        color = ServiceBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onBackToCategories),
                    )

                    else -> Text(
                        if (searching) t.serviceAsk else t.serviceCategories,
                        color = ServiceMuted,
                        fontSize = 12.sp,
                    )
                }
                if (searching) {
                    Text(
                        "✕",
                        color = ServiceBlue,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(onClick = onClearSearch),
                    )
                } else if (speaking) {
                    Text(t.serviceSpeaking, color = ServiceMuted, fontSize = 11.sp)
                }
            }

            // Three across rather than a full-width row each: a phone panel fits two
            // stacked rows before it runs out, which is not enough of the menu to be worth
            // scrolling. Nine tiles in the same space means most categories are reachable
            // without moving.
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                if (searching && questions.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(t.serviceSearchEmpty, color = ServiceMuted, fontSize = 12.sp)
                    }
                }

                // Top level: the categories.
                items(categories, key = { "cat-${it.id}" }) { category ->
                    CategoryTile(category, enabled = connected) { onOpenCategory(category) }
                }

                // Inside a category, or the search results. Never disabled while connected:
                // tapping one while another answer is playing cuts it off and starts this
                // one.
                items(questions, key = { "q-${it.id}" }) { question ->
                    QuestionTile(question, enabled = connected) { onAsk(question) }
                }
            }
        } else {
            // Live Q&A: the mic is open and the LLM is answering. Ending it is done with
            // the button above the panel, which is also what started it.
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ListeningPulse()
                Text(
                    t.serviceLiveListening,
                    color = ServiceInk,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** One line of the exchange. The customer's are blue and right-aligned, the agent's white
 *  and left, like any messaging thread. */
@Composable
private fun TurnBubble(turn: Turn) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (turn.fromCustomer) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                // A ceiling rather than a width: `weight(fill = false)` lets a short line
                // keep its own width and only caps the long ones, which fillMaxWidth would
                // stretch into a full-width block.
                .weight(0.82f, fill = false)
                .clip(RoundedCornerShape(14.dp))
                .background(if (turn.fromCustomer) ServiceBlue else Color.White.copy(alpha = 0.94f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (turn.pending) {
                // Waiting for the voice: three dots lifting one after another — a bubble
                // that just sits there empty reads as something having gone wrong.
                TypingDots(onDark = turn.fromCustomer)
            } else {
                Text(
                    turn.text,
                    color = if (turn.fromCustomer) Color.White else ServiceInk,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

/** Three dots riding up and down in turn, while the answer is on its way. */
@Composable
private fun TypingDots(onDark: Boolean) {
    val transition = rememberInfiniteTransition(label = "dots")
    val color = if (onDark) Color.White else ServiceInk

    Row(
        // Matches a line of text, so the bubble does not change height when the words land.
        modifier = Modifier.height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1100
                        0.35f at 0
                        0.9f at 380 + index * 160
                        0.35f at 760 + index * 160
                    },
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

/** The card that comes with an answer. Blue like the one in a banking app, and clearly a
 *  block rather than another bubble. */
@Composable
private fun AnswerCardBlock(card: AnswerCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ServiceBlue.copy(alpha = 0.08f))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (card.title.isNotEmpty()) {
            Text(card.title, color = ServiceInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        card.body.forEach { line ->
            Text(line, color = Color(0xFF45536E), fontSize = 13.sp, lineHeight = 19.sp)
        }
        card.action?.let { action ->
            Text(
                "👉 $action",
                color = ServiceBlue,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The search box and its button. */
@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
) {
    val t = Localization.t
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(t.serviceSearch, fontSize = 13.sp, color = ServiceMuted) },
            textStyle = TextStyle(fontSize = 13.5.sp, color = ServiceInk),
            shape = RoundedCornerShape(9.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ServiceBlue,
                unfocusedBorderColor = Color(0x241A2740),
                focusedContainerColor = Color.White.copy(alpha = 0.85f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.85f),
                cursorColor = ServiceBlue,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f).height(50.dp),
        )
        Text(
            "🔍",
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(width = 44.dp, height = 50.dp)
                .border(1.dp, Color(0x241A2740), RoundedCornerShape(9.dp))
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White.copy(alpha = 0.85f))
                .clickable(enabled = enabled, onClick = onSubmit)
                .padding(top = 15.dp),
        )
    }
}

/**
 * One category. Carries a name and what is under it, so it is taller than a question row
 * and reads as a heading you open rather than a question you ask.
 */
@Composable
private fun CategoryTile(category: ServiceCategory, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ServiceBlue.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.9f))
            // Weighted enough to survive the bright end of the hall behind the panel: at
            // a sixth of an opacity the outline disappears against the lit counter, which
            // is most of what sits behind these tiles.
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(category.icon, fontSize = 20.sp)
        // The blurb is dropped at this width — a third of a phone leaves room for the
        // name and nothing more, and a truncated subtitle tells the reader less than none.
        Text(
            category.label,
            color = ServiceInk,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** One question. Tapping it cuts off whatever is playing and reads this answer instead. */
@Composable
private fun QuestionTile(question: ServiceQuestion, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ServiceBlue)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            question.label,
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
    }
}

/** A pulse rather than a spinner: nothing is loading, something is listening. */
@Composable
private fun ListeningPulse() {
    val transition = rememberInfiniteTransition(label = "listening")
    val scale by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(ServiceBlue.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((34 * scale).dp)
                .clip(CircleShape)
                .background(ServiceBlue)
        )
    }
}
