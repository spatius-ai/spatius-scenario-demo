package ai.spatialwalk.scenes

import ai.spatialwalk.scenes.rtc.AgentClient
import ai.spatialwalk.scenes.rtc.AvatarRtcSession
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
 * The companion: the avatar in the middle of a warm room, and nothing to do but talk.
 *
 * The other three scenes are built around a task — questions to answer, an audience to
 * play to, a menu to work through. This one deliberately has none of that. The mic opens
 * on entry, the conversation runs on the LLM from the first word, and the only interface
 * is the room itself.
 *
 * What makes it different from free talk in the other scenes is that it remembers.
 * Everything either side says is kept by the backend (see memory.py) and folded into the
 * persona next time, so the companion opens the second conversation already knowing how
 * the first one went. Past a few thousand characters the memory is summarised down rather
 * than growing without bound.
 */

/** The warm palette of the room, matching the Web client. */
private val CompanionDark = Color(0xFF1A1410)
private val CompanionAmber = Color(0xFFFFC478)
private val CompanionSurface = Color(0xFF261C14)

/** How much of the screen height the avatar occupies, centred. Sized down from full bleed:
 *  at close to life size the figure looms over the room, which is the opposite of what this
 *  scene is for. */
private const val AvatarHeightFraction = 0.6f

/** The avatar's height in landscape, as a fraction of the window's width. */
private const val LandscapeAvatarHeightOfWidth = 1.0f

