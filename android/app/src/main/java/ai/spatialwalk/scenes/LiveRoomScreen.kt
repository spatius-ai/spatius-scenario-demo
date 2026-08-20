package ai.spatialwalk.scenes

import ai.spatialwalk.scenes.rtc.AvatarRtcSession
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The live room: the stream on top with danmaku drifting over it, the chat list below,
 * and the gift bar and composer at the foot.
 *
 * Stacked rather than side by side, which is the Web layout: a phone in portrait has no
 * width to spare for a column beside the video, and a stream is watched upright.
 *
 * The audience runs itself. Viewers arrive in bursts with a message already paired to the
 * host's reply (see ChatData), and now and then one sends a gift. Replies are read aloud
 * through `speak`, which is verbatim TTS and never involves the LLM — canned text is what
 * keeps a reply inside a second, fast enough to still be answering the message the viewer
 * can see on screen.
 */

/** How many danmaku lanes cross the video. */
private const val DanmakuLanes = 7
private const val DanmakuMinSeconds = 5f
private const val DanmakuMaxSeconds = 9f

/** Cap on danmaku in flight. A burst outruns the crossing time, and past this the video
 *  disappears behind text. */
private const val MaxDanmaku = 14

/** Cap on the chat list, so a long session cannot grow it without bound. */
private const val MaxMessages = 80

/** How long a viewer waits after asking for the mic. Fake — there is nobody to approve
 *  it — but going straight from a tap to an open mic reads as a toggle rather than a
 *  request being granted. */
private const val MicApprovalMs = 3000L

private data class Danmaku(
    val id: Long,
    val text: String,
    val lane: Int,
    val hue: Float,
    val seconds: Float,
    val fontSize: Int,
)

private enum class MicState { IDLE, PENDING, LIVE }

