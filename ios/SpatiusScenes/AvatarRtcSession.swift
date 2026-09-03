import AgoraRtcKit
import AvatarKit
import AvatarKitRTC
import Foundation

/// The avatar RTC session: initialize the SDK → load the avatar → connect RTC.
///
/// The difference from backend mode is that **the host does not have to feed data**:
/// the agent encodes the animation into the video stream's SEI, the SDK parses it and
/// drives rendering, and audio travels on the RTC audio track. So initializing the SDK
/// in its default driving mode is enough.
@MainActor
final class AvatarRtcSession: ObservableObject {

    /// Whether the avatar's first frame has rendered. Used to drop the "your teacher is
    /// on the way" overlay.
    @Published var isReady = false
    /// Whether RTC is connected. Speaking keys off this rather than the first-frame
    /// callback — the first frame may render before the callback is registered, in
    /// which case isReady never flips.
    @Published var isConnected = false
    /// Whether we have failed. Not inferred from the status text — the English wording
    /// does not contain the word "failed".
    @Published var hasFailed = false
    /// The current stage message.
    @Published var status = Localization.shared.t.teacherComing

    /// The avatar model handed down by the backend. AvatarStage only builds the view
    /// once it has loaded.
    @Published private(set) var avatar: Avatar?

    private var provider: AgoraProvider?
    private var player: AvatarPlayer?
    /// The session id issued by the backend, used on disconnect to stop billing.
    private var sessionId = ""
    /// The conversational agent's uid, used to tell whether it has joined the channel.
    private var agentUid: UInt = 0
    /// Whether a session has been started / RTC connected, so a SwiftUI rebuild does not
    /// do it twice (a session bills from the moment it is created).
    private var hasPrepared = false
    private var hasStarted = false

    /// The render view is owned and reused by the session.
    ///
    /// Rotation makes SwiftUI rebuild AvatarStage, and creating a fresh AvatarView each
    /// time would leave the established RTC session without a render target — it shows
    /// up as picture and sound cutting out mid-sentence. The session outlives the view,
    /// so keeping the view here is what makes it stable.
    private(set) var avatarView: AvatarView?

    /// Turn the SDK's frame rate monitor on or off.
    ///
    /// The monitor lives on the AvatarController, which AvatarView creates and owns, so it
    /// is reachable on the RTC path as well: RTC only changes where frames come from, and
    /// every frame still renders through the same entry point that reports them. Off by
    /// default, and costs nothing while off.
    func setPerfMonitor(_ enabled: Bool, onInfo: ((FrameRateMonitor.FrameRateInfo) -> Void)? = nil) {
        guard let controller = avatarView?.controller else { return }
        controller.frameRateMonitorEnabled = enabled
        controller.onFrameRateInfo = enabled ? onInfo : nil
    }

    /// Cumulative playback stats from the RTC player: frames delivered, lost, recovered
    /// and dropped, plus the jitter buffer's own counters.
    ///
    /// Distinct from the frame rate monitor above, which measures how fast this device
    /// renders. These say how much of what the network sent actually arrived in time.
    var playbackStats: AnimationSessionSummary? { player?.sessionSummary }

    /// Battery and thermal state, sampled on demand.
    ///
    /// Created only once something asks for it, so a session nobody is measuring does not
    /// switch on battery monitoring. iOS exposes far less than Android here — no die
    /// temperatures and no battery current — but `thermalState` is the system's own
    /// verdict on how hot the device is, which is the question being asked.
    private lazy var powerMonitor = PowerMonitor()

    func samplePower() -> PowerMonitor.Snapshot {
        powerMonitor.sample()
        return powerMonitor.snapshot()
    }

    /// Get the render view; the first call creates it for the given avatar, and every
    /// call after that reuses it.
    func obtainAvatarView(for avatar: Avatar) -> AvatarView {
        if let existing = avatarView { return existing }
        let view = AvatarView(avatar: avatar)
        view.onFirstRendering = { [weak self] in
            Task { @MainActor in self?.isReady = true }
        }
        avatarView = view
        return view
    }

    private static var sdkInitialized = false

