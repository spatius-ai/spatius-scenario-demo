<script setup lang="ts">
/**
 * Bank customer service: the avatar over the bank hall, with the topics on offer and
 * whatever is currently being explained on a panel beside them.
 *
 * On a desktop the two sit side by side, the avatar holding the left two thirds. Below
 * the breakpoint it stacks into the phone shape a banking assistant actually has: the
 * avatar fills the screen and the panel floats over the bottom of it.
 *
 * Two ways to ask. The menu is canned text read verbatim through `speak` — see
 * service-data.ts for why accuracy matters more here than in the other scenes — and
 * questions can be asked over and over, each one cutting off whatever is still playing.
 * The button under the avatar opens the mic instead and hands the whole thing to the
 * LLM, with the same business knowledge carried as its persona.
 */
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import * as Backend from './agent-client'
import { TutoringSession } from './rtc-session'
import { asset } from './asset'
import { lang, t } from './i18n'
import LangToggle from './LangToggle.vue'
import PerfPanel from './PerfPanel.vue'
import {
  allQuestions,
  categories,
  type ServiceCategory,
  type ServiceQuestion,
} from './service-data'

const emit = defineEmits<{ exit: [] }>()

const stageRef = ref<HTMLElement | null>(null)
const transcriptRef = ref<HTMLElement | null>(null)

const connected = ref(false)
const overlayError = ref('')
const overlayFailed = ref(false)
const overlayText = computed(() =>
  overlayError.value ? t.value.connectFailed(overlayError.value) : t.value.enteringService,
)

/** One line of the exchange, shown on the panel above the question chips. */
interface Turn {
  id: number
  who: 'customer' | 'agent'
  text: string
  /** True while the reply is on its way: the bubble shows dots instead of the text. */
  pending?: boolean
}

const turns = ref<Turn[]>([])
let nextTurnId = 1

/** Keep the newest line in view. The transcript is short by design, so anything more than
 *  a couple of exchanges scrolls. */
