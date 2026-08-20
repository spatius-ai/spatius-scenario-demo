<script setup lang="ts">
/**
 * The live room: the stream on the left with danmaku drifting over it, the chat list and
 * gift bar on the right.
 *
 * The audience runs itself. Viewers arrive on an uneven timer with a message already
 * paired to the host's reply (see chat-data.ts), and now and then one sends a gift
 * instead. Replies are read aloud through `speak`, which is verbatim TTS and never
 * involves the LLM — canned text is what keeps a reply inside a second, fast enough to
 * still be answering the message the viewer can see on screen.
 *
 * Shares the session layer with the classroom: both scenes are one avatar over RTC, and
 * the only difference is what it is asked to say.
 */
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as Backend from './agent-client'
import { TutoringSession } from './rtc-session'
import { asset } from './asset'
import { lang, t } from './i18n'
import LangToggle from './LangToggle.vue'
import PerfPanel from './PerfPanel.vue'
import {
  giftCatalogue,
  giftMessage,
  randomChat,
  randomGift,
  randomViewer,
  type ChatMessage,
  type Gift,
} from './chat-data'

const emit = defineEmits<{ exit: [] }>()

const stageRef = ref<HTMLElement | null>(null)
const chatRef = ref<HTMLElement | null>(null)

const connected = ref(false)
const overlayError = ref('')
const overlayFailed = ref(false)
const overlayText = computed(() =>
  overlayError.value ? t.value.connectFailed(overlayError.value) : t.value.enteringLive,
)

/** The chat list, capped so a long session cannot grow the DOM without bound. */
const messages = ref<ChatMessage[]>([])
const MAX_MESSAGES = 80

/**
 * Danmaku currently crossing the video.
 *
 * Each carries its own speed and size. Uniform ones move like a marquee — the eye picks
 * up the shared rhythm immediately and the whole layer reads as one animation instead of
 * many people typing.
 */
const danmaku = ref<
  Array<{ id: number; text: string; lane: number; hue: number; seconds: number; size: number }>
>([])
// More lanes and a faster crossing than a slow room would need: at burst rate a long
// dwell time stacks lines on top of each other until the video is unreadable.
const DANMAKU_LANES = 7
/** Crossing time, drawn per line so they overtake each other. */
const DANMAKU_MIN_SECONDS = 5
const DANMAKU_MAX_SECONDS = 9
/** Cap on how many are in flight. A burst can outrun the crossing time, and past this
 *  the video disappears behind text; the oldest is dropped to make room. */
const MAX_DANMAKU = 18
/** Music sits under the host rather than beside them — loud enough to fill the silence
 *  between replies, quiet enough not to compete with what is being said. */
const MUSIC_VOLUME = 0.25

/**
 * Background music.
 *
 * Played locally through an <audio> element rather than mixed into the RTC track. Two
 * reasons: a room's music is something each viewer hears on their own side anyway, and
 * mixing it upward would sit on the same track the silence detection reads, so the host
 * would register as talking forever and never move on to the next reply.
 *
 * Starts on its own. A browser blocks audible autoplay until the page has been
 * interacted with, but entering the room means the user has just clicked through the
 * config screen, which counts — so playback normally succeeds. If it is refused anyway
 * the toggle stays off and one click starts it.
 */
const musicRef = ref<HTMLAudioElement | null>(null)
const musicOn = ref(false)

async function startMusic(): Promise<void> {
  const el = musicRef.value
  if (!el) return
  el.volume = MUSIC_VOLUME
  try {
    await el.play()
    musicOn.value = true
  } catch {
    // Autoplay refused — leave it off rather than showing it as playing silently.
    musicOn.value = false
  }
}

function toggleMusic(): void {
  const el = musicRef.value
  if (!el) return
  if (musicOn.value) {
    el.pause()
    musicOn.value = false
  } else {
    void startMusic()
  }
}

/**
 * Requesting the mic.
 *
 * Modelled on how a real room does it: the viewer asks, waits to be let in, and only
 * then is anyone listening. The wait is fake — there is nobody on the other side to
 * approve it — but going straight from a click to an open mic reads as a button that
 * toggles something rather than a request being granted.
 */