    /// Global SDK initialization; idempotent.
    ///
    /// `.rtc` must be declared: AvatarPlayer asserts on this value during init, so
    /// otherwise this session's telemetry is attributed to a different mode — and it
    /// crashes outright.
    static func initializeSDK(appId: String, region: String) {
        guard !sdkInitialized else { return }
        sdkInitialized = true
        AvatarSDK.initialize(
            appID: appId,
            configuration: Configuration(
                region: region.isEmpty ? "cn-beijing" : region,
                drivingServiceMode: .rtc,
                logLevel: .warning
            )
        )
    }

    /// The stored connection credentials, used by ``connect(avatarView:)``.
    private var credentials: SessionCredentials?

    /// Start the session and load the avatar.
    ///
    /// The reverse of the original order: the avatar and the Spatius app id are both
    /// handed down by the backend along with the session (the config page writes them
    /// into its .env), so we need the credentials before we know which model to load and
    /// which appId to initialize with.
    ///
    /// Idempotent: a session bills from the moment it is created, and SwiftUI may fire
    /// the task more than once.
    func prepare() async {
        guard !hasPrepared else { return }
        hasPrepared = true
        do {
            status = Localization.shared.t.stagePreparing
            let credentials = try await AgentClient.createSession(lang: Localization.shared.lang.rawValue)
            self.credentials = credentials
            sessionId = credentials.sessionId
            agentUid = credentials.agentUid

            // The SDK reads appId once at initialize and it cannot be changed later.
            // Use the one the backend handed down: it has to match the one the backend
            // used to start the avatar, and a mismatch connects fine but shows nothing.
            Self.initializeSDK(appId: credentials.spatiusAppId, region: credentials.spatiusRegion)

            // From here on the session is billing, so any later failure must stop it.
            status = Localization.shared.t.stageLoadingAvatar
            avatar = try await AvatarManager.shared.load(id: credentials.avatarId) { progress in
                Task { @MainActor in
                    self.status = Localization.shared.t.stageDownloading(Int(progress.fractionCompleted * 100))
                }
            }
        } catch {
            await AgentClient.stopSession(sessionId: sessionId)
            sessionId = ""
            // Allow a retry after failure, otherwise the guard would block every
            // subsequent attempt too.
            hasPrepared = false
            status = Localization.shared.t.connectFailed(error.localizedDescription)
            hasFailed = true
        }
    }

    /// Connect RTC.
    ///
    /// Idempotent: makeUIView may be called several times, and the guard has to live on
    /// the session — one on the Coordinator would not stop anything, since that is a new
    /// instance every time.
    func connect(avatarView: AvatarView) async {
        guard !hasStarted, let credentials else { return }
        hasStarted = true
        do {
            status = Localization.shared.t.stageConnecting
            let provider = AgoraProvider()
            let player = AvatarPlayer(
                provider: provider,
                avatarView: avatarView,
                options: AvatarPlayerOptions(logLevel: .warning)
            )
            player.subscribe { [weak self] event in
                Task { @MainActor in self?.handle(event: event) }
            }
            self.provider = provider
            self.player = player

            try await player.connect(AgoraConnectionConfig(
                appId: credentials.appId,
                channel: credentials.channelName,
                token: credentials.token.isEmpty ? nil : credentials.token,
                uid: credentials.uid
            ))
            status = Localization.shared.t.stageConnected
            // Treat a connection as good enough to show the picture, so a missed
            // first-frame callback does not leave the overlay up forever.
            isReady = true

            // Wait for the conversational agent to join before letting the layer above
            // start reading questions.
            await awaitAgentJoined()
            isConnected = true
        } catch {
            await AgentClient.stopSession(sessionId: sessionId)
            sessionId = ""
            player = nil
            provider = nil
            hasStarted = false
            status = Localization.shared.t.connectFailed(error.localizedDescription)
            hasFailed = true
        }
    }

