<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { SAMPLE_QUESTIONS, optionLabel, readAloudText } from './question'
import * as Backend from './agent-client'
import { TutoringSession } from './rtc-session'
import { asset } from './asset'
import { t } from './i18n'
import Icon from './Icon.vue'
import LangToggle from './LangToggle.vue'
import PerfPanel from './PerfPanel.vue'

const emit = defineEmits<{ exit: [] }>()

const questions = SAMPLE_QUESTIONS
const questionIndex = ref(0)
const question = computed(() => questions.value[questionIndex.value])

/**
 * One answer recorded per question, so paging back and forth keeps them and the chosen
 * option stays highlighted. The two question banks have the same count and answer
 * indices, so switching languages does not disturb answers already given.
 */
const selections = ref<(number | null)[]>(questions.value.map(() => null))
const selected = computed(() => selections.value[questionIndex.value])
const allCorrect = computed(() =>
  questions.value.every((q, i) => selections.value[i] === q.answerIndex),
)

const stageRef = ref<HTMLElement | null>(null)
const avatarReady = ref(false)
/**
 * Stores only the failure reason and assembles the copy in a computed: switching
 * languages has to change both "connecting" and any error already on screen, and a
 * stored string would be stuck on the pre-switch version.
 */
const overlayError = ref('')
const overlayFailed = ref(false)
const overlayText = computed(() =>
  overlayError.value ? t.value.connectFailed(overlayError.value) : t.value.teacherComing,
)

const connected = ref(false)
const freeTalk = ref(false)
const micGranted = ref(false)
/** Once denied, no further automatic prompts — the greyed-out button is the hint to
 *  enable it in browser settings. */
const micPrompted = ref(false)
const cameraOn = ref(true)
const cameraGranted = ref(false)
const cameraStream = ref<MediaStream | null>(null)
const cameraRef = ref<HTMLVideoElement | null>(null)

const micDenied = computed(() => freeTalk.value && micPrompted.value && !micGranted.value)
const micEnabled = computed(() => freeTalk.value && micGranted.value)

const session = shallowRef<TutoringSession | null>(null)

// ---------------------------------------------------------------- Speech throttling

/**
 * The pending line to speak, **keeping only the most recent one**.
 *
 * Rapid clicking does not queue: later text overwrites the previous line and everything
 * in between is dropped — a queue would have the avatar work through stale feedback one
 * line at a time, when the student only cares about their last answer.
 */
const pendingSpeech = ref<string | null>(null)
const SPEAK_INTERVAL_MS = 2000
let lastSentAt = 0
let speechTimer: number | null = null

function speak(text: string): void {
  pendingSpeech.value = text
  scheduleSpeech()
}

/**
 * A single send loop: the first line after an idle period goes out immediately,
 * otherwise it waits out the remainder of the interval since the last send. It may be
 * overwritten again while waiting, so it reads the latest value at the moment it fires.
 */
function scheduleSpeech(): void {
  if (speechTimer !== null) return
  const elapsed = Date.now() - lastSentAt
  const wait = Math.max(0, SPEAK_INTERVAL_MS - elapsed)
  speechTimer = window.setTimeout(() => {
    speechTimer = null
    const text = pendingSpeech.value
    if (text === null) return
    pendingSpeech.value = null
    lastSentAt = Date.now()
    void session.value?.speak(text)
  }, wait)
}

// ---------------------------------------------------------------- Lifecycle

onMounted(async () => {
  session.value = new TutoringSession({
    onRendered: () => {
      avatarReady.value = true
    },
  })
  void requestCamera()
  try {
    if (!stageRef.value) throw new Error('stage not mounted')
    await session.value.start(stageRef.value)
    // Set connected only once the agent is ready: reading the question is triggered by
    // connected, and sending it early gets it dropped.
    await session.value.waitForAgent()
    connected.value = true
  } catch (err) {
    overlayError.value = (err as Error).message
    overlayFailed.value = true
  }
})

/**
 * The fallback for closing the tab, reloading, or being reclaimed after backgrounding.
 *
 * The back button alone is not enough: when the user simply closes the page, the fetch in
 * the component's unmount hook is cut off by the browser and the room is left up and
 * billing. pagehide covers close and reload, and fires on mobile when the page is
 * reclaimed after switching away (unload is unreliable on iOS Safari, so it is not used).
 */
function stopOnUnload(): void {
  if (session.value) Backend.stopSessionOnUnload(session.value.id)
}