type MicState = 'idle' | 'pending' | 'live'
const micState = ref<MicState>('idle')
const MIC_APPROVAL_MS = 3000

/** What the viewer is typing. Their own lines are answered like anyone else's. */
const draft = ref('')

/** A viewer count that drifts, so the room does not look frozen. */
const viewers = ref(1200 + Math.floor(Math.random() * 800))

const session = shallowRef<TutoringSession | null>(null)
let chatTimer: number | null = null
let viewerTimer: number | null = null

// ---------------------------------------------------------------- speaking

/**
 * Picking what to answer.
 *
 * A host does not work through a queue. They finish a line, glance at the screen, and
 * pick up whatever happens to be there — so this holds the recent messages and chooses
 * one at random, rather than answering in order. Anything that scrolled past unanswered
 * stays unanswered, which is exactly what happens in a real room.
 *
 * Gifts are the exception and are answered next: a thank-you that arrives after the
 * animation has faded has missed its moment.
 */
const answerable: ChatMessage[] = []
const ANSWERABLE_WINDOW = 12
let gifted: ChatMessage | null = null
let hosting = false

function offer(message: ChatMessage): void {
  if (!message.reply) return
  if (message.gift) {
    gifted = message
    return
  }
  answerable.push(message)
  // Only the last few are still on screen; answering something from a minute ago reads as
  // the host being out of step with the room.
  if (answerable.length > ANSWERABLE_WINDOW) answerable.shift()
}

/**
 * Speak one line, pause, then look again — the loop the host runs for the whole session.
 *
 * The pause is what keeps it from sounding mechanical: back to back, every reply lands
 * the instant the last one ends, which no person does.
 */
async function hostLoop(): Promise<void> {
  if (hosting) return
  hosting = true
  while (connected.value) {
    // Say nothing at all while a viewer is on the mic, or while they are waiting to be
    // let in. Reading canned lines over a real conversation makes the host talk across
    // the person who just got permission to speak, and the free-talk persona is answering
    // them at the same time — two voices from one avatar, interrupting each other.
    if (micState.value === 'idle') {
      const next =
        gifted ??
        (answerable.length ? answerable[Math.floor(Math.random() * answerable.length)] : null)
      gifted = null
      if (next) {
        answerable.length = 0
        try {
          await session.value?.speak(next.reply!)
          await session.value?.waitUntilSilent()
        } catch (err) {
          console.warn('[live] speak failed', err)
        }
      }
    }
    await new Promise((resolve) => setTimeout(resolve, Math.random() * 3000))
  }
  hosting = false
}

/**
 * The line the host opens with.
 *
 * Sent before the audience timer starts, so the room begins with someone talking rather
 * than with a silent face waiting to be spoken to.
 */
async function openWithGreeting(): Promise<void> {
  try {
    await session.value?.speak(t.value.greeting)
    await session.value?.waitUntilSilent()
  } catch (err) {
    console.warn('[live] greeting failed', err)
  }
}

/**
 * Ask to join, wait, then open the mic.
 *
 * Switching the backend to its free-talk persona is what makes the difference: until
 * then every line is canned text read verbatim, and only here does the LLM start
 * answering what is actually said.
 */
async function requestMic(): Promise<void> {
  if (micState.value !== 'idle') {
    await endMic()
    return
  }
  micState.value = 'pending'

  try {
    // Switch the persona and open the mic straight away rather than after the wait. The
    // wait is only there so being let in feels like a request rather than a toggle, and
    // spending it idle wastes it: ASR and the LLM are not touched until audio starts
    // arriving, so doing this first means they are warm by the time anyone speaks. The
    // classroom gets this for free — it plays a transition line while switching, which
    // covers the same ground.
    await session.value?.startFreeTalk('host')
    await session.value?.publishMic()

    // The host acknowledging the request is what fills the wait, and it doubles as the
    // reason it no longer feels like dead air.
    await session.value?.speak(t.value.micWelcome)
    await session.value?.waitUntilSilent()
  } catch (err) {
    console.warn('[live] mic failed', err)
    micState.value = 'idle'
    return
  }

  if (micState.value === 'pending') micState.value = 'live'
}

