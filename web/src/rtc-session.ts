/**
 * The avatar's RTC session: start a session -> initialize the SDK -> load the avatar
 * -> connect -> publish the microphone.
 *
 * Runs in `DrivingServiceMode.rtc`: the host feeds no driving data. The agent encodes
 * the animation into the video stream's SEI, the SDK parses it out to drive rendering,
 * and audio travels on an RTC track.
 *
 * The two transports (LiveKit and Agora) converge here: the only differences are which
 * provider is used and how the connection config is filled in, and the classroom logic
 * sees exactly the same interface either way. Which one is in use is decided by the
 * backend's `TRANSPORT` and comes back in the session response.
 *
 * Mirrors Android's `AvatarRtcSession.kt`.
 */
import {
  AvatarSDK,
  AvatarManager,
  AvatarView,
  DrivingServiceMode,
  LogLevel,
} from '@spatius/avatarkit'
import {
  AvatarPlayer,
  AgoraProvider,
  LiveKitProvider,
  type RTCConnectionConfig,
} from '@spatius/avatarkit-rtc'
import type { IAgoraRTCRemoteUser } from 'agora-rtc-sdk-ng'
import type { Participant } from 'livekit-client'
import * as Backend from './agent-client'
import { t } from './i18n'

let sdkInitialized = false

/** Volume above which the agent counts as talking. getVolumeLevel reports 0..1, and idle
 *  track noise sits well under this. */
const SPEAKING_LEVEL = 0.02

/**
 * Initialized once globally. The appId comes down with the session from the backend
 * and the SDK reads it only at initialize time, so it cannot be changed afterwards —
 * switching transports needs a page refresh, otherwise the previous transport's appId
 * stays in effect.
 */
async function initializeSdk(appId: string, region?: string): Promise<void> {
  if (sdkInitialized) return
  sdkInitialized = true
  // The RTC driving mode has to be declared — AvatarPlayer validates it — and getting
  // it wrong files this path's telemetry under the wrong category, correctable only at
  // initialize time.
  await AvatarSDK.initialize(appId, {
    drivingServiceMode: DrivingServiceMode.rtc,
    logLevel: LogLevel.warning,
    // On the Agora path the backend returns the access region, so follow it: with the
    // wrong region the avatar model downloads from a different one, which is slow and
    // may not resolve at all. The LiveKit path sends none, so leave it empty and take
    // the SDK's default.
    ...(region ? { region } : {}),
  })
}

/**
 * The two stats shapes, derived from the SDKs' own public members. Neither type is
 * re-exported from its package entry, and reaching into `dist/` for them would tie this
 * demo to a path that is not public API.
 */
type FrameRateInfo = Parameters<
  NonNullable<AvatarView['controller']['onFrameRateInfo']>
>[0]
type SessionSummary = AvatarPlayer['sessionSummary']

export interface SessionCallbacks {
  /** Stage text for the waiting overlay to display */
  onProgress?: (text: string) => void
  /** The avatar's first rendered frame — what dismisses the waiting overlay, rather
   *  than "connected" */
  onRendered?: () => void
}

export class TutoringSession {
  private player: AvatarPlayer | null = null
  private provider: LiveKitProvider | AgoraProvider | null = null
  private avatarView: AvatarView | null = null
  private sessionId = ''
  /** Which transport this session actually used. How the agent is awaited depends on it. */
  private transport: Backend.Transport = 'livekit'
  /** On the Agora path, the conversational agent's uid, used to tell whether it has
   *  joined the channel. */
  private agentUid = 0
  /** Once a connection has been started it is not started again — a session bills from
   *  the moment it is created. */
  private started = false
  private micStream: MediaStream | null = null

  hasRendered = false

  constructor(private readonly callbacks: SessionCallbacks = {}) {}

  get isConnected(): boolean {
    return this.player?.isConnected ?? false
  }

  /** Which transport this session ended up on. The two fail in different places, so a
   *  failure message that says where to look has to know which one is in play. */
  get transportUsed(): Backend.Transport {
    return this.transport
  }

  /**
   * Turn the SDK's frame rate monitor on or off.
   *
   * The monitor lives on the AvatarController, which AvatarView creates and owns, so it
   * is reachable on the RTC path as well: RTC only changes where frames come from, and
   * every frame still renders through the same entry point that reports them. Off by
   * default, and costs nothing while off.
   *
   * Kept as one call rather than exposing the view, so the panel does not have to know
   * how the session is wired.
   */
  setPerfMonitor(enabled: boolean, onInfo?: (info: FrameRateInfo) => void): void {
    const controller = this.avatarView?.controller
    if (!controller) return
    controller.frameRateMonitorEnabled = enabled
    controller.onFrameRateInfo = enabled ? (onInfo ?? null) : null
  }