/** What the custom character description is capped at, matching the Web client's textarea. */
private const val CustomPromptLimit = 600

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CompanionRoomScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = Localization.t
    val lang = Localization.lang

    /**
     * Who is in the room, picked before it opens.
     *
     * The scene has no task, so the character is the whole of it — asking first, rather
     * than dropping the user into a default, is what makes the choice feel like part of the
     * scene instead of a setting.
     */
    var chosen by remember { mutableStateOf<Persona?>(null) }

    /**
     * Which memory the chosen character reads and writes, fixed when the room opens.
     *
     * One per character and language: per character so that what you told the flatmate does
     * not come back out of the mentor; per language because a conversation held in English
     * goes into the persona verbatim, and asking a model answering in Chinese to pull a
     * detail out of an English transcript adds a translation step that loses things.
     *
     * Fixed rather than followed live, because the language toggle stays available inside
     * the room: following it would file the second half of a conversation under a memory
     * the first half is not in — and against a persona the backend was never switched to.
     * What was said in this session belongs where it started.
     */
    var sessionMemoryKey by remember { mutableStateOf("") }

    /**
     * The session, created only once a character has been chosen.
     *
     * Held here rather than inside the stage, which is rebuilt on rotation — and created
     * lazily, because AvatarStage connects the moment it mounts and a session bills from
     * the moment it is created.
     */
    var session by remember { mutableStateOf<AvatarRtcSession?>(null) }

    var connected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }

    /** Whether the mic is open. It opens after the greeting — this scene is nothing but the
     *  conversation — and the control is there to close it, not to start it. */
    var micOpen by remember { mutableStateOf(false) }

    /** What the companion already remembers, read once on entry. Shown so the memory is
     *  visible rather than an invisible claim: without it, a returning visitor has no way
     *  to tell whether anything was kept. */
    var remembered by remember { mutableStateOf<AgentClient.MemorySummary?>(null) }
    var showMemory by remember { mutableStateOf(false) }

    // Asked for at the point the character is picked rather than on arrival: the picker
    // needs no mic, and a permission dialog thrown at someone the moment they arrive gets
    // dismissed without being read.
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    /** Opens the room once a character has been chosen. */
    fun choose(persona: Persona) {
        if (chosen != null) return
        // Fixed for the rest of the session — see `sessionMemoryKey`.
        sessionMemoryKey = "${persona.id}-${lang.code}"
        chosen = persona
        session = AvatarRtcSession(context, scope)
        if (!micPermission.status.isGranted) micPermission.launchPermissionRequest()
    }

    /** Forget everything and start over, so the scene can be shown from a blank slate. */
    fun forget() {
        val key = sessionMemoryKey
        if (key.isEmpty()) return
        scope.launch {
            runCatching {
                session?.clearMemory(key)
                remembered = session?.fetchMemory(key)
            }
        }
    }

    fun toggleMic() {
        val active = session ?: return
        scope.launch {
            runCatching {
                if (micOpen) {
                    active.unpublishMic()
                    micOpen = false
                } else {
                    active.publishMic()
                    micOpen = true
                }
            }
        }
    }

    // Read the memory as soon as the room starts opening rather than after connecting: it
    // is what the persona is built from, and the backend assembles that at session
    // creation. The greeting below picks its wording from the result.
    LaunchedEffect(sessionMemoryKey) {
        if (sessionMemoryKey.isNotEmpty()) {
            remembered = session?.fetchMemory(sessionMemoryKey)
        }
    }

    // The opening line, sent once the agent has joined — AvatarRtcSession.start waits for
    // that before reporting connected, so a line sent here is never dropped.
    LaunchedEffect(connected) {
        val active = session ?: return@LaunchedEffect
        if (!connected) return@LaunchedEffect

        runCatching {
            active.startFreeTalk("companion", chosen?.prompt.orEmpty(), sessionMemoryKey)

            // A fixed greeting, in one of two versions depending on whether there is
            // anything to remember. It is read verbatim rather than generated: the only way
            // of making the avatar speak is `speak`, which takes the text as given — there
            // is no call that has the agent produce a line of its own. What the memory does
            // affect is everything after this: it is in the persona, so the first real reply
            // already draws on it.
            active.speak(
                if ((remembered?.size ?: 0) > 0) t.companionHelloAgain else t.companionHello
            )
            active.waitUntilSilent()

            // Opened after the greeting rather than before it: open first and the companion
            // hears its own line through the room and answers itself.
            if (micPermission.status.isGranted) {
                active.publishMic()
                micOpen = true
            }
        }
    }

    // Granting after the greeting has already gone out still opens the mic: the permission
    // dialog is raised when the character is picked, and an answer that arrives late should
    // not leave the room silent with no way back in but the mute button.
    LaunchedEffect(micPermission.status.isGranted, connected) {
        val active = session ?: return@LaunchedEffect
        if (!connected || !micPermission.status.isGranted || micOpen) return@LaunchedEffect
        // Only once the greeting is over, for the same reason as above.
        runCatching {
            active.waitUntilSilent()
            active.publishMic()
            micOpen = true
        }
    }

    BackHandler(onBack = onExit)

    // Stop the session only on a real exit (rotation does not trigger this, since the key
    // is Unit).
    DisposableEffect(Unit) {
        onDispose {
            // rememberCoroutineScope won't do: it is already cancelled by the time we leave
            // the composition tree, so the stop request never goes out and the agent keeps
            // billing. Use an independent scope to carry this cleanup through.
            val active = session
            val key = sessionMemoryKey
            if (active != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { active.unpublishMic() }
                    // The conversation is collected as part of stopping: on the Agora path
                    // the transcript lives with the agent and goes away when it stops.
                    runCatching { active.stop(key) }
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(CompanionDark)) {
        // Landscape crops a portrait frame to a slice down its middle, so each orientation
        // gets its own background and the avatar is sized off the width instead.
        val landscape = maxWidth > maxHeight
        // A warm room rather than a workplace: this scene is the one with nothing to get
        // done, and the setting is most of what says so.
        Image(
            painter = painterResource(
                if (landscape) R.drawable.companion_room_bg_landscape
                else R.drawable.companion_room_bg
            ),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0x381A1410), Color(0x7A1A1410)))
                )
        )

        // Held to a band in the middle rather than filling the window. Full-bleed, the
        // figure looms over the room at close to life size and reads as confrontational —
        // which is the opposite of what this scene is for.
        if (chosen != null) {
            session?.let { active ->
                // The figure's own pixels lose their alpha towards the foot, so it
                // dissolves into the room rather than ending on a straight edge across the
                // middle of the screen. `Offscreen` is what makes that possible: it renders
                // the view into its own layer first, so the DstIn blend erases the avatar's
                // alpha rather than punching a hole through the room behind it.
                Box(
                    modifier = (
                        if (landscape) {
                            // Height from the window's *width*: in landscape the height is
                            // the tight dimension, and sizing off it would shrink the figure
                            // on every device that is merely wide.
                            Modifier
                                .fillMaxWidth()
                                .height(maxWidth * LandscapeAvatarHeightOfWidth)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(AvatarHeightFraction)
                        }
                    )
                        .align(Alignment.Center)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    // Opaque until three quarters down, then out to
                                    // nothing over the last quarter.
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
                        session = active,
                        onConnectedChange = { connected = it },
                        onFailure = { failure = it },
                    )
                }
            }
        }

        // Who is in the room. Nothing connects until this is answered — the scene is the
        // character, and a session bills from the moment it starts.
        if (chosen == null) {
            PersonaPicker(onPick = ::choose, modifier = Modifier.fillMaxSize())
        } else if (!connected) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xC71A1410)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (failure.isEmpty()) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                }
                Text(
                    failure.ifEmpty { t.enteringCompanion },
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp, start = 24.dp, end = 24.dp),
                )
            }
        }

        TitleBar(onExit = onExit, modifier = Modifier.align(Alignment.TopCenter))

        // The only controls: close the mic, and look at what is remembered. Kept to the
        // foot of the screen and small, so the room stays the thing on screen.
        if (chosen != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                MicButton(micOpen = micOpen, enabled = connected, onClick = ::toggleMic)
                MemoryButton(onClick = { showMemory = !showMemory })
            }
        }

        // What it remembers. Shown on request rather than always: the point of the scene is
        // that the memory surfaces in conversation, not that it is displayed.
        if (showMemory) {
            MemoryPanel(
                memory = remembered,
                onForget = ::forget,
                onClose = { showMemory = false },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 92.dp),
            )
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
 * Who to talk to, asked before anything connects.
 *
 * Two across rather than the Web client's auto-fitting grid: a phone in portrait has the
 * width for a pair of cards, and one per row would push the fifth off the bottom.
 */