async function endMic(): Promise<void> {
  micState.value = 'idle'
  try {
    await session.value?.unpublishMic()
  } catch (err) {
    console.warn('[live] unpublish failed', err)
  }
}

/** Send what the viewer typed. It joins the pool the host picks from, like any other
 *  message — but with no canned reply attached, since nobody wrote one for it. */
function sendDraft(): void {
  const text = draft.value.trim()
  if (!text) return
  draft.value = ''
  post({
    id: Date.now(),
    viewer: { name: t.value.you, hue: 265 },
    text,
    reply: null,
    gift: null,
  })
}

// ---------------------------------------------------------------- audience

function post(message: ChatMessage): void {
  messages.value.push(message)
  if (messages.value.length > MAX_MESSAGES) {
    messages.value.splice(0, messages.value.length - MAX_MESSAGES)
  }

  const seconds =
    DANMAKU_MIN_SECONDS + Math.random() * (DANMAKU_MAX_SECONDS - DANMAKU_MIN_SECONDS)
  danmaku.value.push({
    id: message.id,
    text: message.gift ? `${message.gift.icon} ${message.text}` : message.text,
    lane: Math.floor(Math.random() * DANMAKU_LANES),
    hue: message.viewer.hue,
    seconds,
    // Small spread only. Past roughly this much the big ones read as emphasis the sender
    // never intended.
    size: 13 + Math.random() * 5,
  })
  if (danmaku.value.length > MAX_DANMAKU) {
    danmaku.value.splice(0, danmaku.value.length - MAX_DANMAKU)
  }
  // Removed on its own schedule, since each line crosses at its own speed.
  window.setTimeout(() => {
    danmaku.value = danmaku.value.filter((d) => d.id !== message.id)
  }, seconds * 1000)

  offer(message)
}

/**
 * The audience arrives in bursts, not on a beat.
 *
 * A real room surges and then goes quiet: something lands, twenty people react at once,
 * then nothing for a few seconds. A steady interval — even a fast one — reads as a
 * machine dropping text on a timer, so the gap is redrawn per message and the room swings
 * between flooding and lulling.
 */
let burstLeft = 0

function scheduleChat(): void {
  // Inside a burst the lines come almost on top of each other; between bursts the room
  // goes quiet long enough to notice.
  const delay = burstLeft > 0 ? 250 + Math.random() * 500 : 1500 + Math.random() * 3000
  if (burstLeft > 0) burstLeft -= 1
  else if (Math.random() < 0.45) burstLeft = 3 + Math.floor(Math.random() * 8)

  chatTimer = window.setTimeout(() => {
    // Gifts are the rare event, which is what makes one worth breaking off to thank. At
    // burst rate even a small share of them arrives constantly, and a host doing nothing
    // but thanking people never gets to talk.
    if (Math.random() < 0.02) post(giftMessage(lang.value, randomGift(lang.value)))
    else post(randomChat(lang.value))
    scheduleChat()
  }, delay)
}

function sendGift(gift: Gift): void {
  post(giftMessage(lang.value, gift, randomViewer(lang.value)))
}

// Keep the newest message in view, but only when already at the bottom: yanking the list
// down while someone is scrolled up reading is worse than letting it run on.
watch(
  () => messages.value.length,
  () => {
    const el = chatRef.value
    if (!el) return
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    if (atBottom) requestAnimationFrame(() => (el.scrollTop = el.scrollHeight))
  },
)

// ---------------------------------------------------------------- lifecycle

onMounted(async () => {
  session.value = new TutoringSession()
  try {
    if (!stageRef.value) throw new Error('stage not mounted')
    await session.value.start(stageRef.value)
    // Wait for the agent before letting the audience in: a reply sent before it is ready
    // gets a 200 from the backend and is then dropped, so the first few messages would
    // go silently unanswered.
    await session.value.waitForAgent()
    connected.value = true
    // Only once the room is actually up. Started earlier the music plays over a loading
    // screen, which reads as a page making noise rather than a room being open.
    void startMusic()
    // The audience starts arriving immediately — a room that opens with nobody in the
    // chat looks dead, and viewers turning up while the host is still introducing
    // themselves is exactly what happens.
    scheduleChat()
    // The replies are what has to wait. Started alongside the introduction, the first
    // message picked up would `speak` over it and cut it off partway through.
    await openWithGreeting()
    void hostLoop()
    viewerTimer = window.setInterval(() => {
      viewers.value = Math.max(800, viewers.value + Math.floor(Math.random() * 21) - 8)
    }, 3000)
  } catch (err) {
    overlayError.value = (err as Error).message
    overlayFailed.value = true
  }
})