function scrollToEnd(): void {
  requestAnimationFrame(() => {
    const el = transcriptRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function addTurn(who: Turn['who'], text: string, pending = false): number {
  const id = nextTurnId++
  turns.value.push({ id, who, text, pending })
  scrollToEnd()
  return id
}

/** Swap a pending bubble for the text it was holding a place for. */
function settleTurn(id: number): void {
  const turn = turns.value.find((t) => t.id === id)
  if (turn) turn.pending = false
  // Three dots become a paragraph, so the bubble grows and pushes its own bottom out of
  // view — scrolling only when the bubble first went up leaves the reply half off-screen.
  scrollToEnd()
}

/** Drop a pending bubble whose reply never arrived, or was cut off by the next question. */
function dropTurn(id: number): void {
  turns.value = turns.value.filter((t) => t.id !== id)
}

/**
 * Say one line, showing dots until it can be heard.
 *
 * Everything the avatar says goes through here so the caption and the voice stay
 * together — see `ask` for why that gap matters.
 */
async function say(text: string): Promise<void> {
  const id = addTurn('agent', text, true)
  try {
    await session.value?.speakThen(text, () => settleTurn(id))
  } catch (err) {
    console.warn('[service] say failed', err)
  }
  // Covers the transport that resolves without ever calling back, and any failure above.
  settleTurn(id)
}

/**
 * Where in the menu the customer is: null at the top showing categories, otherwise the
 * category whose questions are listed.
 *
 * Nothing is consumed by being asked. A question stays on the list after it has been
 * answered — someone who half caught a limit or a document name wants to hear it again,
 * and a menu that empties as it is used ends up blank in front of a customer who still
 * has questions.
 */
const openCategory = ref<ServiceCategory | null>(null)

/** The card that came with the current answer, or null when the reply was speech only. */
const card = ref<ServiceQuestion['card']>(null)

/**
 * The search box.
 *
 * Matches across every category rather than the list on screen — someone typing 「挂失」
 * while inside the account category means they want that question, wherever it lives.
 *
 * Matched against the label, the question as asked, and the answer, so a word that only
 * appears in the reply still finds it.
 */
const query = ref('')
const searching = computed(() => query.value.trim().length > 0)

const results = computed<ServiceQuestion[]>(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return []
  return allQuestions(lang.value).filter((q) =>
    `${q.label} ${q.asked} ${q.answer}`.toLowerCase().includes(needle),
  )
})

/** The questions listed right now: search results, or the open category's set. */
const visibleQuestions = computed<ServiceQuestion[]>(() =>
  searching.value ? results.value : (openCategory.value?.questions ?? []),
)

/** Categories show only at the top level — inside one, or while searching, the list is
 *  questions. */
const visibleCategories = computed<ServiceCategory[]>(() =>
  openCategory.value || searching.value ? [] : categories(lang.value),
)

/**
 * Enter, or the search button.
 *
 * The list filters as they type, so submitting has to do more than re-run it: a single
 * match is asked outright, since typing enough to narrow it to one and then having to
 * click it is a step nobody wants. Several matches are left on screen to choose from.
 */
function submitSearch(): void {
  if (results.value.length !== 1) return
  void ask(results.value[0])
}

function clearSearch(): void {
  query.value = ''
}

function openCat(category: ServiceCategory): void {
  openCategory.value = category
  query.value = ''
}

function backToCategories(): void {
  openCategory.value = null
  query.value = ''
}

/** Whether the avatar is currently reading an answer. Only drives the indicator — it does
 *  not lock anything, since a new question is allowed to cut the current one off. */
const speaking = ref(false)

type Phase = 'chatting' | 'live'
const phase = ref<Phase>('chatting')

const session = shallowRef<TutoringSession | null>(null)

// ---------------------------------------------------------------- answering

/**
 * Which answer is currently playing.
 *
 * Every `ask` takes a ticket. When the avatar finishes talking, the handler checks its
 * ticket is still the current one before clearing the speaking flag — otherwise a reply
 * that was interrupted three questions ago wakes up and clears a flag belonging to the
 * answer now playing.
 */
let currentAsk = 0

/**
 * Read one answer out.
 *
 * Interrupting is the point: tapping a second question while the first is still being
 * read cuts it off and starts the new one. Waiting for a paragraph of bank policy to
 * finish before the menu responds makes the whole thing feel stuck, and a customer who
 * has heard the part they needed should be able to move on.
 */
async function ask(question: ServiceQuestion): Promise<void> {
  if (phase.value !== 'chatting') return

  const ticket = ++currentAsk
  speaking.value = true

  // Asking one is the end of that search: leaving the query in place would answer the
  // question and then still show a filtered list rather than where to go next.
  query.value = ''
  addTurn('customer', question.asked)
  // The reply goes up as dots and stays that way until it can actually be heard: the text
  // is ready instantly, the voice is not, and a caption that lands a second or two ahead
  // of the audio reads as the avatar being out of sync with itself.
  const replyId = addTurn('agent', question.answer, true)
  // The card belongs with the spoken answer, so it waits too.
  card.value = null

  try {
    // Stop whatever is playing before starting this one, or the two overlap: `speak`
    // queues rather than replaces.
    await session.value?.interrupt()
    await session.value?.speakThen(question.answer, () => {
      // Interrupted while the audio was on its way — the dots belong to a question the
      // customer has already moved on from, so take them down rather than filling them in.
      if (ticket !== currentAsk) {
        dropTurn(replyId)
        return
      }
      settleTurn(replyId)
      card.value = question.card
      // The card lands under the reply and adds its own height on top of it.
      scrollToEnd()
    })
    if (ticket !== currentAsk) dropTurn(replyId)
  } catch (err) {
    console.warn('[service] speak failed', err)
    if (ticket === currentAsk) settleTurn(replyId)
    else dropTurn(replyId)
  }

  // Only the newest ask owns the flag — an interrupted one must not clear it.
  if (ticket === currentAsk) speaking.value = false
}

// ---------------------------------------------------------------- live Q&A

/**
 * Open the mic and let them ask anything.
 *
 * The menu answers are canned text read verbatim, which is what keeps them accurate and
 * fast. This is the other half: the backend switches to the bank persona, the LLM starts
 * answering what is actually said, and everything in the menu becomes background the
 * avatar can draw on rather than a list to pick from.
 */
async function startLive(): Promise<void> {
  if (phase.value !== 'chatting') return
  phase.value = 'live'

  try {
    // Stop whatever answer is playing first — the transition line should not land on top
    // of a paragraph about transfer limits. Bumping the ticket also stops the interrupted
    // answer settling its own bubble or putting its card up once the mic is already open.
    currentAsk += 1
    speaking.value = false
    await session.value?.interrupt()
    await session.value?.startFreeTalk('banker')
    await session.value?.publishMic()

    await say(t.value.serviceLiveOpen)
  } catch (err) {
    console.warn('[service] live q&a failed', err)
    phase.value = 'chatting'
    await session.value?.unpublishMic().catch(() => {})
  }
}

/** Back to the menu: close the mic and stop the LLM answering. */
async function endLive(): Promise<void> {
  if (phase.value !== 'live') return
  phase.value = 'chatting'
  try {
    await session.value?.unpublishMic()
  } catch (err) {
    console.warn('[service] unpublish failed', err)
  }
}

// ---------------------------------------------------------------- lifecycle

onMounted(async () => {
  session.value = new TutoringSession()
  try {
    if (!stageRef.value) throw new Error('stage not mounted')
    await session.value.start(stageRef.value)
    // The greeting is dropped if it is sent before the agent is up — see rtc-session.ts.
    // A timeout here means the worker never joined, which otherwise surfaces much later
    // and far less clearly as "say failed: Response timeout".
    const agentReady = await session.value.waitForAgent()
    // Only the LiveKit path treats this as fatal: its worker runs on the user's own
    // machine, so a no-show is a local setup problem worth stopping for. The Agora
    // agent runs in the cloud and is deliberately non-fatal — see waitForAgent.
    if (!agentReady && session.value.transportUsed === 'livekit')
      throw new Error(t.value.agentMissing)
    if (!agentReady)
      console.warn('[rtc] agent never joined; the avatar will render but will not speak')
    connected.value = true

    speaking.value = true
    await say(t.value.serviceGreeting)
    speaking.value = false
  } catch (err) {
    overlayError.value = (err as Error).message
    overlayFailed.value = true
  }
})

/** Closing the tab or reloading: an ordinary fetch is cut off, leaving the session up and
 *  billing. */
function stopOnUnload(): void {
  if (session.value) Backend.stopSessionOnUnload(session.value.id)
}

onMounted(() => window.addEventListener('pagehide', stopOnUnload))

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', stopOnUnload)
  // Stops the capture as well as the publish, or the tab keeps its recording indicator lit
  // after the scene is gone.
  void session.value?.unpublishMic()
  void session.value?.stop()
  session.value = null
})
</script>