    /// Wait for the ConvoAI conversational agent to join the channel.
    ///
    /// `connect()` returning only means this device joined the channel; the agent is
    /// started asynchronously by ConvoAI after the backend's `/api/session` returns,
    /// measured at a second or two later. A say sent during that window still gets a 200
    /// from the backend, but nobody speaks the line — it looks like "we're in the
    /// classroom but the question is never read out".
    ///
    /// Polling rather than a callback. `AvatarPlayerEvent` carries no user-joined event,
    /// and `getUserInfo(byUid:)` answers from the SDK's own member table, which is enough
    /// for a question asked once at startup. (A delegate would work too — the engine takes
    /// several through `addDelegate`, as waitUntilSilent uses — but there is nothing to
    /// gain from the extra machinery here.)
    ///
    /// It matches on the agent uid assigned by the backend: the avatar's publishing
    /// endpoint is in the channel too, so keying off "some remote user exists" matches
    /// the wrong one — that one actually joins first. A timeout also lets us through —
    /// an agent that never joins is the backend's problem; the stem does not get read
    /// out, but the picture and the mic still work, so it should not drag the whole
    /// session into failure.
    private func awaitAgentJoined(timeout: TimeInterval = 20) async {
        guard agentUid > 0, let engine = player?.getNativeClient() as? AgoraRtcEngineKit else { return }
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            var error: AgoraErrorCode = .noError
            if engine.getUserInfo(byUid: agentUid, withError: &error) != nil, error == .noError {
                return
            }
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
        // Deliberately not an error: the session carries on without the agent (see above).
        // Logged because a silent return leaves "it never says anything" with nothing to
        // go on — this is the one place that knows the agent never turned up.
        print("[AvatarRtcSession] agent join timed out uid=\(agentUid); the avatar will render but will not speak")
    }

    /// Interrupt whatever is being spoken, with nothing new to follow.
    func interrupt() async {
        await AgentClient.interrupt(sessionId: sessionId)
    }

    /// Switch to the free-talk prompt. The caller speaks the transition line itself —
    /// the server only changes state; if both sides sent one, the two messages would
    /// reach the agent back to back and interrupt each other.
    ///
    /// - Parameters:
    ///   - custom: a character written by the user, for the companion scene.
    ///   - memoryKey: which stored memory that character reads and writes, for the
    ///     companion scene. Left empty by the other three, which remember nothing.
    func startFreeTalk(
        persona: String = "freetalk",
        custom: String = "",
        memoryKey: String = ""
    ) async {
        await AgentClient.startFreeTalk(
            sessionId: sessionId,
            lang: Localization.shared.lang.rawValue,
            persona: persona,
            custom: custom,
            memoryKey: memoryKey
        )
    }

    /// How many waiters currently need volume reports.
    ///
    /// `enableAudioVolumeIndication` is one global switch, not a subscription, so two
    /// overlapping waiters cannot each turn it off when they are done: the first to finish
    /// would silence the reports the second is still waiting on, and that one then hangs
    /// until its timeout. The customer service scene interrupts constantly — a new question
    /// starts a wait while the interrupted one is still unwinding — so it is reached
    /// routinely there. Counting means the switch only goes off when the last waiter leaves.
    private var volumeWaiters = 0

    /// Start volume reports for one waiter, returning the listener to read.
    private func beginVolumeWatch(on engine: AgoraRtcEngineKit) -> VolumeListener {
        let listener = VolumeListener()
        engine.addDelegate(listener)
        volumeWaiters += 1
        // 200ms is the shortest interval Agora accepts; smoothing left at its default.
        engine.enableAudioVolumeIndication(200, smooth: 3, reportVad: false)
        return listener
    }

    /// Drop one waiter, turning the reports off only once none are left.
    private func endVolumeWatch(on engine: AgoraRtcEngineKit, listener: VolumeListener) {
        engine.removeDelegate(listener)
        volumeWaiters = max(0, volumeWaiters - 1)
        if volumeWaiters == 0 {
            engine.enableAudioVolumeIndication(0, smooth: 3, reportVad: false)
        }
    }