/**
 * The fallback for closing the tab, reloading, or being reclaimed after backgrounding: a
 * fetch started in the unmount hook is cut off by the browser, which leaves the room up
 * and billing.
 */
function stopOnUnload(): void {
  if (session.value) Backend.stopSessionOnUnload(session.value.id)
}

onMounted(() => window.addEventListener('pagehide', stopOnUnload))

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', stopOnUnload)
  if (chatTimer !== null) clearTimeout(chatTimer)
  if (viewerTimer !== null) clearInterval(viewerTimer)
  // Stops the capture as well as the publish, or the tab keeps its recording indicator
  // lit after the room is gone.
  micState.value = 'idle'
  void session.value?.unpublishMic()
  void session.value?.stop()
  session.value = null
})
</script>

<template>
  <div class="room" :style="{ '--stage-bg': `url(${asset('live-room-bg.jpg')})` }">
    <div v-if="!connected" class="page-overlay">
      <div v-if="!overlayFailed" class="spinner" />
      <span>{{ overlayText }}</span>
    </div>

    <section class="stage-column">
      <div class="stage-wrap">
        <div class="stage-frame">
          <div ref="stageRef" class="stage" />

          <!-- Danmaku sits over the video rather than beside it: that overlap is what
               makes a stream read as live, and the list on the right is the record for
               anything that drifts past too fast to catch. -->
          <div class="danmaku-layer">
            <span
              v-for="d in danmaku"
              :key="d.id"
              class="danmaku"
              :style="{
                top: `${6 + d.lane * 11}%`,
                color: `hsl(${d.hue} 70% 85%)`,
                animationDuration: `${d.seconds}s`,
                fontSize: `${d.size}px`,
              }"
            >
              {{ d.text }}
            </span>
          </div>

          <div class="badges">
            <span class="live-badge">{{ t.liveBadge }}</span>
            <span class="viewer-badge">{{ t.viewerCount(viewers) }}</span>
          </div>

          <button
            class="music"
            :class="{ on: musicOn }"
            :aria-label="t.music"
            :title="t.music"
            @click="toggleMusic"
          >
            {{ musicOn ? '♪' : '♪̸' }}
          </button>

          <audio ref="musicRef" :src="asset('bgm/lounge-1.mp3')" loop preload="auto" />

          <!-- Over the video, opposite the badges. Asking to speak is something you do to
               the stream, so the control belongs on it rather than in the chat column. -->
          <button class="mic" :class="micState" :disabled="!connected" @click="requestMic">
            <span class="mic-icon">{{ micState === 'live' ? '●' : '🎙' }}</span>
            <span class="mic-text">
              {{
                micState === 'idle'
                  ? t.micIdle
                  : micState === 'pending'
                    ? t.micPending
                    : t.micLive
              }}
            </span>
          </button>
        </div>
      </div>

      <!-- Gifts sit under the video, where attention already is, rather than in the chat
           column which is a running record. -->
      <div class="gift-bar">
        <span class="gift-label">{{ t.giftBar }}</span>
        <button
          v-for="g in giftCatalogue(lang)"
          :key="g.name"
          class="gift"
          :disabled="!connected"
          @click="sendGift(g)"
        >
          <span class="gift-icon">{{ g.icon }}</span>
          <span class="gift-name">{{ g.name }}</span>
          <span class="gift-value">{{ g.value }}</span>
        </button>
      </div>
    </section>

    <aside class="chat-column">
      <header class="chat-header">
        <button class="back" :aria-label="t.leaveLive" @click="emit('exit')">←</button>
        <LangToggle />
      </header>

      <div ref="chatRef" class="chat-list">
        <p v-for="m in messages" :key="m.id" class="chat-row" :class="{ gift: m.gift }">
          <span class="avatar" :style="{ background: `hsl(${m.viewer.hue} 60% 60%)` }">
            {{ m.viewer.name.slice(0, 1) }}
          </span>
          <span class="body">
            <span class="name">{{ m.viewer.name }}</span>
            <span class="text">
              <template v-if="m.gift">{{ m.gift.icon }} </template>{{ m.text }}
            </span>
          </span>
        </p>
      </div>

      <!-- Composer and mic sit at the foot of the list, where the room's own input
           belongs — the gift bar under the video is for reacting, this is for talking. -->
      <footer class="composer">
        <div class="compose-row">
          <input
            v-model="draft"
            :placeholder="t.chatPlaceholder"
            :disabled="!connected"
            maxlength="60"
            @keyup.enter="sendDraft"
          />
          <button class="send" :disabled="!connected || !draft.trim()" @click="sendDraft">
            {{ t.send }}
          </button>
        </div>
      </footer>
    </aside>

    <PerfPanel :session="session" />
  </div>