<template>
  <div class="desk">
    <!-- The hall is the whole window: it is the room the scene happens in, and cutting it
         to the avatar's column would put a seam down the middle of it. -->
    <div class="backdrop" :style="{ backgroundImage: `url(${asset('bank-hall-bg.jpg')})` }" />

    <div v-if="!connected" class="page-overlay">
      <div v-if="!overlayFailed" class="spinner" />
      <span>{{ overlayText }}</span>
    </div>

    <!-- What the SDK is handed, and the only thing held to two thirds of the window: its
         width is what decides how the avatar is framed. Everything else spans the page. -->
    <div ref="stageRef" class="video" />

    <!-- Under the avatar, centred on their column. Talking to them is done to them, not
         to the list of topics, so the control belongs on the avatar rather than at the
         foot of the panel. -->
    <button
      class="live-fab"
      :class="{ on: phase === 'live' }"
      :disabled="!connected"
      @click="phase === 'live' ? endLive() : startLive()"
    >
      <span class="fab-icon">{{ phase === 'live' ? '●' : '🎙' }}</span>
      {{ phase === 'live' ? t.serviceLiveOn : t.serviceLive }}
    </button>

    <!-- Title bar, styled after the one in a banking app's web view. Spans the window
         rather than the avatar's column, or its gradient stops two thirds of the way
         across and leaves a visible edge down the page. -->
    <header class="titlebar">
      <button class="close" :aria-label="t.serviceLeave" @click="emit('exit')">✕</button>
      <div class="title">
        <span class="name">{{ t.serviceTitle }}</span>
        <span class="status"><i class="dot" />{{ t.serviceOnline }}</span>
      </div>
      <LangToggle class="lang" />
    </header>

    <!-- The questions. A column beside the avatar on a desktop, a translucent sheet over
         the bottom of it on a phone. -->
    <section class="panel">
      <div ref="transcriptRef" class="transcript">
        <p v-for="turn in turns" :key="turn.id" class="turn" :class="turn.who">
          <span class="bubble">
            <!-- Waiting on the voice: three dots riding up and down in turn. -->
            <span v-if="turn.pending" class="dots" :aria-label="t.serviceSpeaking">
              <i /><i /><i />
            </span>
            <template v-else>{{ turn.text }}</template>
          </span>
        </p>

        <!-- The card that came with the answer, laid out like the account-type list in a
             real assistant: a heading, the explanation, then the options. -->
        <div v-if="card" class="card">
          <p v-if="card.title" class="card-title">{{ card.title }}</p>
          <p v-for="(line, i) in card.body" :key="i" class="card-body">{{ line }}</p>
          <p v-if="card.action" class="card-action">👉 {{ card.action }}</p>
        </div>
      </div>

      <!-- Chatting: the questions on offer -->
      <div v-if="phase === 'chatting'" class="questions">
        <!-- Searching the whole bank, not the offered set: someone who types a word wants
             that question wherever it sits in the tree. -->
        <div class="search">
          <input
            v-model="query"
            type="search"
            :placeholder="t.serviceSearch"
            :disabled="!connected"
            @keyup.enter="submitSearch"
          />
          <button
            class="search-go"
            :aria-label="t.serviceSearch"
            :disabled="!connected"
            @click="submitSearch"
          >
            🔍
          </button>
        </div>

        <p class="questions-head">
          <!-- Inside a category, the heading doubles as the way back out. -->
          <button v-if="openCategory && !searching" class="crumb" @click="backToCategories">
            ‹ {{ openCategory.label }}
          </button>
          <span v-else>{{ searching ? t.serviceAsk : t.serviceCategories }}</span>
          <button v-if="searching" class="more" @click="clearSearch">✕</button>
          <span v-else-if="speaking" class="speaking">{{ t.serviceSpeaking }}</span>
        </p>

        <p v-if="searching && !results.length" class="search-empty">
          {{ t.serviceSearchEmpty }}
        </p>

        <!-- Top level: the categories. -->
        <button
          v-for="c in visibleCategories"
          :key="c.id"
          class="chip category"
          :disabled="!connected"
          @click="openCat(c)"
        >
          <span class="cat-icon">{{ c.icon }}</span>
          <span class="cat-text">
            <span class="cat-label">{{ c.label }}</span>
            <span class="cat-blurb">{{ c.blurb }}</span>
          </span>
          <span class="chevron">›</span>
        </button>

        <!-- Inside a category, or the search results. Never disabled: tapping one while
             another answer is playing cuts it off and starts this one. -->
        <button
          v-for="q in visibleQuestions"
          :key="q.id"
          class="chip"
          :disabled="!connected"
          @click="ask(q)"
        >
          {{ q.label }}
          <span class="chevron">›</span>
        </button>

      </div>

      <!-- Live Q&A: the mic is open and the LLM is answering. Ending it is done with the
           button under the avatar, which is also what started it. -->
      <div v-else class="live">
        <div class="live-pulse" />
        <p class="live-text">{{ t.serviceLiveListening }}</p>
      </div>
    </section>

    <PerfPanel :session="session" />
  </div>