onMounted(() => {
  window.addEventListener('pagehide', stopOnUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', stopOnUnload)
  stopCamera()
  // This cannot wait until the component has finished unmounting: the stop request has
  // to go out, or billing continues.
  void session.value?.stop()
  session.value = null
})

/**
 * Reads the current question aloud once the avatar is connected, and again when the
 * question changes.
 *
 * selections is deliberately not a dependency: the moment an answer is correct, the click
 * path speaks the feedback, and this should not fire a second time. Questions already
 * answered correctly are not re-read — paging back to them is just review.
 */
watch([connected, questionIndex], () => {
  if (!connected.value) return
  if (selections.value[questionIndex.value] !== question.value.answerIndex) {
    speak(readAloudText(question.value, questionIndex.value + 1))
  } else {
    // An explicit interrupt is required: otherwise the previous question is read to the
    // end while the UI has already moved on. Clear the pending line first, or the
    // throttle loop sends the just-interrupted line again.
    pendingSpeech.value = null
    if (speechTimer !== null) {
      clearTimeout(speechTimer)
      speechTimer = null
    }
    void session.value?.interrupt()
  }
})

watch([micEnabled, connected], () => {
  if (!connected.value) return
  void (micEnabled.value ? session.value?.publishMic() : session.value?.unpublishMic())?.catch(
    (e: unknown) => console.warn(t.value.micFailed, e),
  )
})

// ---------------------------------------------------------------- Interaction

function onSelect(index: number): void {
  // A correct answer freezes the question; a wrong one can be changed and retried
  // indefinitely.
  if (selected.value === question.value.answerIndex) return

  selections.value[questionIndex.value] = index

  // Recompute here: the answer just written has not propagated to the computed yet.
  const finished = questions.value.every((q, i) => selections.value[i] === q.answerIndex)
  const correct = index === question.value.answerIndex

  if (finished && !freeTalk.value) {
    // Feedback and the opening line go out as one message, so two arriving back to back
    // do not interrupt each other at the agent.
    speak(t.value.sayAllCorrect)
    void enterFreeTalk()
  } else if (finished) {
    speak(t.value.sayCorrectThenChat)
  } else if (correct) {
    speak(t.value.sayCorrect)
  } else {
    const replies = t.value.wrongReplies
    speak(replies[Math.floor(Math.random() * replies.length)])
  }
}

async function enterFreeTalk(): Promise<void> {
  freeTalk.value = true
  if (!micGranted.value && !micPrompted.value) await requestMic()
  await session.value?.startFreeTalk()
}

async function onToggleFreeTalk(): Promise<void> {
  if (freeTalk.value) {
    // Hanging up only mutes the microphone: the server prompt is already the free-talk
    // one, so re-entering does not need to switch it again.
    freeTalk.value = false
    return
  }
  if (!allCorrect.value) {
    // Free talk is the reward for finishing the questions.
    speak(t.value.sayFinishFirst)
    return
  }
  speak(t.value.sayFreeTalkOpen)
  await enterFreeTalk()
}

async function requestMic(): Promise<void> {
  micPrompted.value = true
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    // This is only to obtain permission — the SDK does its own capture.
    stream.getTracks().forEach((t) => t.stop())
    micGranted.value = true
  } catch {
    micGranted.value = false
  }
}

async function requestCamera(): Promise<void> {
  try {
    cameraStream.value = await navigator.mediaDevices.getUserMedia({ video: true })
    cameraGranted.value = true
    if (cameraRef.value) cameraRef.value.srcObject = cameraStream.value
  } catch {
    cameraGranted.value = false
  }
}

function stopCamera(): void {
  cameraStream.value?.getTracks().forEach((t) => t.stop())
  cameraStream.value = null
}

watch([cameraRef, cameraStream], () => {
  if (cameraRef.value && cameraStream.value) cameraRef.value.srcObject = cameraStream.value
})

function optionState(index: number): 'idle' | 'correct' | 'wrong' {
  // Only the selected option is coloured: a wrong answer does not reveal the correct
  // one, or retrying would be pointless.
  if (selected.value === null || index !== selected.value) return 'idle'
  return index === question.value.answerIndex ? 'correct' : 'wrong'
}
</script>