@Composable
private fun PersonaPicker(
    onPick: (Persona) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Localization.t
    val lang = Localization.lang

    /** A character the user wrote, used when they pick the custom entry. */
    var customPrompt by remember { mutableStateOf("") }
    var writingCustom by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(Color(0xB3140E0A))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            t.companionPick,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 48.dp),
        )
        Text(
            t.companionPickHint,
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(personas(lang), key = { it.id }) { persona ->
                PersonaCard(
                    icon = persona.icon,
                    name = persona.name,
                    blurb = persona.blurb,
                    dashed = false,
                    onClick = { onPick(persona) },
                )
            }

            // Writing one is the same shape as picking one, so it sits in the grid rather
            // than below it — it is another character, not a settings escape hatch.
            item(span = { GridItemSpan(maxLineSpan) }) {
                PersonaCard(
                    icon = "✎",
                    name = t.companionCustom,
                    blurb = t.companionCustomBlurb,
                    dashed = true,
                    onClick = { writingCustom = !writingCustom },
                )
            }
        }

        if (writingCustom) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = {
                        if (it.length <= CustomPromptLimit) customPrompt = it
                    },
                    placeholder = {
                        Text(
                            t.companionCustomPlaceholder,
                            fontSize = 12.5.sp,
                            color = Color.White.copy(alpha = 0.44f),
                        )
                    },
                    textStyle = TextStyle(fontSize = 13.5.sp, color = Color.White, lineHeight = 20.sp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CompanionAmber.copy(alpha = 0.72f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.26f),
                        focusedContainerColor = Color(0xB81C1510),
                        unfocusedContainerColor = Color(0xB81C1510),
                        cursorColor = CompanionAmber,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 108.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${customPrompt.length} / $CustomPromptLimit",
                        color = Color.White.copy(alpha = 0.52f),
                        fontSize = 11.5.sp,
                    )
                    val ready = customPrompt.isNotBlank()
                    Text(
                        t.companionStart,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFFC47C36))
                            .clickable(enabled = ready) {
                                val text = customPrompt.trim()
                                onPick(
                                    Persona(
                                        id = CustomPersonaId,
                                        // The card is gone by the time this is used, so the
                                        // name only has to be something, not something short.
                                        name = text.take(12),
                                        blurb = "",
                                        icon = "✎",
                                        prompt = text,
                                    )
                                )
                            }
                            .alpha(if (ready) 1f else 0.42f)
                            .padding(horizontal = 22.dp, vertical = 9.dp),
                    )
                }
            }
        }

        // Balances the heading above, so the grid sits centred rather than riding high.
        Box(modifier = Modifier.size(24.dp))
    }
}