</template>

<style scoped>
/* On a desktop the window is landscape, and a panel laid across the foot of it stretches
   each question into a long thin bar. So the two are put side by side instead: the avatar
   holds the left two thirds, the questions take the right third as a column. Below the
   breakpoint it goes back to the stacked phone layout, where the panel floats over the
   bottom of a full-bleed avatar. */
.desk {
  /* Lives here rather than on the panel so the button, which is a sibling, can read it. */
  --panel-height: 58%;
  position: relative;
  height: 100vh;
  overflow: hidden;
  background: #0d1b2e;
}

@media (min-width: 900px) {
  .desk {
    display: grid;
    grid-template-columns: 2fr 1fr;
  }
}

/* The element handed to the SDK. Full-bleed by default; two thirds of the window once
   the questions move alongside. Its size is what the avatar is framed to, so it is a real
   box in the layout rather than an overlay. */
.video {
  position: relative;
  z-index: 1;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

/* On a phone it is the whole screen, with the questions floating over the bottom of it. */
@media (max-width: 899px) {
  .video {
    position: absolute;
    inset: 0;
  }
}

/* The hall photograph, dimmed and cooled so the avatar in front of it keeps contrast —
   at full brightness the lit counter competes with the face. */
/* Absolute, so it covers the whole window rather than taking a column of the grid. */
.backdrop {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.backdrop::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgb(10 26 48 / 30%) 0%, rgb(10 26 48 / 55%) 100%);
}