<template>
  <div class="classroom">
    <!--
      A full-page overlay, held until the agent is ready.
      Covering just the video panel is not enough: until then, clicking the question or
      the options does nothing visible — reading the question and speaking feedback both
      need the agent online — which reads as an unresponsive page. Better to block the
      whole thing.
    -->
    <div v-if="!connected" class="page-overlay">
      <div v-if="!overlayFailed" class="spinner" />
      <span>{{ overlayText }}</span>
    </div>
    <header class="topbar">
      <div class="topbar-left">
        <button class="back" :aria-label="t.back" @click="emit('exit')">←</button>
        <LangToggle />
      </div>
      <div class="title">{{ question.topic }} · {{ t.section(questionIndex + 1) }}</div>
      <div class="topbar-spacer" />
    </header>

    <main class="body">
      <section class="content-card">
        <div class="panel-header">
          <span class="topic">{{ question.topic }}</span>
          <span class="spacer" />
          <button class="pager" :disabled="questionIndex === 0" @click="questionIndex--">
            {{ t.prevQuestion }}
          </button>
          <span class="progress">{{ questionIndex + 1 }}/{{ questions.length }}</span>
          <button
            class="pager"
            :disabled="questionIndex === questions.length - 1"
            @click="questionIndex++"
          >
            {{ t.nextQuestion }}
          </button>
        </div>

        <p class="stem">{{ question.stem }}</p>

        <ul class="options">
          <li
            v-for="(option, index) in question.options"
            :key="index"
            :class="['option', optionState(index)]"
            @click="onSelect(index)"
          >
            <span class="badge">{{ optionLabel(index) }}</span>
            <span class="text">{{ option }}</span>
          </li>
        </ul>

        <div class="mic-row">
          <button
            :class="['mic-fab', { active: micEnabled, denied: micDenied }]"
            :aria-label="freeTalk ? t.endFreeTalk : t.startFreeTalk"
            @click="onToggleFreeTalk"
          >
            <Icon :name="micDenied ? 'mic-off' : 'mic'" />
          </button>
          <p class="mic-hint">
            {{ freeTalk ? t.freeTalkListening : t.freeTalkLocked }}
          </p>
        </div>
      </section>

      <aside class="video-column">
        <div class="tile">
          <img class="tile-bg" :src="asset('classroom-background.jpg')" alt="" />
          <div ref="stageRef" class="stage" />
          <div v-if="!avatarReady" class="overlay">
            <div v-if="!overlayFailed" class="spinner" />
            <span>{{ overlayText }}</span>
          </div>
          <div class="name-bar">
            <span>{{ t.teacher }}</span>
            <span :class="['mic-dot', { on: micEnabled }]">
              <Icon :name="micEnabled ? 'mic' : 'mic-off'" />
            </span>
          </div>
        </div>

        <div class="tile student">
          <div class="tile-bg student-bg" />
          <video
            v-if="cameraGranted && cameraOn"
            ref="cameraRef"
            class="stage"
            autoplay
            playsinline
            muted
          />
          <div v-else class="avatar-placeholder">{{ t.me }}</div>
          <div class="name-bar">
            <span>{{ t.me }}</span>
            <button
              v-if="cameraGranted"
              :class="['mic-dot', { on: cameraOn }]"
              :aria-label="cameraOn ? t.cameraOff : t.cameraOn"
              @click="cameraOn = !cameraOn"
            >
              <Icon :name="cameraOn ? 'camera' : 'camera-off'" />
            </button>
          </div>
        </div>
      </aside>
    </main>

    <PerfPanel :session="session" />
  </div>
</template>

<style scoped>
.classroom {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px 28px 22px;
  box-sizing: border-box;
}

/* Top bar -------------------------------------------------------------- */
.topbar {
  /* The left and right columns split the remaining space evenly and the title takes only
     its own width, so the title stays dead centre no matter how much is on the left,
     without having to guess control widths. */
  display: flex;
  align-items: center;
  gap: 12px;
}

.topbar-left,
.topbar-spacer {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
}

.back {
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.75);
  color: var(--primary);
  font-size: 22px;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(47, 107, 255, 0.14);
}

.title {
  padding: 12px 48px;
  white-space: nowrap;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.62);
  color: #1f3a6e;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

/* Body ----------------------------------------------------------------- */
/*
 * Both columns fill the body height without either measuring the other: the content card
 * stretches, and the video column splits evenly into two.
 * An earlier version computed the card height with calc, which fought the card's internal
 * flex and pushed the microphone out of the card.
 *
 * A video panel's width comes from the column width and its height from "half minus half
 * a gap", with aspect-ratio capping it — so it is not stretched into a tall strip when
 * there is height to spare.
 */
.body {
  --gap: 14px;

  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(300px, 28%, 400px);
  gap: 18px;
  /* Equal-height columns, vertically centred: filling the height stretches the video
     panels into strips and leaves the content card looking empty. */
  grid-template-rows: 85%;
  align-content: center;
}

.content-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
  padding: 26px 30px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 10px 40px rgba(31, 58, 110, 0.08);
}