@Composable
fun LiveRoomScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = Localization.t
    val lang = Localization.lang

    val session = remember { AvatarRtcSession(context, scope) }
    var connected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }

    val messages = remember { mutableStateListOf<ChatMessage>() }
    val danmaku = remember { mutableStateListOf<Danmaku>() }
    var viewers by remember { mutableStateOf(1200 + Random.nextInt(800)) }
    var micState by remember { mutableStateOf(MicState.IDLE) }
    var draft by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    // What the host may pick up. Cleared once something is chosen, so a line that has
    // scrolled past stays unanswered — which is what happens in a real room.
    val answerable = remember { mutableStateListOf<ChatMessage>() }
    var gifted by remember { mutableStateOf<ChatMessage?>(null) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) micState = MicState.IDLE
    }

    fun post(message: ChatMessage) {
        messages += message
        if (messages.size > MaxMessages) messages.removeRange(0, messages.size - MaxMessages)

        val seconds = DanmakuMinSeconds + Random.nextFloat() * (DanmakuMaxSeconds - DanmakuMinSeconds)
        danmaku += Danmaku(
            id = message.id,
            text = if (message.gift != null) "${message.gift.icon} ${message.text}" else message.text,
            lane = Random.nextInt(DanmakuLanes),
            hue = message.viewer.hue,
            seconds = seconds,
            // Small spread only. Past roughly this much the big ones read as emphasis the
            // sender never intended.
            fontSize = 13 + Random.nextInt(5),
        )
        if (danmaku.size > MaxDanmaku) danmaku.removeRange(0, danmaku.size - MaxDanmaku)

        if (message.reply != null) {
            if (message.gift != null) gifted = message
            else {
                answerable += message
                // Only the last few are still on screen; answering something from a
                // minute ago reads as the host being out of step with the room.
                if (answerable.size > 12) answerable.removeAt(0)
            }
        }
    }

    // ---- The room ----

    // The audience, arriving in bursts rather than on a beat. A real room surges and then
    // goes quiet; a steady interval reads as a machine dropping text on a timer.
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        var burstLeft = 0
        while (true) {
            val delayMs = if (burstLeft > 0) 250L + Random.nextLong(500) else 1500L + Random.nextLong(3000)
            if (burstLeft > 0) burstLeft -= 1 else if (Random.nextFloat() < 0.45f) burstLeft = 3 + Random.nextInt(8)
            delay(delayMs)
            // Gifts are the rare event, which is what makes one worth breaking off to
            // thank. At burst rate even a small share arrives constantly.
            if (Random.nextFloat() < 0.02f) post(ChatData.giftMessage(lang, ChatData.randomGift(lang)))
            else post(ChatData.randomChat(lang))
        }
    }

    // The host: open with an introduction, then answer whatever happens to be on screen.
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        runCatching {
            session.speak(t.greeting)
            session.waitUntilSilent()
        }
        while (true) {
            // Silent while a viewer has the mic, or is waiting to be let in: reading
            // canned lines over a real conversation talks across the person who just got
            // permission to speak, and the free-talk persona is answering them at the
            // same time.
            if (micState == MicState.IDLE) {
                val next = gifted ?: answerable.randomOrNull()
                gifted = null
                if (next?.reply != null) {
                    answerable.clear()
                    runCatching {
                        session.speak(next.reply)
                        session.waitUntilSilent()
                    }
                }
            }
            delay(Random.nextLong(3000))
        }
    }

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        while (true) {
            delay(3000)
            viewers = (viewers + Random.nextInt(21) - 8).coerceAtLeast(800)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // Deliberately not stopping the session on dispose alone — AvatarStage owns the
    // view's lifecycle and the session is stopped when the room is really left.
    // A swipe back has to leave the room the same way the close button does. Without this
    // the gesture falls through to the system, which pops the activity — the scene never
    // learns it was left, so nothing here runs and the agent keeps billing.
    BackHandler(onBack = onExit)

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

    fun requestMic() {
        if (micState != MicState.IDLE) {
            micState = MicState.IDLE
            scope.launch { runCatching { session.unpublishMic() } }
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        micState = MicState.PENDING
        scope.launch {
            runCatching {
                // Switch the persona and open the mic before the wait rather than after:
                // spending it idle wastes it, and doing this first means recognition and
                // the model are warm by the time anyone speaks.
                session.startFreeTalk("host")
                session.publishMic()
                session.speak(t.micWelcome)
                session.waitUntilSilent()
                // Whatever is left of the approval wait. The host acknowledging the
                // request usually covers it on its own, and this only pads a short line.
                delay(MicApprovalMs - 2000L)
            }.onSuccess {
                if (micState == MicState.PENDING) micState = MicState.LIVE
            }.onFailure {
                micState = MicState.IDLE
            }
        }
    }

    fun sendDraft() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        post(ChatMessage(System.currentTimeMillis(), Viewer(t.you, 265f), text, null, null))
    }

    // ---- Layout ----

    // The scene's own root is a Column, so the readout gets a Box around it to have
    // something to align against.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Landscape turns the stack on its side: the stage takes one column and the chat
        // the other. Stacked vertically on a landscape phone the stage would be a letterbox
        // strip and the chat a couple of lines — neither ends up usable.
        val landscape = maxWidth > maxHeight

        val stage = @Composable { modifier: Modifier ->
            LiveStage(
                session = session,
                danmaku = danmaku,
                viewers = viewers,
                micState = micState,
                connected = connected,
                failure = failure,
                onExit = onExit,
                onMic = ::requestMic,
                onConnectedChange = { connected = it },
                onFailure = { failure = it },
                modifier = modifier,
            )
        }

        val chat = @Composable { modifier: Modifier ->
            Column(modifier = modifier) {
                ChatList(
                    messages = messages,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                )

                GiftBar(lang = lang, enabled = connected) { gift ->
                    post(ChatData.giftMessage(lang, gift, ChatData.randomViewer(lang)))
                }

                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = ::sendDraft,
                    enabled = connected,
                )
            }
        }

        val frame = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()

        if (landscape) {
            Row(modifier = frame) {
                // A square, sized off the height — the tight dimension in landscape.
                stage(Modifier.fillMaxHeight().aspectRatio(1f))
                chat(Modifier.weight(1f))
            }
        } else {
            Column(modifier = frame) {
                stage(Modifier.fillMaxWidth().aspectRatio(1f))
                chat(Modifier.weight(1f))
            }
        }

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
 * The stream, with everything laid over it: danmaku, the badges, the exit and mic
 * controls.
 *
 * Square, matching the Web client. A phone could give it more height, but the avatar is
 * framed for a square and the room below needs the space more.
 */