  /**
   * Cumulative playback stats from the RTC player: frames delivered, skipped, recovered
   * and dropped, plus the jitter buffer's own counters.
   *
   * Distinct from the frame rate monitor above, which measures how fast this machine
   * renders. These say how much of what the network sent actually arrived in time.
   */
  get playbackStats(): SessionSummary | null {
    return this.player?.sessionSummary ?? null
  }

  async start(container: HTMLElement): Promise<void> {
    if (this.started) return
    this.started = true

    const progress = (text: string) => this.callbacks.onProgress?.(text)

    progress(t.value.stagePreparing)
    // avatarId is left to the backend: it lives in .env and the config page can already
    // change it.
    const session = await Backend.createSession()
    this.sessionId = session.sessionId
    this.transport = session.transport
    if (session.transport === 'agora') this.agentUid = session.agentUid

    // Billing has started as of here, so any later failure has to stop the session.
    try {
      // Use the appId the backend sent: it has to match the one the backend used to
      // start the agent, or the view finds no matching publisher.
      await initializeSdk(
        session.spatiusAppId,
        session.transport === 'agora' ? session.spatiusRegion : undefined,
      )

      progress(t.value.stageLoadingAvatar)
      // A cache hit skips the download — re-entering the classroom should not run the
      // progress bar again.
      const cached = AvatarManager.shared.retrieve(session.avatarId)
      const avatar =
        cached ??
        (await AvatarManager.shared.load(session.avatarId, (info) => {
          if (typeof info.progress === 'number') {
            progress(t.value.stageDownloading(Math.round(info.progress)))
          }
        }))
      if (!avatar) throw new Error('Avatar load returned null')

      this.avatarView = new AvatarView(avatar, container)
      this.avatarView.onFirstRendering = () => {
        this.hasRendered = true
        this.callbacks.onRendered?.()
      }

      progress(t.value.stageConnecting)
      // Errors only: once the demo is running the console should not be flooded with
      // per-frame logs. Valid values are 'info' | 'warning' | 'error' | 'none' — set it
      // back to 'info' when troubleshooting.
      const provider =
        session.transport === 'agora' ? new AgoraProvider() : new LiveKitProvider()
      const player = new AvatarPlayer(provider, this.avatarView, {
        logLevel: 'error',
      })
      this.player = player
      this.provider = provider

      // Subscribe before connecting, or the events fired at the moment of connection are
      // missed.
      player.on('stalled', () => {
        // Reconnect automatically when the stream stalls, so the picture does not freeze.
        void player.reconnect().catch((e) => console.warn('[rtc] reconnect failed', e))
      })

      const connection: RTCConnectionConfig =
        session.transport === 'agora'
          ? {
              appId: session.appId,
              channel: session.channelName,
              token: session.token,
              uid: session.uid,
            }
          : {
              url: session.url,
              token: session.token,
              roomName: session.roomName,
            }
      await player.connect(connection)
      progress(t.value.stageConnected)
    } catch (err) {
      await Backend.stopSession(this.sessionId)
      this.sessionId = ''
      this.player = null
      this.avatarView = null
      // Allow a retry after a failure, otherwise the guard blocks every later attempt too.
      this.started = false
      throw err
    }
  }

  /** Used as the page closes: there is no time to await anything on that path, so the id
   *  is taken synchronously and handed to the beacon. */
  get id(): string {
    return this.sessionId
  }