/* Title bar ------------------------------------------------------------- */

.titlebar {
  position: absolute;
  inset: 0 0 auto;
  z-index: 2;
  display: grid;
  grid-template-columns: 40px 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  /* Fades out rather than ending on a line: a hard edge across the hall photograph reads
     as a seam. */
  background: linear-gradient(180deg, rgb(8 20 38 / 72%) 0%, rgb(8 20 38 / 0%) 100%);
  color: #fff;
}


.close {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: rgb(255 255 255 / 14%);
  color: #fff;
  font-size: 15px;
  cursor: pointer;
}
.close:hover {
  background: rgb(255 255 255 / 24%);
}

.title {
  display: flex;
  flex-direction: column;
  align-items: center;
  line-height: 1.35;
}
.title .name {
  font-size: 16px;
  font-weight: 600;
}
.title .status {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  opacity: 0.75;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #3ddc84;
}

.lang {
  justify-self: end;
}

/* Panel ----------------------------------------------------------------- */

/* Anchored to the bottom and capped at half the height: the avatar's face has to stay
   clear above it, and a panel that grows with its content eventually covers it. */
.panel {
  /* One number for the panel's height, so the button that positions against it cannot
     drift out of step with it — which is exactly what happened when the phone breakpoint
     changed one and not the other. */
  position: absolute;
  /* Held to a readable column and centred rather than run edge to edge: on a wide window
     a full-width panel stretches each question into a single long bar with the label at
     one end and the chevron at the other, and leaves the row half empty. */
  inset: auto 0 0;
  width: min(100%, 640px);
  margin: 0 auto;
  max-height: var(--panel-height);
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  box-sizing: border-box;
  border-radius: 18px 18px 0 0;
  background: rgb(255 255 255 / 82%);
  backdrop-filter: blur(18px);
  box-shadow: 0 -8px 32px rgb(8 20 38 / 22%);
  /* Translucent over a photograph needs its own stacking context, or the blur samples
     the avatar canvas rather than the backdrop. */
  isolation: isolate;
}

/* The questions become the right column: a full-height panel rather than an overlay, so
   it is opaque and square-edged — there is nothing behind it to show through. */
@media (min-width: 900px) {
  /* A card floating on the hall rather than a column filling the side: inset from the
     window edges, translucent enough to read the room through, and outlined so it reads
     as a distinct surface over the photograph. */
  .panel {
    position: relative;
    z-index: 1;
    inset: auto;
    width: auto;
    /* A fixed 80% of the window rather than sized to its content: the card is a fixture of
       the layout, and one that grows and shrinks as questions come and go makes the whole
       right side jump about. Short content simply leaves the lower part empty. */
    height: 80vh;
    /* The phone layout caps the panel at 58% of the screen. Left in place that cap wins
       over the height above, and the card comes out a little over half the window. */
    max-height: none;
    align-self: center;
    margin: 0 24px 0 0;
    border: 1px solid rgb(255 255 255 / 55%);
    border-radius: 16px;
    background: rgb(255 255 255 / 14%);
    backdrop-filter: blur(14px);
    box-shadow: 0 10px 40px rgb(8 20 38 / 26%);
    padding: 18px;
  }

  /* Takes the slack in the fixed-height card, so a short exchange leaves the space empty
     above the questions rather than floating them in the middle. */
  .transcript {
    flex: 1;
    max-height: none;
    /* Lighter than on a phone: the panel behind it is already translucent white, and a
       second wash of the same on top of it turns the outline to mush. */
    background: rgb(255 255 255 / 20%);
    border-color: rgb(255 255 255 / 45%);
  }

  /* A transparent card sits on the hall photograph, so the text on it has to carry its
     own contrast. Dark type with no shadow: white with a blur under it turns to mush
     against the bright end of the hall, and the hall is mostly light. */
  .questions-head,
  .search-empty,
  .speaking {
    color: #33415c;
  }

  .more,
  .crumb {
    color: #1e63d0;
  }

  /* Opaque, like the reply bubbles: a translucent block on a translucent card doubles the
     background through it and the text loses its footing. */
  .card {
    background: rgb(255 255 255 / 88%);
    border-color: rgb(30 99 208 / 20%);
  }

  .live-text {
    color: #1a2740;
  }
}