/** One character to pick. */
@Composable
private fun PersonaCard(
    icon: String,
    name: String,
    blurb: String,
    dashed: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                // A dashed border needs a PathEffect and a custom draw; at this size the
                // amber outline reads as "the other kind of card" just as clearly.
                color = if (dashed) CompanionAmber.copy(alpha = 0.5f)
                else Color.White.copy(alpha = 0.24f),
                shape = shape,
            )
            .clip(shape)
            .background(Color(0x9E261C14))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(icon, fontSize = 20.sp)
        Text(name, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        if (blurb.isNotEmpty()) {
            Text(
                blurb,
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * The mic control.
 *
 * Outlined and see-through: it floats over the room, and a filled block there reads as a
 * piece of UI dropped on the picture.
 */
@Composable
private fun MicButton(micOpen: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val t = Localization.t

    // Listening: warm rather than the usual recording red — nothing here is being recorded
    // for anyone else, and red reads as an alarm in a room like this.
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .border(
                width = 1.5.dp,
                color = if (micOpen) CompanionAmber.copy(alpha = 0.9f)
                else Color.White.copy(alpha = 0.72f),
                shape = shape,
            )
            .clip(shape)
            .background(
                if (micOpen) Color(0xFF8C521A).copy(alpha = 0.45f * pulse)
                else Color(0x66140E0A)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (micOpen) "●" else "🎙", fontSize = 12.sp, color = Color.White)
        Text(
            if (micOpen) t.companionListening else t.companionMicOff,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Opens the memory panel. Quieter than the mic button: it is the secondary control. */
@Composable
private fun MemoryButton(onClick: () -> Unit) {
    val t = Localization.t
    val shape = RoundedCornerShape(999.dp)
    Text(
        t.companionMemory,
        color = Color.White.copy(alpha = 0.86f),
        fontSize = 12.5.sp,
        modifier = Modifier
            .border(1.5.dp, Color.White.copy(alpha = 0.34f), shape)
            .clip(shape)
            .background(Color(0x57140E0A))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** What the companion remembers, with the way to wipe it. */
@Composable
private fun MemoryPanel(
    memory: AgentClient.MemorySummary?,
    onForget: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Localization.t

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(CompanionSurface.copy(alpha = 0.92f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(t.companionMemory, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "✕",
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onClose)
                    .padding(top = 5.dp),
            )
        }

        if (memory == null || memory.size == 0) {
            Text(
                t.companionMemoryEmpty,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        } else {
            Text(
                t.companionMemoryMeta(memory.turns, memory.size),
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 11.5.sp,
            )
            Text(
                memory.summary.ifEmpty { t.companionMemoryRaw },
                color = if (memory.summary.isEmpty()) Color.White.copy(alpha = 0.66f)
                else Color.White.copy(alpha = 0.92f),
                fontSize = 13.sp,
                lineHeight = 21.sp,
                // Long enough to read a few lines of notes, capped so the panel cannot grow
                // over the controls it sits above.
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }

        Text(
            t.companionForget,
            color = Color(0xFFFFA8A8),
            fontSize = 12.sp,
            modifier = Modifier.clickable(onClick = onForget),
        )
    }
}

/** Just the way out: the scene is the room, so nothing else belongs across the top of it. */
@Composable
private fun TitleBar(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val t = Localization.t

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Fades out rather than ending on a line: a hard edge across the photograph
            // reads as a seam.
            .background(Brush.verticalGradient(listOf(Color(0x8C140E0A), Color.Transparent)))
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
                .clickable(onClickLabel = t.companionLeave, onClick = onExit)
                .padding(top = 7.dp),
        )

        Box(modifier = Modifier.weight(1f))

        LangToggle()
    }
}