@Composable
private fun LiveStage(
    session: AvatarRtcSession,
    danmaku: List<Danmaku>,
    viewers: Int,
    micState: MicState,
    connected: Boolean,
    failure: String,
    onExit: () -> Unit,
    onMic: () -> Unit,
    onConnectedChange: (Boolean) -> Unit,
    onFailure: (String) -> Unit,
    // Sizing is the caller's: portrait gives the stage a square across the top, landscape
    // a column down one side. Deciding it here would mean the stage filling the width in
    // landscape and leaving the chat nowhere to go.
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1f),
) {
    val t = Localization.t

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF111111)),
    ) {
        Image(
            painter = painterResource(R.drawable.live_room_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        AvatarStage(
            modifier = Modifier.fillMaxSize(),
            session = session,
            onConnectedChange = onConnectedChange,
            onFailure = onFailure,
        )

        // Danmaku sits over the video rather than beside it: that overlap is what makes a
        // stream read as live, and the list below is the record for anything that drifts
        // past too fast to catch.
        danmaku.forEach { item ->
            DanmakuLine(item)
        }

        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                t.liveBadge,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFFE0245E))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Text(
                t.viewerCount(viewers),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }

        Text(
            "←",
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onExit)
                .padding(top = 5.dp),
        )

        // Over the video, bottom right: asking to speak is something you do to the stream,
        // so the control belongs on it rather than below with the chat.
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .clip(CircleShape)
                .background(
                    if (micState == MicState.LIVE) Color(0xFFE0245E)
                    else Color.Black.copy(alpha = 0.55f)
                )
                .clickable(enabled = connected, onClick = onMic)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                if (micState == MicState.LIVE) "●" else "🎙",
                fontSize = 11.sp,
                color = Color.White,
            )
            Text(
                when (micState) {
                    MicState.IDLE -> t.micIdle
                    MicState.PENDING -> t.micPending
                    MicState.LIVE -> t.micLive
                },
                color = Color.White,
                fontSize = 12.sp,
            )
        }

        if (!connected) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (failure.isEmpty()) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(26.dp))
                }
                Text(
                    failure.ifEmpty { t.enteringLive },
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One line of danmaku crossing the video, at its own speed and size — uniform ones move
 *  like a marquee and read as one animation rather than many people typing. */
@Composable
private fun DanmakuLine(item: Danmaku) {
    val transition = rememberInfiniteTransition(label = "danmaku")
    val progress by transition.animateFloat(
        initialValue = 1f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(
            animation = tween((item.seconds * 1000).toInt(), easing = LinearEasing),
        ),
        label = "drift",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            item.text,
            color = Color.hsl(item.hue, 0.7f, 0.85f),
            fontSize = item.fontSize.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            style = TextStyle(
                // An outline rather than a panel: danmaku has to stay readable over
                // whatever the video happens to be showing.
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black.copy(alpha = 0.9f),
                    blurRadius = 4f,
                ),
            ),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(top = (6 + item.lane * 11).dp)
                .offset { IntOffset((progress * 1200).toInt(), 0) },
        )
    }
}

/** The chat list. The record of everything said, including what drifted past unread. */
@Composable
private fun ChatList(
    messages: List<ChatMessage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // A surface of its own, so the list reads as the room's chat rather than text
            // spilling out from under the video with nothing holding it.
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 10.dp),
        // Anchored to the bottom: an empty room then starts its first messages at the
        // foot of the panel, where the newest always is, rather than at the top with the
        // rest of the space blank beneath them.
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        // Gift messages are tinted so they stand out in a fast-moving
                        // list — they are the ones the host reacts to first.
                        if (message.gift != null) {
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(6.dp)
                        } else Modifier
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    message.viewer.name.take(1),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.hsl(message.viewer.hue, 0.6f, 0.6f))
                        .padding(top = 4.dp),
                )
                Column {
                    Text(
                        message.viewer.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (message.gift != null) "${message.gift.icon} ${message.text}"
                        else message.text,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Gifts, scrolling horizontally under the chat. */
@Composable
private fun GiftBar(lang: Lang, enabled: Boolean, onSend: (Gift) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ChatData.gifts(lang), key = { it.name }) { gift ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(enabled = enabled) { onSend(gift) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .width(52.dp),
            ) {
                Text(gift.icon, fontSize = 19.sp)
                Text(gift.name, fontSize = 10.sp, maxLines = 1)
                Text(
                    gift.value.toString(),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Where the viewer types. Their lines join the pool the host picks from, like anyone
 *  else's — but with no canned reply, since nobody wrote one for it. */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
) {
    val t = Localization.t
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(t.chatPlaceholder, fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
            shape = RoundedCornerShape(9.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSend, enabled = enabled && draft.isNotBlank()) {
            Text(t.send, fontSize = 13.sp)
        }
    }
}
