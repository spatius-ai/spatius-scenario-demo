/**
 * Backend client. The backend runs on the user's own machine (backend/) and
 * holds the RTC credentials in its .env; this side only asks it for a session.
 */
import { BACKEND_URL } from './config'
import { lang } from './i18n'

/**
 * Which RTC transport the avatar joins over. Decided by the backend's `TRANSPORT`,
 * not chosen here — only the backend has the credentials, so picking the one it lacks
 * would not connect anyway.
 */
export type Transport = 'livekit' | 'agora'

interface SessionBase {
  sessionId: string
  avatarId: string
  /** The Spatius app id, used to initialize the SDK. Not the same thing as the RTC
   *  provider's credentials. */
  spatiusAppId: string
}

export interface LiveKitSession extends SessionBase {
  transport: 'livekit'
  url: string
  token: string
  roomName: string
}

export interface AgoraSession extends SessionBase {
  transport: 'agora'
  /** Agora's App ID — a different thing from spatiusAppId. */
  appId: string
  channelName: string
  token: string
  uid: number
  /** The conversational agent's uid, used to tell whether it has joined the channel. */
  agentUid: number
  spatiusRegion: string
}

/**
 * The two transports carry different fields, so this is a discriminated union rather
 * than a bag of optional ones: forgetting to check `transport` is a compile error
 * instead of an undefined that only shows up when connecting.
 */
export type Session = LiveKitSession | AgoraSession

/**
 * Starts a classroom session. **Billing starts on this call** — always call
 * [stopSession] on the way out.
 */
export async function createSession(): Promise<Session> {
  // Send the UI language along: the avatar's persona has to follow it, or after
  // switching to English the teacher answers in Chinese the moment the student
  // unmutes. The questions and the read-aloud text are already English by then, so
  // the LLM path is the only place it shows.
  const res = await fetch(`${BACKEND_URL}/api/session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ lang: lang.value }),
  })
  const body = await res.text()
  if (!res.ok) throw new Error(`session failed: HTTP ${res.status} ${body}`)
  return JSON.parse(body) as Session
}

/**
 * Everything below is fire-and-forget, logging on failure. A line of feedback that
 * does not get spoken should not interrupt the class.
 */
async function post(path: string, payload: Record<string, unknown>): Promise<void> {
  try {
    await fetch(`${BACKEND_URL}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
  } catch (err) {
    console.warn(`[backend] ${path} failed`, err)
  }
}

export async function say(sessionId: string, text: string): Promise<void> {
  if (!sessionId) return
  await post('/api/session/say', { sessionId, text })
}

export async function interrupt(sessionId: string): Promise<void> {
  if (!sessionId) return
  await post('/api/session/interrupt', { sessionId })
}

/**
 * Switches the backend's persona only, without speaking — transition lines are all sent
 * from the client.
 *
 * The persona names which one to switch to, since the two scenes want different things
 * from the same backend: the classroom moves to open questions from a teacher, the live
 * room to a streamer talking to whoever came on the mic.
 */
export async function startFreeTalk(
  sessionId: string,
  persona: 'freetalk' | 'host' | 'banker' | 'companion' = 'freetalk',
  /** A character written by the user, for the companion scene. Ignored by the others. */
  custom = '',
  /** Which stored memory this character reads, for the companion scene. */
  memoryKey = '',
): Promise<void> {
  if (!sessionId) return
  await post('/api/session/free-talk', {
    sessionId,
    lang: lang.value,
    persona,
    custom,
    memoryKey,
  })
}

/**
 * Ends the session, optionally keeping what was said.
 *
 * Only the companion scene remembers. On the Agora path the transcript lives with the
 * agent and goes away when it stops, so collecting it is part of stopping rather than a
 * call of its own.
 */
export async function stopSession(sessionId: string, remember: string | false = false): Promise<void> {
  if (!sessionId) return
  await post('/api/session/stop', {
    sessionId,
    remember: remember ? '1' : '',
    persona: remember || '',
  })
}

/** What the companion remembers so far. */
export interface MemorySummary {
  /** Earlier conversations folded into notes, empty until the first compaction. */
  summary: string
  /** How many turns are held raw, on top of the summary. */
  turns: number
  /** Total characters, which is what the compaction threshold is measured against. */
  size: number
}

export async function fetchMemory(persona = 'friend'): Promise<MemorySummary> {
  try {
    const res = await fetch(`${BACKEND_URL}/api/memory?persona=${encodeURIComponent(persona)}`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return (await res.json()) as MemorySummary
  } catch (err) {
    console.warn('[backend] memory read failed', err)
    return { summary: '', turns: 0, size: 0 }
  }
}

export async function clearMemory(persona = 'friend'): Promise<void> {
  await post('/api/memory/clear', { persona })
}

/**
 * The stop request sent as the page closes.
 *
 * sendBeacon rather than fetch: the page is unloading, and the browser cuts ordinary
 * requests off, which leaves the room up and billing. A beacon is handed to the
 * browser to deliver, so it still goes out after the page is gone.
 *
 * text/plain rather than application/json: JSON triggers a preflight, and unload has
 * no time to complete one. The backend reads the body without looking at
 * Content-Type, so nothing breaks.
 */
export function stopSessionOnUnload(sessionId: string, remember: string | false = false): void {
  if (!sessionId) return
  const payload = JSON.stringify({
    sessionId,
    remember: remember ? '1' : '',
    persona: remember || '',
  })
  const sent =
    typeof navigator.sendBeacon === 'function' &&
    navigator.sendBeacon(`${BACKEND_URL}/api/session/stop`, new Blob([payload], { type: 'text/plain' }))
  if (!sent) void post('/api/session/stop', { sessionId, remember: remember ? '1' : '', persona: remember || '' })
}