/* The conversation is its own surface inside the panel, outlined and inset, so it reads
   as a transcript rather than as bubbles floating loose above the questions. */
.transcript {
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid rgb(26 39 64 / 12%);
  border-radius: 12px;
  background: rgb(255 255 255 / 34%);
  /* Bounded so a long exchange scrolls inside the panel instead of pushing the questions
     off the bottom of it. */
  max-height: 40vh;
}

.turn {
  display: flex;
  margin: 0;
}
.turn.customer {
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  padding: 9px 13px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
}
.turn.agent .bubble {
  background: rgb(255 255 255 / 92%);
  color: #1a2740;
  border: 1px solid rgb(26 39 64 / 8%);
}
.turn.customer .bubble {
  background: #1e63d0;
  color: #fff;
}

/* Waiting for the voice. Three dots lifting one after another — a bubble that just sits
   there empty reads as something having gone wrong. */
.dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  /* Matches a line of text, so the bubble does not change height when the words land. */
  height: 21px;
}
.dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.45;
  animation: dot 1.1s ease-in-out infinite;
}
.dots i:nth-child(2) {
  animation-delay: 0.16s;
}
.dots i:nth-child(3) {
  animation-delay: 0.32s;
}

@keyframes dot {
  0%,
  70%,
  100% {
    transform: translateY(0);
    opacity: 0.35;
  }
  35% {
    transform: translateY(-4px);
    opacity: 0.85;
  }
}

/* The card that comes with an answer. Blue like the one in a banking app, and clearly a
   block rather than another bubble. */
.card {
  padding: 12px 14px;
  border-radius: 12px;
  background: rgb(30 99 208 / 8%);
  border: 1px solid rgb(30 99 208 / 18%);
}
.card-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: #1a2740;
}
.card-body {
  margin: 0 0 4px;
  font-size: 13px;
  line-height: 1.55;
  color: #45536e;
}
.card-action {
  margin: 8px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: #1e63d0;
}

/* Questions ------------------------------------------------------------- */

.questions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* Search ---------------------------------------------------------------- */

.search {
  flex: 1 0 100%;
  display: flex;
  gap: 6px;
}

.search input {
  flex: 1;
  min-width: 0;
  padding: 9px 12px;
  border: 1px solid rgb(26 39 64 / 14%);
  border-radius: 9px;
  background: rgb(255 255 255 / 82%);
  font-size: 13.5px;
  color: #1a2740;
}
.search input:focus {
  outline: none;
  border-color: #1e63d0;
}

.search-go {
  flex: 0 0 auto;
  width: 38px;
  border: 1px solid rgb(26 39 64 / 14%);
  border-radius: 9px;
  background: rgb(255 255 255 / 82%);
  font-size: 14px;
  cursor: pointer;
}
.search-go:hover:not(:disabled) {
  background: #fff;
}

.search-empty {
  flex: 1 0 100%;
  margin: 2px 0;
  font-size: 12.5px;
  color: #6b7893;
}

.questions-head {
  flex: 1 0 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 0;
  font-size: 12px;
  color: #6b7893;
}

.more {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  color: #1e63d0;
  cursor: pointer;
}
.more:disabled {
  opacity: 0.4;
  cursor: default;
}

/* One per row inside the portrait frame. Two columns leaves each label alone at one end
   of a wide button with the chevron at the other, which reads as a stretched row rather
   than a list you tap down. */