  /**
   * Waits until the agent is ready for the question to be read out.
   *
   * The two transports have different readiness criteria:
   *
   * - **Agora**: wait for the conversational agent to join the channel. A return from
   *   `/join` only means ConvoAI accepted the request; it brings the agent up
   *   asynchronously afterwards, measured at about a second later than the client's own
   *   connection (question sent at connect +9.7s, agent not user-online until +10.6s).
   *   A `speak` sent in that window still gets a 200 from the backend, but nobody says
   *   the line — it presents as "joined the classroom but does not read the question".
   *
   *   It matches on the agent uid the backend assigned: the avatar's publisher is in the
   *   channel too, so judging by "some remote user appeared" picks the wrong one — that
   *   one actually joins first.
   * - **LiveKit**: the agent is a separate process dispatched by LiveKit, and when
   *   `connect()` returns it is usually another second or two from being up. Sending the
   *   question in that window gets a 200 from the backend's RPC, but AgentSession has
   *   not started yet and the line is dropped — again "joined the classroom but does not
   *   read the question". So wait for the agent to announce itself ready (see
   *   backend/agent.py, which sets that attribute after session.start()).
   *
   *   "Joined the room" is not enough: AgentSession is still initializing at that point
   *   and a say arriving then is dropped. Waiting for it to publish an audio track does
   *   not work either — the agent is TTS-driven, so there is no track until it speaks,
   *   and the first thing it should say is the question, which would mean waiting another
   *   ten-odd seconds.
   */
  async waitForAgent(timeoutMs = 20000): Promise<boolean> {
    if (this.transport === 'agora') return this.waitForAgoraAgent(timeoutMs)

    const room = (this.provider as LiveKitProvider | null)?.getNativeClient()
    if (!room) return false

    const ready = (): boolean =>
      [...room.remoteParticipants.values()].some(
        (p) => p.identity.startsWith('agent') && p.attributes?.ready === '1',
      )
    if (ready()) return true

    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        cleanup()
        resolve(false)
      }, timeoutMs)

      const check = (): void => {
        if (!ready()) return
        cleanup()
        resolve(true)
      }
      const cleanup = (): void => {
        clearTimeout(timer)
        room.off('participantConnected', check)
        room.off('participantAttributesChanged', check)
      }

      room.on('participantConnected', check)
      room.on('participantAttributesChanged', check)
    })
  }

  /**
   * Waits for ConvoAI's conversational agent to join the channel.
   *
   * Returns on timeout as well: a no-show is a backend or credentials problem, and while
   * the question then goes unread, the rest of the classroom (video, microphone) still
   * works — no reason to fail the whole session here.
   */
  private waitForAgoraAgent(timeoutMs: number): Promise<boolean> {
    const client = (this.provider as AgoraProvider | null)?.getNativeClient()
    if (!client || !this.agentUid) return Promise.resolve(false)

    const joined = (): boolean =>
      client.remoteUsers.some((u: IAgoraRTCRemoteUser) => Number(u.uid) === this.agentUid)
    if (joined()) return Promise.resolve(true)

    return new Promise((resolve) => {
      const done = (ok: boolean): void => {
        clearTimeout(timer)
        client.off('user-joined', check)
        resolve(ok)
      }
      const timer = setTimeout(() => done(false), timeoutMs)
      const check = (): void => {
        if (joined()) done(true)
      }
      client.on('user-joined', check)
    })
  }

  /**
   * Resolve once the avatar has stopped talking.
   *
   * Polls the level on the agent's audio track and treats a stretch of quiet as the end
   * of the line. There is no "finished speaking" signal on this path — the track is
   * continuous and simply carries near-silence between lines — and timing it from the
   * text length guesses wrong in both directions, cutting long lines off and leaving
   * gaps after short ones.
   *
   * The quiet window has to outlast the pauses *inside* a sentence, which are nowhere
   * near a second, while still turning the queue around promptly once a line really has
   * ended.
   *
   * Also returns when the whole line never produced sound, so a failed `speak` cannot
   * wedge the queue.
   */
  async waitUntilSilent(quietMs = 900, timeoutMs = 30000): Promise<void> {
    const level = this.speechLevel()
    if (!level) return

    const deadline = Date.now() + timeoutMs
    let quietSince: number | null = null
    // Wait for sound to start before watching for it to stop: speech takes a moment to
    // arrive after speak() returns, and without this the silence before it counts as the
    // line already being over.
    let heardAnything = false

    while (Date.now() < deadline) {
      if (level() > SPEAKING_LEVEL) {
        heardAnything = true
        quietSince = null
      } else if (heardAnything) {
        quietSince ??= Date.now()
        if (Date.now() - quietSince >= quietMs) return
      }
      await new Promise((resolve) => setTimeout(resolve, 100))
    }
  }

  /**
   * Resolve once the avatar has actually started talking, for `speakThen` to time a
   * caption against.
   *
   * Agora only: the start is read from the volume on the avatar's publisher, and on
   * LiveKit there is no level to read (see `speechLevel`), so it resolves at once.
   *
   * Resolves on timeout too — a line that never produces sound must not leave the caller
   * waiting on it for ever.
   */
  private async waitUntilSpeaking(timeoutMs = 8000): Promise<void> {
    const level = this.speechLevel()
    if (!level) return

    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (level() > SPEAKING_LEVEL) return
      await new Promise((resolve) => setTimeout(resolve, 60))
    }
  }

  /**
   * Say a line, and call back the moment it can be heard.
   *
   * For captions that should appear with the voice rather than ahead of it. The two
   * transports reach that moment completely differently, which is why this is here rather
   * than left to the caller:
   *
   * - **Agora**: `speak` returns as soon as ConvoAI accepts the text, well before any
   *   sound arrives, so the start is found by watching the level on the avatar's
   *   publisher.
   * - **LiveKit**: there is no level to watch (see `speechLevel`), and `speak` does not
   *   return until the whole line has *finished* playing — the backend's RPC awaits the
   *   speech handle. Waiting on it before showing the caption would hold the text back
   *   for the entire line, so the callback fires when the request goes out and the
   *   caption rides with it.
   *
   * Resolves once the line has finished either way.
   */
  async speakThen(text: string, onAudible: () => void): Promise<void> {
    if (this.transport !== 'agora') {
      // Fires ahead of the audio by however long the backend takes to start playing it,
      // which is the closest this path can get: `speak` resolving already means finished.
      onAudible()
      await this.speak(text)
      return
    }

    await this.speak(text)
    await this.waitUntilSpeaking()
    onAudible()
    await this.waitUntilSilent()
  }

  /**
   * A reading of how loudly the avatar is currently talking, or null when the transport
   * cannot supply one.
   *
   * The two transports expose entirely different objects — Agora hands back a client with
   * `remoteUsers`, LiveKit a room with `remoteParticipants` — so reading one shape on the
   * other path throws, which surfaces as every line cutting off the one before it: the
   * caller's wait collapses and the next `speak` interrupts what is still playing.
   *
   * On both, the sound comes from the avatar's own publisher rather than the agent. The
   * agent stays muted; it hands its TTS audio to Spatius, and Spatius publishes into the
   * channel.
   */
  /**
   * How to tell the avatar has stopped talking, or null when nothing needs waiting on.
   *
   * The two transports need opposite treatment:
   *
   * - **Agora**: poll the volume on the avatar's publisher. The agent itself stays muted
   *   — it hands its TTS audio to Spatius, and Spatius is what publishes into the channel
   *   — so watching the agent's own uid would find no track and read as permanent
   *   silence.
   * - **LiveKit**: nothing to poll. The avatar's audio is consumed by the SDK rather than
   *   subscribed as a room track, so `activeSpeakers` stays empty and every participant
   *   reports an `audioLevel` of exactly zero for the entire time it is talking. The
   *   backend waits on the speech handle instead and only answers the RPC once playback
   *   has finished, which makes `speak` itself the signal.
   */
  private speechLevel(): (() => number) | null {
    if (this.transport !== 'agora') return null

    const client = (this.provider as AgoraProvider | null)?.getNativeClient()
    if (!client) return null
    return () => {
      const publisher = client.remoteUsers?.find(
        (u: IAgoraRTCRemoteUser) => Number(u.uid) !== this.agentUid && !!u.audioTrack,
      )
      return publisher?.audioTrack?.getVolumeLevel() ?? 0
    }
  }

  async speak(text: string): Promise<void> {
    await Backend.say(this.sessionId, text)
  }


  async interrupt(): Promise<void> {
    await Backend.interrupt(this.sessionId)
  }

  async startFreeTalk(
    persona: 'freetalk' | 'host' | 'banker' | 'companion' = 'freetalk',
    custom = '',
    memoryKey = '',
  ): Promise<void> {
    await Backend.startFreeTalk(this.sessionId, persona, custom, memoryKey)
  }

  /**
   * Captures the microphone and publishes it. Unlike the native clients, the web
   * publishAudio wants the host to supply the track — permission and device selection
   * belong to the page in a browser, so the SDK does not reach past it to call
   * getUserMedia.
   */
  async publishMic(): Promise<void> {
    if (!this.player) return
    this.micStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const track = this.micStream.getAudioTracks()[0]
    if (!track) throw new Error('no audio track')
    await this.player.publishAudio(track)
  }

  async unpublishMic(): Promise<void> {
    await this.player?.unpublishAudio()
    // We started the capture, so we have to stop it, or the tab's recording indicator
    // stays lit.
    this.micStream?.getTracks().forEach((t) => t.stop())
    this.micStream = null
  }

  /**
   * The session has to be stopped explicitly rather than left to the server's timeout
   * reclaim — that takes a full minute, billing the whole way.
   */
  async stop(remember: string | false = false): Promise<void> {
    try {
      await this.player?.disconnect()
    } catch (err) {
      console.warn('[rtc] disconnect failed', err)
    }
    this.micStream?.getTracks().forEach((t) => t.stop())
    this.micStream = null
    this.player = null
    this.provider = null
    await Backend.stopSession(this.sessionId, remember)
    this.sessionId = ''
    this.started = false
    this.avatarView = null
    this.hasRendered = false
  }
}