</template>

<style scoped>
.room {
  display: flex;
  gap: 16px;
  height: 100vh;
  padding: 16px;
  box-sizing: border-box;
  background: var(--surface-variant);
}

/* Stage ----------------------------------------------------------------- */
/* The video column takes what is left after the chat, and is allowed to shrink: without
   min-width the portrait frame's own width becomes the floor and pushes the chat column
   off the row entirely. */
/* Sized to what the square actually needs rather than taking the whole remaining row.
   The frame is bounded by the row's height, so a greedy column stops growing at that
   point and leaves the surplus as empty margin either side of the video — width that
   belongs to the chat. */
.stage-column {
  flex: 0 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* The frame is the positioning context for everything laid over the video — danmaku and
   the badges anchor to it rather than to the page. */
/* Square, with the frame's own padding as the decorative border. A browser window is
   landscape, so the portrait shape a phone stream uses would leave tall empty margins on
   both sides and squeeze everything else. */
.stage-frame {
  position: relative;
  aspect-ratio: 1;
  /* Bounded on both axes so the square fits whichever runs out first — the column's
     width on a narrow window, its height on a short one. */
  max-width: 100%;
  max-height: 100%;
  border-radius: 20px;
  overflow: hidden;
  /* The room behind the host. Cropped from the centre, which keeps the arch and its
     light — the part of the picture that reads as a room — while the square trims the
     portrait source top and bottom. */
  background: #111 var(--stage-bg) center / cover no-repeat;
  /* The border is the decoration: a soft ring plus a lifted shadow, so the stream reads
     as a framed picture rather than a video tag dropped on the page. */
  box-shadow:
    0 0 0 1px var(--outline-variant),
    0 0 0 8px var(--surface),
    0 0 0 9px var(--outline-variant),
    0 18px 40px rgba(0, 0, 0, 0.13);
}

/* Centres the square in whatever space is left after the gift bar. */
.stage-wrap {
  flex: 1;
  min-height: 0;
  display: grid;
  place-items: center;
  /* Room for the ring, which is drawn outside the frame's own box. */
  padding: 10px;
}

.stage {
  width: 100%;
  height: 100%;
}

.danmaku-layer {
  position: absolute;
  inset: 0;
  overflow: hidden;
  /* Clicks belong to the video, not to text drifting across it. */
  pointer-events: none;
}

.danmaku {
  position: absolute;
  left: 100%;
  white-space: nowrap;
  font-weight: 500;
  /* An outline rather than a panel: danmaku has to stay readable over whatever the video
     happens to be showing, and a background band would cover it. */
  text-shadow:
    0 1px 2px rgba(0, 0, 0, 0.9),
    0 0 6px rgba(0, 0, 0, 0.6);
  animation-name: drift;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
}

@keyframes drift {
  to {
    transform: translateX(calc(-100% - 100vw));
  }
}

/* Sits opposite the badges, over the video: it belongs to the stream rather than to the
   page around it. */
.music {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  color: rgba(255, 255, 255, 0.55);
  font-size: 14px;
  cursor: pointer;
  line-height: 1;
}

.music.on {
  color: #fff;
  background: rgba(0, 0, 0, 0.6);
}

.badges {
  position: absolute;
  top: 12px;
  left: 12px;
  display: flex;
  gap: 8px;
  font-size: 12px;
}

.live-badge {
  background: #e0245e;
  color: #fff;
  padding: 3px 10px;
  border-radius: 999px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.viewer-badge {
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  padding: 3px 10px;
  border-radius: 999px;
}

/* Gifts ----------------------------------------------------------------- */
.gift-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 12px;
  background: var(--surface);
  border-radius: 12px;
}

.gift-label {
  font-size: 13px;
  color: #6b7280;
  margin-right: 4px;
}

.gift {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  min-width: 58px;
  padding: 6px 8px;
  border: 1px solid var(--outline-variant);
  border-radius: 10px;
  background: var(--surface-variant);
  color: var(--on-surface-variant);
  cursor: pointer;
  font-family: inherit;
}

.gift:hover:not(:disabled) {
  border-color: var(--primary);
}

.gift:disabled {
  opacity: 0.5;
  cursor: default;
}

.gift-icon {
  font-size: 20px;
}

.gift-name {
  font-size: 11px;
}

.gift-value {
  font-size: 10px;
  color: #6b7280;
}

/* Chat ------------------------------------------------------------------ */
.chat-column {
  /* Takes whatever the video does not need, down to a floor that keeps messages
     readable. The list is the second half of the room, not a panel beside the video. */
  flex: 1 1 auto;
  min-width: 320px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-radius: 12px;
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid var(--outline-variant);
}

.back {
  border: none;
  background: transparent;
  color: var(--on-surface-variant);
  font-size: 18px;
  cursor: pointer;
  padding: 0;
  line-height: 1;
}

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.chat-row {
  display: flex;
  gap: 8px;
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
}

/* Gift messages are tinted so they stand out in a fast-moving list — they are the ones
   the host reacts to first. */
.chat-row.gift {
  background: color-mix(in srgb, var(--primary) 12%, transparent);
  border-radius: 8px;
  padding: 6px 8px;
  margin: -2px -4px;
}

.avatar {
  flex: none;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 600;
}

.body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.name {
  font-size: 12px;
  color: #6b7280;
}

.text {
  word-break: break-word;
}

/* Composer -------------------------------------------------------------- */
.composer {
  border-top: 1px solid var(--outline-variant);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* A floating capsule rather than a bar button: it sits over video, so it carries its own
   dark backing and does not inherit the panel styling of the chat column. */
.mic {
  position: absolute;
  right: 12px;
  bottom: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: none;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  backdrop-filter: blur(6px);
  transition: background 0.18s ease;
}

.mic:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.72);
}

