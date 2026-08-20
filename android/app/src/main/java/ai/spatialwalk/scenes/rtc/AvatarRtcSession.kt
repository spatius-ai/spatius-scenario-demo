package ai.spatialwalk.scenes.rtc

import ai.spatialwalk.scenes.Localization
import ai.spatius.avatarkit.AvatarSDK
import ai.spatius.avatarkit.AvatarView
import ai.spatius.avatarkit.Configuration
import ai.spatius.avatarkit.DrivingServiceMode
import ai.spatius.avatarkit.LogLevel
import ai.spatius.avatarkit.assets.AvatarManager
import ai.spatius.avatarkit.performance.FrameRateMonitor
import ai.spatius.avatarkit.performance.PowerMonitor
import ai.spatius.avatarkit.rtc.AgoraConnectionConfig
import ai.spatius.avatarkit.rtc.AnimationSessionSummary
import ai.spatius.avatarkit.rtc.AvatarPlayer
import ai.spatius.avatarkit.rtc.AvatarPlayerEvent
import ai.spatius.avatarkit.rtc.AvatarPlayerOptions
import ai.spatius.avatarkit.rtc.RTCLogLevel
import ai.spatius.avatarkit.rtc.providers.AgoraProvider
import android.content.Context
import android.util.Log
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "AvatarRtcSession"

/** Volume above which a remote speaker counts as talking. Agora reports 0..255, and an
 *  idle track sits well under this. */
private const val SPEAKING_VOLUME = 5

/**
 * Avatar RTC session: initialize the SDK → load the avatar → connect RTC → publish the mic.
 *
 * The difference from backend mode is that **the host does not have to feed data**: the
 * agent encodes the animation into the video stream's SEI, the SDK parses it to drive
 * rendering, and audio travels on the RTC audio track. So the SDK can be initialized in
 * its default driving mode — no `DrivingServiceMode.BACKEND` and no keyframe transcoding
 * involved.
 */