.chip {
  flex: 1 0 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  padding: 12px 14px;
  border: none;
  border-radius: 10px;
  background: #1e63d0;
  color: #fff;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}
.chip:hover:not(:disabled) {
  background: #1a58ba;
}
.chip:disabled {
  opacity: 0.55;
  cursor: default;
}
.chevron {
  opacity: 0.8;
  font-size: 16px;
}

/* A category row carries a name and what is under it, so it is taller than a question
   row and reads as a heading you open rather than a question you ask. */
.chip.category {
  gap: 10px;
  padding: 11px 14px;
  background: rgb(255 255 255 / 88%);
  color: #1a2740;
  border: 1px solid rgb(30 99 208 / 16%);
}
.chip.category:hover:not(:disabled) {
  background: #fff;
  border-color: rgb(30 99 208 / 34%);
}
.cat-icon {
  font-size: 19px;
}
.cat-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  text-align: left;
}
.cat-label {
  font-size: 14px;
  font-weight: 600;
}
.cat-blurb {
  font-size: 11.5px;
  color: #6b7893;
}
.chip.category .chevron {
  color: #6b7893;
}

/* The way back out of a category, sitting where the heading was. */
.crumb {
  border: none;
  background: none;
  padding: 0;
  font-size: 13px;
  font-weight: 600;
  color: #1e63d0;
  cursor: pointer;
}

.speaking {
  font-size: 11.5px;
  color: #6b7893;
}

/* Floating under the avatar. Absolute against the page so it centres on the avatar's own
   column rather than on the window, which the panel takes a third of. */
.live-fab {
  position: absolute;
  z-index: 2;
  bottom: 40px;
  left: 0;
  /* Centred within the avatar's two thirds; the panel owns the rest. */
  right: 33.33%;
  width: fit-content;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 24px;
  border: none;
  border-radius: 999px;
  background: #1e63d0;
  color: #fff;
  font-size: 14.5px;
  font-weight: 600;
  box-shadow: 0 8px 26px rgb(8 20 38 / 34%);
  cursor: pointer;
  transition: background-color 0.15s, transform 0.15s;
}
.live-fab:hover:not(:disabled) {
  background: #1a58ba;
  transform: translateY(-1px);
}
.live-fab:disabled {
  opacity: 0.5;
  cursor: default;
}
/* Listening: red, and pulsing, so it is obvious the mic is live. */
.live-fab.on {
  background: #d64545;
  animation: fab-pulse 1.6s ease-out infinite;
}
.fab-icon {
  font-size: 15px;
}
@keyframes fab-pulse {
  0% {
    box-shadow: 0 8px 26px rgb(8 20 38 / 34%), 0 0 0 0 rgb(214 69 69 / 45%);
  }
  100% {
    box-shadow: 0 8px 26px rgb(8 20 38 / 34%), 0 0 0 18px rgb(214 69 69 / 0%);
  }
}

/* On a phone the panel is at the foot of the screen, so the button sits above it and
   centres on the whole width. */
@media (max-width: 899px) {
  .live-fab {
    right: 0;
    bottom: calc(var(--panel-height) + 16px);
  }
}

/* Live Q&A ------------------------------------------------------------- */

.live {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 10px 0 4px;
}
.live-text {
  margin: 0;
  font-size: 14px;
  color: #1a2740;
}
/* A pulse rather than a spinner: nothing is loading, something is listening. */
.live-pulse {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #1e63d0;
  animation: pulse 1.4s ease-out infinite;
}
@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgb(30 99 208 / 45%);
  }
  100% {
    box-shadow: 0 0 0 16px rgb(30 99 208 / 0%);
  }
}

/* Overlay --------------------------------------------------------------- */

.page-overlay {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  background: rgb(8 20 38 / 76%);
  color: #fff;
  font-size: 14px;
  /* The agent-missing message is several lines of troubleshooting, not one sentence. */
  white-space: pre-line;
  text-align: center;
  padding: 0 24px;
  line-height: 1.7;
}

.spinner {
  width: 30px;
  height: 30px;
  border: 3px solid rgb(255 255 255 / 25%);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* On a phone the panel gets more of the screen: the questions are the whole interaction,
   and two rows of chips plus a transcript does not fit in the desktop proportion. */
@media (max-width: 560px) {
  .panel {
    max-height: var(--panel-height);
    padding: 14px 12px;
  }
  .chip {
    flex-basis: 100%;
  }
}
</style>