.mic-icon {
  font-size: 12px;
  line-height: 1;
}

/* Waiting to be let in: pulsing says "in progress" without needing a spinner. */
.mic.pending {
  opacity: 0.85;
}

.mic.pending .mic-icon {
  animation: pulse 1.1s ease-in-out infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}

/* Live reads as recording — the same red dot every streaming tool uses. */
.mic.live {
  background: #e0245e;
}

.mic.live .mic-icon {
  animation: pulse 1.4s ease-in-out infinite;
}

.mic:disabled {
  opacity: 0.4;
  cursor: default;
}

.compose-row {
  display: flex;
  gap: 8px;
}

.compose-row input {
  flex: 1;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--outline-variant);
  border-radius: 9px;
  background: var(--surface-variant);
  color: var(--on-surface-variant);
  font-size: 13px;
  font-family: inherit;
}

.send {
  padding: 8px 14px;
  border: none;
  border-radius: 9px;
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}

.send:disabled {
  opacity: 0.45;
  cursor: default;
}

/* Overlay --------------------------------------------------------------- */
.page-overlay {
  position: fixed;
  inset: 0;
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  background: var(--surface-variant);
  color: var(--on-surface-variant);
}

.spinner {
  width: 26px;
  height: 26px;
  border: 3px solid var(--outline-variant);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* Narrow screens: chat moves below the video, the shape a phone would use. */
@media (max-width: 900px) {
  .room {
    flex-direction: column;
  }

  .chat-column {
    width: auto;
    height: 220px;
  }
}
</style>