class AvatarRtcSession(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var provider: AgoraProvider? = null
    private var player: AvatarPlayer? = null

    /**
     * The render view is owned and reused by the session.
     *
     * Rotation makes Compose recompose and rebuild the AndroidView; creating a new
     * AvatarView each time would leave the established RTC session without a render
     * target — which shows up as the picture and the sound both cutting out mid-sentence.
     * The session outlives the Composable, so the view is only stable when held here.
     */
    private var avatarView: AvatarView? = null

    /** Whether a connection has already been started, so recomposition doesn't start a
     * second agent (every agent bills from the moment it starts). */
    private var hasStarted = false

    /** Returns the render view; created on the first call and reused from then on. */
    /**
     * Whether the avatar's first frame has rendered. Tracked on the session rather than in
     * the view callback: the callback does not fire again when the view is reused, and
     * relying on it alone would leave the rebuilt UI stuck on the waiting overlay forever.
     */
    var hasRendered: Boolean = false
        private set

    /** Notifies of first-frame state changes so the UI can drop the waiting overlay. */
    var onRenderedChange: ((Boolean) -> Unit)? = null

    fun obtainAvatarView(context: Context): AvatarView {
        avatarView?.let { return it }
        return AvatarView(context).also { view ->
            view.onFirstRendering = {
                hasRendered = true
                onRenderedChange?.invoke(true)
            }
            avatarView = view
        }
    }

    /** Session id issued by the backend, used to stop billing on disconnect. */
    private var sessionId: String = ""

    /** The conversational agent's uid, used to tell whether it has joined the channel. */
    private var agentUid: Long = 0

    /** Connection state changes. */
    var onConnectionChange: ((Boolean) -> Unit)? = null

    /** Errors. */
    var onError: ((Throwable) -> Unit)? = null

    val isConnected: Boolean get() = player?.isConnected == true

    /**
     * Turn the SDK's frame rate monitor on or off.
     *
     * The monitor lives on the AvatarController, which AvatarView creates and owns, so it
     * is reachable on the RTC path as well: RTC only changes where frames come from, and
     * every frame still renders through the same entry point that reports them. Off by
     * default, and costs nothing while off.
     */
    fun setPerfMonitor(enabled: Boolean, onInfo: ((FrameRateMonitor.FrameRateInfo) -> Unit)? = null) {
        val controller = avatarView?.controller ?: return
        controller.frameRateMonitorEnabled = enabled
        controller.onFrameRateInfo = if (enabled) onInfo else null
    }

    /**
     * Cumulative playback stats from the RTC player: frames delivered, skipped, recovered
     * and dropped, plus the jitter buffer's own counters.
     *
     * Distinct from the frame rate monitor above, which measures how fast this device
     * renders. These say how much of what the network sent actually arrived in time.
     */
    val playbackStats: AnimationSessionSummary? get() = player?.sessionSummary

    /**
     * Battery and thermal readings, sampled on demand.
     *
     * Created only once something asks for it, so a session nobody is measuring pays
     * nothing. PowerMonitor throttles itself internally, so calling this on a timer is
     * safe.
     */
    private val powerMonitor by lazy { PowerMonitor(context) }

    fun samplePower(): PowerMonitor.Snapshot {
        powerMonitor.sample()
        return powerMonitor.snapshot()
    }

    /**
     * Load the avatar, connect RTC and start publishing the microphone.
     *
     * @param onProgress stage progress for the UI to display
     */
    suspend fun start(
        avatarView: AvatarView,
        onProgress: (String) -> Unit = {},
    ) {
        // Idempotent: recomposition triggers this again, but an agent bills from the
        // moment it starts, so it must not be started twice.
        if (hasStarted) return
        hasStarted = true

        // With no network OkHttp only throws an obscure DNS error, so check first, wait for
        // it to come back, and give a clear message.
        if (!NetworkStatus.isOnline(context)) {
            Log.d(TAG, "offline, waiting for network")
            onProgress(Localization.t.offlineWaiting)
            NetworkStatus.awaitOnline(context)
            Log.d(TAG, "network back online")
        }

        // Start a session to get credentials. The avatar is left to the backend — it lives
        // in .env and the config screen can already change it; it comes back in the
        // response, and the client loads the model from that response so the two sides
        // cannot disagree.
        onProgress(Localization.t.stagePreparing)
        val credentials = AgentClient.createSession(context, Localization.lang.code)
        Log.d(TAG, "session ok: id=${credentials.sessionId} avatar=${credentials.avatarId}")
        sessionId = credentials.sessionId
        agentUid = credentials.agentUid

        // The SDK reads the appId once at initialize and it cannot be changed later. Use
        // the one the backend hands down: it has to match the one the backend used to
        // start the avatar, and a mismatch connects successfully but shows no picture.
        initializeSdk(context, credentials.spatiusAppId, credentials.spatiusRegion)

        // The agent is billing from this point on. Any failure in the steps below must
        // stop it, otherwise it leaves an orphaned agent that nobody owns and that only
        // exits at idle_timeout.
        try {
            onProgress(Localization.t.stageLoadingAvatar)
            val avatar = withContext(Dispatchers.IO) {
                AvatarManager.load(credentials.avatarId) { progress ->
                    when (progress) {
                        is AvatarManager.LoadProgress.Downloading -> {
                            val pct = (progress.progress * 100).toInt()
                            Log.d(TAG, "downloading $pct%")
                            onProgress(Localization.t.stageDownloading(pct))
                        }

                        is AvatarManager.LoadProgress.Completed -> {
                            Log.d(TAG, "avatar download completed")
                            onProgress(Localization.t.stageConnecting)
                        }

                        is AvatarManager.LoadProgress.Failed -> {
                            Log.e(TAG, "avatar load failed", progress.error)
                            onProgress(Localization.t.connectFailed(progress.error.message.orEmpty()))
                        }
                    }
                }
            } ?: error("Avatar load returned null")
            Log.d(TAG, "avatar loaded, initializing view")

            avatarView.init(avatar, scope)
            Log.d(TAG, "view initialized, connecting RTC")

            onProgress(Localization.t.stageConnecting)
            val agoraProvider = AgoraProvider(context.applicationContext)
            val avatarPlayer = AvatarPlayer(
                agoraProvider,
                avatarView,
                AvatarPlayerOptions(logLevel = RTCLogLevel.INFO),
            )
            provider = agoraProvider
            player = avatarPlayer

            avatarPlayer.subscribe { event -> handleEvent(event) }


            avatarPlayer.connect(
                AgoraConnectionConfig(
                    appId = credentials.appId,
                    channel = credentials.channelName,
                    token = credentials.token,
                    uid = credentials.uid,
                )
            )
        } catch (e: Throwable) {
            Log.w(TAG, "start failed after session created, stopping session", e)
            runCatching { AgentClient.stopSession(context, sessionId) }
            sessionId = ""
            player = null
            provider = null
            // Allow a retry after failure, otherwise the guard would block every later
            // attempt too.
            hasStarted = false
            throw e
        }

        onProgress(Localization.t.stageConnected)
        Log.d(TAG, "connected channel=${credentials.channelName} uid=${credentials.uid}")

        // Wait for the conversational agent to join the channel before letting the caller
        // start reading questions.
        awaitAgentJoined()
        onConnectionChange?.invoke(true)
    }

    /**
     * Wait for ConvoAI's conversational agent to join the channel.
     *
     * `connect()` returning only means this device joined the channel; ConvoAI spins the
     * agent up asynchronously after the backend's `/api/session` returns, measured at a
     * second or two later. A say sent during that window still gets a 200 from the
     * backend, but nobody speaks the line — it surfaces as "I'm in the classroom but it
     * won't read the question".
     *
     * The match is on the agent uid the backend assigned: the avatar's streaming endpoint
     * is in the channel too, so keying off "a remote user appeared" would match the wrong
     * one — and that one actually joins first.
     *
     * This adds a handler rather than taking over the delegate: Agora's `addHandler`
     * supports multiple listeners, so the SDK's own is unaffected. A timeout also lets
     * things proceed — an agent that fails to join is the backend's problem, and while the
     * question won't be read aloud, the picture and the mic still work, so it shouldn't
     * drag the whole session down with it.
     */
    private suspend fun awaitAgentJoined(timeoutMs: Long = 20_000) {
        val engine = provider?.getNativeClient() as? RtcEngine ?: return
        if (agentUid <= 0) return

        val joined = CompletableDeferred<Unit>()
        val handler = object : IRtcEngineEventHandler() {
            override fun onUserJoined(uid: Int, elapsed: Int) {
                if (uid.toLong() and 0xFFFFFFFFL == agentUid) joined.complete(Unit)
            }
        }
        engine.addHandler(handler)
        try {
            val ok = withTimeoutOrNull(timeoutMs) { joined.await() } != null
            Log.d(TAG, if (ok) "agent joined uid=$agentUid" else "agent join timed out")
        } finally {
            engine.removeHandler(handler)
        }
    }


    /** Have the avatar speak a piece of text. */
    suspend fun speak(text: String) {
        AgentClient.say(context, sessionId, text)
        Log.d(TAG, "speak: $text")
    }

    /** Interrupt what is being spoken without queueing anything new. */
    suspend fun interrupt() {
        AgentClient.interrupt(context, sessionId)
        Log.d(TAG, "interrupt")
    }

    /**
     * Enter free talk: switch the prompt and speak the transition line; the caller decides
     * when to open the mic.
     *
     * @param custom a character written by the user, for the companion scene. The other
     *   scenes have their persona in the backend and leave this empty.
     * @param memoryKey which stored memory the character reads, for the companion scene.
     *   Empty everywhere else — the other three scenes start from nothing every time.
     */
    suspend fun startFreeTalk(
        persona: String = "freetalk",
        custom: String = "",
        memoryKey: String = "",
    ) {
        AgentClient.startFreeTalk(
            context,
            sessionId,
            Localization.lang.code,
            persona,
            custom,
            memoryKey,
        )
        Log.d(TAG, "free talk started")
    }

    /** What the companion currently remembers under this key. */
    suspend fun fetchMemory(memoryKey: String): AgentClient.MemorySummary =
        AgentClient.fetchMemory(context, memoryKey)

    /** Forget everything stored under this key. */
    suspend fun clearMemory(memoryKey: String) {
        AgentClient.clearMemory(context, memoryKey)
    }

    /**
     * Suspend until the avatar has stopped talking.
     *
     * Agora reports the loudest speakers on an interval once volume indication is turned
     * on, so a stretch with the avatar absent from that report is the end of its line.
     * There is no "finished speaking" event on the player to subscribe to instead, and
     * timing it from the length of the text guesses wrong in both directions — cutting
     * long lines off and leaving gaps after short ones.
     *
     * Matches on any speaker that is not the local user: the conversational agent joins
     * muted and hands its audio to the avatar's endpoint, which is what actually
     * publishes, so keying off the agent's own uid would find silence throughout.
     *
     * Returns if nothing was ever heard, so a failed say cannot wedge the caller.
     */
    suspend fun waitUntilSilent(quietMs: Long = 900, timeoutMs: Long = 30_000) {
        val engine = provider?.getNativeClient() as? RtcEngine ?: return

        val speakingAt = AtomicLong(0)
        val handler = object : IRtcEngineEventHandler() {
            override fun onAudioVolumeIndication(
                speakers: Array<out IRtcEngineEventHandler.AudioVolumeInfo>?,
                totalVolume: Int,
            ) {
                // uid 0 is the local user in this callback; anything else carrying volume
                // is the avatar.
                val remote = speakers?.any { it.uid != 0 && it.volume > SPEAKING_VOLUME }
                if (remote == true) speakingAt.set(System.currentTimeMillis())
            }
        }
        engine.addHandler(handler)
        // 200ms is the shortest interval Agora accepts; smoothing is left at its default.
        engine.enableAudioVolumeIndication(200, 3, false)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            // Wait for speech to start before watching for it to stop: audio takes a
            // moment to arrive after say() returns, and the silence before it would
            // otherwise count as the line already being over.
            while (System.currentTimeMillis() < deadline && speakingAt.get() == 0L) delay(100)
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() - speakingAt.get() >= quietMs) return
                delay(100)
            }
        } finally {
            engine.removeHandler(handler)
            engine.enableAudioVolumeIndication(0, 3, false)
        }
    }

    /**
     * Suspend until the avatar has actually started talking.
     *
     * The mirror image of [waitUntilSilent], for a caption that should land with the voice
     * rather than ahead of it: `speak` returns as soon as the agent accepts the text, which
     * is well before any sound arrives, so a caption shown at that point reads as the avatar
     * being out of sync with itself.
     *
     * Same source as [waitUntilSilent] — Agora's volume report, matching any speaker that is
     * not the local user, since the conversational agent joins muted and the avatar's
     * endpoint is what actually publishes.
     *
     * Returns on timeout as well, so a line that never produces sound cannot wedge the
     * caller on a bubble of dots.
     */
    suspend fun waitUntilAudible(timeoutMs: Long = 8_000) {
        val engine = provider?.getNativeClient() as? RtcEngine ?: return

        val speaking = AtomicBoolean(false)
        val handler = object : IRtcEngineEventHandler() {
            override fun onAudioVolumeIndication(
                speakers: Array<out IRtcEngineEventHandler.AudioVolumeInfo>?,
                totalVolume: Int,
            ) {
                val remote = speakers?.any { it.uid != 0 && it.volume > SPEAKING_VOLUME }
                if (remote == true) speaking.set(true)
            }
        }
        engine.addHandler(handler)
        // 200ms is the shortest interval Agora accepts; smoothing is left at its default.
        engine.enableAudioVolumeIndication(200, 3, false)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (speaking.get()) return
                delay(60)
            }
        } finally {
            engine.removeHandler(handler)
            engine.enableAudioVolumeIndication(0, 3, false)
        }
    }

    /** Open the mic so the student can talk. Requires the record-audio permission. */
    suspend fun publishMic() {
        player?.publishAudio()
        Log.d(TAG, "mic published")
    }

    /** Close the mic. */
    suspend fun unpublishMic() {
        player?.unpublishAudio()
        Log.d(TAG, "mic unpublished")
    }

    private fun handleEvent(event: AvatarPlayerEvent) {
        when (event) {
            is AvatarPlayerEvent.Connected -> onConnectionChange?.invoke(true)
            is AvatarPlayerEvent.Disconnected -> onConnectionChange?.invoke(false)
            // Reconnect on a stalled stream so the picture doesn't freeze.
            is AvatarPlayerEvent.Stalled -> scope.launch {
                runCatching { player?.reconnect() }
                    .onFailure { Log.w(TAG, "reconnect failed", it) }
            }

            else -> Unit
        }
    }

    /**
     * End the session.
     *
     * @param remember which stored memory to append this conversation to, for the companion
     *   scene. Empty — the default — keeps nothing, which is what the other three scenes
     *   want: they start from a blank slate every time.
     */
    suspend fun stop(remember: String = "") {
        runCatching { player?.disconnect() }
            .onFailure { Log.w(TAG, "disconnect failed", it) }
        player = null
        provider = null
        // A session bills continuously from the moment it is established, so it has to be
        // stopped explicitly rather than left to idle_timeout.
        AgentClient.stopSession(context, sessionId, remember)
        sessionId = ""
        agentUid = 0
        hasStarted = false
        // Clear the held view so switching teachers rebuilds it for the new avatar.
        avatarView = null
        hasRendered = false
        onConnectionChange?.invoke(false)
    }

    companion object {
        private val initialized = AtomicBoolean(false)

        /**
         * Global SDK initialization; idempotent.
         *
         * [DrivingServiceMode.RTC] must be declared: AvatarPlayer validates the value, and
         * without it this session's telemetry is attributed to DIRECT and cannot be told
         * apart from self-managed connection traffic.
         */
        fun initializeSdk(context: Context, appId: String, region: String) {
            if (!initialized.compareAndSet(false, true)) return
            AvatarSDK.initialize(
                context.applicationContext,
                appId,
                Configuration(
                    region = region.ifEmpty { "cn-beijing" },
                    drivingServiceMode = DrivingServiceMode.RTC,
                    logLevel = LogLevel.WARNING,
                ),
            )
            Log.d(TAG, "SDK initialized appId=$appId region=$region")
        }
    }
}