.panel-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.topic {
  padding: 5px 10px;
  border-radius: 6px;
  background: rgba(47, 107, 255, 0.1);
  color: var(--primary);
  font-size: 13px;
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.pager {
  border: none;
  background: none;
  color: var(--primary);
  font-size: 14px;
  cursor: pointer;
  padding: 4px 12px;
}

.pager:disabled {
  color: var(--on-surface-variant);
  cursor: default;
}

.progress {
  font-size: 13px;
  color: var(--on-surface-variant);
  font-variant-numeric: tabular-nums;
}

.stem {
  /* A real pause between the stem and the options. This card is several hundred pixels
     tall, and a gap of a dozen pixels amounts to nothing at that scale. */
  margin: 10px 0 40px;
  font-size: clamp(18px, 1.6vw, 26px);
  font-weight: 700;
  line-height: 1.45;
  color: #17356b;
  text-wrap: pretty;
}

/* Options -------------------------------------------------------------- */
.options {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.option {
  /* Split the remaining height evenly, but with a cap: an even split turns each row into
     a large empty slab when the stem is short. */
  flex: 1;
  min-height: 56px;
  max-height: 92px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 14px;
  border: 1px solid var(--outline-variant);
  border-radius: 12px;
  background: rgba(232, 236, 244, 0.5);
  cursor: pointer;
  transition: background-color 0.18s, border-color 0.18s;
}

.option .badge {
  flex: none;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--surface);
  color: var(--on-surface-variant);
  font-size: 14px;
  font-weight: 700;
}

.option .text {
  flex: 1;
  font-size: clamp(14px, 1.1vw, 18px);
}

.option.correct {
  border-color: var(--correct);
  background: rgba(46, 158, 91, 0.12);
}

.option.correct .badge {
  background: var(--correct);
  color: #fff;
}

.option.wrong {
  border-color: var(--wrong);
  background: rgba(214, 69, 69, 0.12);
}

.option.wrong .badge {
  background: var(--wrong);
  color: #fff;
}

/* Microphone ----------------------------------------------------------- */
.mic-row {
  /* Pinned to the bottom inside the card, not floating outside it. */
  padding-top: 4px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.mic-fab {
  width: 52px;
  height: 52px;
  border: none;
  border-radius: 50%;
  background: #4aa8ff;
  color: #fff;
  font-size: 24px;
  display: grid;
  place-items: center;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(74, 168, 255, 0.4);
}

.mic-fab.active {
  background: var(--correct);
  box-shadow: 0 6px 18px rgba(46, 158, 91, 0.4);
}

.mic-fab.denied {
  background: #9e9e9e;
  box-shadow: none;
}

.mic-hint {
  margin: 0;
  font-size: 14px;
  color: var(--primary);
}

/* Video column --------------------------------------------------------- */
.video-column {
  display: flex;
  flex-direction: column;
  gap: var(--gap);
  min-height: 0;
}

.tile {
  position: relative;
  /* Splits the video column's height evenly; the width comes from the column. */
  flex: 1;
  min-height: 0;
  border-radius: 20px;
  overflow: hidden;
  background: #dbe6f7;
}

.tile-bg,
.stage {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.student-bg {
  background: linear-gradient(160deg, #00897b, #26a69a);
}

.avatar-placeholder {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 40px;
  font-weight: 700;
}

.page-overlay {
  position: fixed;
  inset: 0;
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  background: rgba(247, 245, 241, 0.86);
  backdrop-filter: blur(3px);
  color: var(--on-surface-variant);
  font-size: 15px;
  font-weight: 600;
  text-align: center;
}

/* The full-page overlay has a light background, where a white spinner is invisible, so
   it gets a dark set of its own. */
.page-overlay .spinner {
  width: 30px;
  height: 30px;
  border-color: var(--outline-variant);
  border-top-color: var(--primary);
}

.overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: rgba(0, 0, 0, 0.4);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  text-align: center;
  padding: 0 16px;
}

.spinner {
  width: 26px;
  height: 26px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation-duration: 3s;
  }
}

.name-bar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.5);
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.28));
}

.mic-dot {
  /* A dark round backing: the icon sits over the video, and plain white lines vanish
     against a light background. */
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.42);
  color: rgba(255, 255, 255, 0.75);
  font-size: 18px;
  cursor: pointer;
  backdrop-filter: blur(2px);
}

.mic-dot.on {
  /* Solid green when on, so it is distinguishable from off at a glance. */
  background: var(--correct);
  color: #fff;
}

/* Narrow screens: video moves to a row on top, matching mobile portrait. */
/* Narrow screens: video moves to a row on top, matching mobile portrait; the side length
   is then determined by the width. */
@media (max-width: 900px) {
  .body {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }

  .video-column {
    flex-direction: row;
    order: -1;
  }

  .video-column {
    height: 34vh;
  }
}
</style>