    /// Wait until the avatar has stopped talking.
    ///
    /// Agora reports the loudest speakers on an interval once volume indication is on, so
    /// a stretch with nobody remote in that report is the end of the line. There is no
    /// "finished speaking" signal to subscribe to instead, and timing it from the length
    /// of the text guesses wrong in both directions — cutting long lines off and leaving
    /// gaps after short ones.
    ///
    /// Uses `addDelegate` rather than taking over `delegate`: the engine supports several,
    /// so `AgoraProvider` keeps receiving everything it needs.
    ///
    /// Returns if nothing was ever heard, so a failed say cannot wedge the caller.
    func waitUntilSilent(quietFor: TimeInterval = 0.9, timeout: TimeInterval = 30) async {
        guard let engine = player?.getNativeClient() as? AgoraRtcEngineKit else { return }

        let listener = beginVolumeWatch(on: engine)
        defer { endVolumeWatch(on: engine, listener: listener) }

        let deadline = Date().addingTimeInterval(timeout)
        // Wait for speech to start before watching for it to stop: audio takes a moment to
        // arrive after say() returns, and the silence before it would otherwise count as
        // the line already being over.
        while Date() < deadline, listener.lastSpokeAt == nil {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        while Date() < deadline {
            if let last = listener.lastSpokeAt, Date().timeIntervalSince(last) >= quietFor {
                return
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }

    /// Wait until the avatar can actually be heard starting a line.
    ///
    /// The counterpart to ``waitUntilSilent``, and for the same reason there is no signal
    /// to subscribe to: volume reports are the only evidence that audio has begun. A
    /// caller that has just sent text has it on screen instantly while the voice is still
    /// a second or two out, and a caption that lands ahead of the audio reads as the
    /// avatar being out of sync with itself.
    ///
    /// Returns on timeout rather than throwing, so a line that is never spoken — a failed
    /// say, an agent that never joined — leaves the caller showing its text rather than
    /// waiting on dots forever.
    /// The timeout and the polling interval match the other two clients, so the same
    /// backend failure keeps a caption waiting for the same length of time everywhere.
    func waitUntilSpeaking(timeout: TimeInterval = 8) async {
        guard let engine = player?.getNativeClient() as? AgoraRtcEngineKit else { return }

        let listener = beginVolumeWatch(on: engine)
        defer { endVolumeWatch(on: engine, listener: listener) }

        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline, listener.lastSpokeAt == nil, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 60_000_000)
        }
    }

    /// Go live so the student can speak. Requires the recording permission.
    func publishMic() async {
        try? await player?.publishAudio()
    }

    /// Mute the mic.
    func unpublishMic() async {
        await player?.unpublishAudio()
    }

    /// Have the avatar speak a piece of text.
    func speak(_ text: String) async {
        await AgentClient.say(sessionId: sessionId, text: text)
    }

    private func handle(event: AvatarPlayerEvent) {
        switch event {
        case .stalled:
            // Reconnect on a stalled stream so the picture does not freeze.
            Task { try? await player?.reconnect() }
        default:
            break
        }
    }

    /// Disconnect and stop the agent.
    ///
    /// - Parameter remember: which memory to keep this conversation in, or nil to keep
    ///   nothing. Only the companion scene passes one. The transcript lives with the agent
    ///   on the Agora path and goes away when it stops, so it is collected in the same call
    ///   that ends the session rather than in one of its own.
    func stop(remember: String? = nil) async {
        await player?.disconnect()
        player = nil
        provider = nil
        // A session bills continuously from creation, so it has to be stopped
        // explicitly — relying on idle_timeout alone is not enough.
        await AgentClient.stopSession(sessionId: sessionId, remember: remember)
        sessionId = ""
        agentUid = 0
        credentials = nil
        avatar = nil
        hasPrepared = false
        isReady = false
        isConnected = false
        hasStarted = false
        // Drop the retained view so switching teachers rebuilds it for the new avatar.
        avatarView = nil
    }
}

/// Listens for volume reports and nothing else.
///
/// A separate object because `AgoraProvider` is the engine's primary delegate and does not
/// forward this callback. Attached with `addDelegate`, which the engine supports alongside
/// its existing one.
private final class VolumeListener: NSObject, AgoraRtcEngineDelegate {
    /// When a remote speaker was last heard, or nil if none has been.
    private(set) var lastSpokeAt: Date?

    func rtcEngine(
        _ engine: AgoraRtcEngineKit,
        reportAudioVolumeIndicationOfSpeakers speakers: [AgoraRtcAudioVolumeInfo],
        totalVolume: Int
    ) {
        // uid 0 is the local user in this callback; anything else carrying volume is the
        // avatar's publisher. The conversational agent joins muted and hands its audio to
        // that endpoint, so matching on the agent's own uid would find silence throughout.
        if speakers.contains(where: { $0.uid != 0 && $0.volume > 5 }) {
            lastSpokeAt = Date()
        }
    }
}
