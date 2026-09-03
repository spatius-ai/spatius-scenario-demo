<script setup lang="ts">
/**
 * The companion: a full-screen avatar and nothing else to do but talk.
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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as Backend from './agent-client'
import { TutoringSession } from './rtc-session'
import { asset } from './asset'
import { lang, t } from './i18n'
import LangToggle from './LangToggle.vue'
import PerfPanel from './PerfPanel.vue'
import { personas, type Persona } from './companion-personas'

const emit = defineEmits<{ exit: [] }>()

/**
 * Who is in the room, picked before it opens.
 *
 * The scene has no task, so the character is the whole of it — asking first, rather than
 * dropping the user into a default, is what makes the choice feel like part of the scene
 * instead of a setting.
 */
const chosen = ref<Persona | null>(null)
/** A character the user wrote, used when they pick the custom entry. */
const customPrompt = ref('')
const writingCustom = ref(false)

/**
 * Which memory the chosen character reads and writes.
 *
 * One per character and language. Per character so that what you told the flatmate does
 * not come back out of the mentor; per language because a conversation held in English
 * goes into the persona verbatim, and asking a model answering in Chinese to pull a
 * detail out of English transcript adds a translation step that loses things. Anything
 * written by hand shares a file per language, since a custom character has no stable name
 * to file it under.
 */
const memoryKey = computed(() => `${chosen.value?.id ?? 'friend'}-${lang.value}`)

/**
 * The key this session actually belongs to, fixed when the room opens.
 *
 * The language toggle stays available inside the room, and following it live would file
 * the second half of a conversation under a memory the first half is not in — and file it
 * against a persona the backend was never switched to. What was said in this session
 * belongs where it started.
 */
const sessionMemoryKey = ref('')

function choose(persona: Persona): void {
  customPrompt.value = ''
  chosen.value = persona
}

function startCustom(): void {
  if (!customPrompt.value.trim()) return
  writingCustom.value = false
  chosen.value = {
    id: 'custom',
    name: customPrompt.value.trim().slice(0, 12),
    blurb: '',
    icon: '✎',
    prompt: customPrompt.value.trim(),
  }
}

const stageRef = ref<HTMLElement | null>(null)

const connected = ref(false)
const overlayError = ref('')
const overlayFailed = ref(false)
const overlayText = computed(() =>
  overlayError.value ? t.value.connectFailed(overlayError.value) : t.value.enteringCompanion,
)

/** Whether the mic is open. It opens on entry — this scene is nothing but the
 *  conversation — and the control is there to close it, not to start it. */
const micOpen = ref(false)

/** What the companion already remembers, read once on entry. Shown so the memory is
 *  visible rather than an invisible claim: without it, a returning visitor has no way to
 *  tell whether anything was kept. */
const remembered = ref<Backend.MemorySummary | null>(null)
const showMemory = ref(false)

const session = shallowRef<TutoringSession | null>(null)

async function toggleMic(): Promise<void> {
  if (!session.value) return
  try {
    if (micOpen.value) {
      await session.value.unpublishMic()
      micOpen.value = false
    } else {
      await session.value.publishMic()
      micOpen.value = true
    }
  } catch (err) {
    console.warn('[companion] mic toggle failed', err)
  }
}

/** Forget everything and start over, so the scene can be shown from a blank slate. */
async function forget(): Promise<void> {
  const key = sessionMemoryKey.value || memoryKey.value
  await Backend.clearMemory(key)
  remembered.value = await Backend.fetchMemory(key)
}

/** Opens the room once a character has been chosen. Nothing connects before that — a
 *  session bills from the moment it is created. */
async function enter(): Promise<void> {
  session.value = new TutoringSession()
  try {
    await nextTick()
    if (!stageRef.value) throw new Error('stage not mounted')

    // Fixed for the rest of the session — see `sessionMemoryKey`.
    sessionMemoryKey.value = memoryKey.value

    // Read the memory before connecting: it is what the persona is built from, and the
    // backend assembles that at session creation.
    remembered.value = await Backend.fetchMemory(sessionMemoryKey.value)

    await session.value.start(stageRef.value)
    const agentReady = await session.value.waitForAgent()
    // Only the LiveKit path treats this as fatal: its worker runs on the user's own
    // machine, so a no-show is a local setup problem worth stopping for. The Agora
    // agent runs in the cloud and is deliberately non-fatal — see waitForAgent.
    if (!agentReady && session.value.transportUsed === 'livekit')
      throw new Error(t.value.agentMissing)
    if (!agentReady)
      console.warn('[rtc] agent never joined; the avatar will render but will not speak')
    connected.value = true

    await session.value.startFreeTalk('companion', chosen.value?.prompt ?? '', sessionMemoryKey.value)

    // A fixed greeting, in one of two versions depending on whether there is anything to
    // remember. It is read verbatim rather than generated: ConvoAI's only way of making
    // the avatar speak is `speak`, which takes the text as given — there is no call that
    // has the agent produce a line of its own (checked against a live agent). What the
    // memory does affect is everything after this: it is in the persona, so the first real
    // reply already draws on it.
    await session.value.speak(
      remembered.value?.size ? t.value.companionHelloAgain : t.value.companionHello,
    )
    await session.value.waitUntilSilent()

    // Opened after the greeting rather than before it: open first and the companion hears
    // its own line through the room and answers itself.
    await session.value.publishMic()
    micOpen.value = true
  } catch (err) {
    overlayError.value = (err as Error).message
    overlayFailed.value = true
  }
}

/**
 * Leaving has to tell the backend to keep the conversation — on the Agora path the
 * transcript lives with the agent and goes away when it stops, so it is collected in the
 * same call that ends the session.
 */
function stopOnUnload(): void {
  if (session.value) Backend.stopSessionOnUnload(session.value.id, sessionMemoryKey.value)
}

// Connect only once a character is chosen.
watch(chosen, (persona) => {
  if (persona) void enter()
})

onMounted(() => window.addEventListener('pagehide', stopOnUnload))

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', stopOnUnload)
  void session.value?.unpublishMic()
  void session.value?.stop(sessionMemoryKey.value)
  session.value = null
})
</script>

<template>
  <div class="room">
    <!-- The room is the whole window, and the avatar sits in it. Nothing else competes
         for space: the scene is the conversation. -->
    <div class="backdrop" :style="{ backgroundImage: `url(${asset('companion-room-bg.jpg')})` }" />

    <!-- Who is in the room. Nothing connects until this is answered — the scene is the
         character, and a session bills from the moment it starts. -->
    <section v-if="!chosen" class="picker">
      <h1>{{ t.companionPick }}</h1>
      <p class="picker-hint">{{ t.companionPickHint }}</p>

      <div class="cards">
        <button v-for="p in personas(lang)" :key="p.id" class="card" @click="choose(p)">
          <span class="card-icon">{{ p.icon }}</span>
          <span class="card-name">{{ p.name }}</span>
          <span class="card-blurb">{{ p.blurb }}</span>
        </button>

        <button class="card write" @click="writingCustom = !writingCustom">
          <span class="card-icon">✎</span>
          <span class="card-name">{{ t.companionCustom }}</span>
          <span class="card-blurb">{{ t.companionCustomBlurb }}</span>
        </button>
      </div>

      <div v-if="writingCustom" class="custom">
        <textarea
          v-model="customPrompt"
          :placeholder="t.companionCustomPlaceholder"
          rows="4"
          maxlength="600"
        />
        <div class="custom-actions">
          <span class="count">{{ customPrompt.length }} / 600</span>
          <button class="primary" :disabled="!customPrompt.trim()" @click="startCustom">
            {{ t.companionStart }}
          </button>
        </div>
      </div>
    </section>

    <div v-else-if="!connected" class="page-overlay">
      <div v-if="!overlayFailed" class="spinner" />
      <span>{{ overlayText }}</span>
    </div>

    <div v-if="chosen" ref="stageRef" class="stage" />

    <header class="titlebar">
      <button class="icon" :aria-label="t.back" @click="emit('exit')">✕</button>
      <LangToggle class="lang" />
    </header>

    <!-- The only controls: close the mic, and look at what is remembered. Kept to the
         foot of the screen and small, so the room stays the thing on screen. -->
    <footer v-if="chosen" class="controls">
      <button
        class="mic"
        :class="{ on: micOpen }"
        :disabled="!connected"
        @click="toggleMic"
      >
        <span class="mic-icon">{{ micOpen ? '●' : '🎙' }}</span>
        {{ micOpen ? t.companionListening : t.companionMicOff }}
      </button>

      <button class="memory-toggle" @click="showMemory = !showMemory">
        {{ t.companionMemory }}
      </button>
    </footer>

    <!-- What it remembers. Shown on request rather than always: the point of the scene is
         that the memory surfaces in conversation, not that it is displayed. -->
    <aside v-if="showMemory" class="memory">
      <header class="memory-head">
        <span>{{ t.companionMemory }}</span>
        <button class="icon small" :aria-label="t.back" @click="showMemory = false">✕</button>
      </header>

      <p v-if="!remembered || !remembered.size" class="memory-empty">
        {{ t.companionMemoryEmpty }}
      </p>
      <template v-else>
        <p class="memory-meta">{{ t.companionMemoryMeta(remembered.turns, remembered.size) }}</p>
        <p v-if="remembered.summary" class="memory-body">{{ remembered.summary }}</p>
        <p v-else class="memory-body faint">{{ t.companionMemoryRaw }}</p>
      </template>

      <button class="forget" @click="forget">{{ t.companionForget }}</button>
    </aside>

    <PerfPanel :session="session" />
  </div>
</template>

<style scoped>
.room {
  position: relative;
  height: 100vh;
  overflow: hidden;
  background: #1a1410;
}

/* A warm room rather than a workplace: this scene is the one with nothing to get done,
   and the setting is most of what says so. */
.backdrop {
  position: absolute;
  inset: 0;
}
.backdrop::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgb(26 20 16 / 22%) 0%, rgb(26 20 16 / 48%) 100%);
}

.stage {
  position: absolute;
  inset: 0;
}

/* Picker ---------------------------------------------------------------- */

.picker {
  position: absolute;
  inset: 0;
  z-index: 4;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 24px;
  background: rgb(20 14 10 / 62%);
  backdrop-filter: blur(10px);
  color: #fff;
  overflow-y: auto;
}

.picker h1 {
  margin: 0;
  font-size: 21px;
  font-weight: 600;
}
.picker-hint {
  margin: 0 0 14px;
  font-size: 13px;
  color: rgb(255 255 255 / 68%);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
  width: min(100%, 720px);
}

.card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 16px 16px 18px;
  border: 1px solid rgb(255 255 255 / 24%);
  border-radius: 14px;
  background: rgb(38 28 20 / 62%);
  color: #fff;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s, border-color 0.15s, transform 0.15s;
}
.card:hover {
  background: rgb(56 41 28 / 72%);
  border-color: rgb(255 196 120 / 62%);
  transform: translateY(-2px);
}
.card-icon {
  font-size: 22px;
}
.card-name {
  font-size: 15px;
  font-weight: 600;
}
.card-blurb {
  font-size: 12px;
  line-height: 1.5;
  color: rgb(255 255 255 / 66%);
}

/* Writing one is the same shape as picking one, so it sits in the grid rather than below
   it — it is another character, not a settings escape hatch. */
.card.write {
  border-style: dashed;
}

.custom {
  width: min(100%, 720px);
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.custom textarea {
  width: 100%;
  padding: 12px 14px;
  border: 1px solid rgb(255 255 255 / 26%);
  border-radius: 12px;
  background: rgb(28 21 16 / 72%);
  color: #fff;
  font: inherit;
  font-size: 13.5px;
  line-height: 1.6;
  resize: vertical;
  box-sizing: border-box;
}
.custom textarea:focus {
  outline: none;
  border-color: rgb(255 196 120 / 72%);
}
.custom textarea::placeholder {
  color: rgb(255 255 255 / 44%);
}

.custom-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.count {
  font-size: 11.5px;
  color: rgb(255 255 255 / 52%);
}

.primary {
  padding: 9px 22px;
  border: none;
  border-radius: 999px;
  background: rgb(196 124 54);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}
.primary:disabled {
  opacity: 0.42;
  cursor: default;
}

/* Chrome ---------------------------------------------------------------- */

.titlebar {
  position: absolute;
  inset: 0 0 auto;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  background: linear-gradient(180deg, rgb(20 14 10 / 55%) 0%, rgb(20 14 10 / 0%) 100%);
}

.icon {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: rgb(255 255 255 / 16%);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}
.icon:hover {
  background: rgb(255 255 255 / 26%);
}
.icon.small {
  width: 24px;
  height: 24px;
  font-size: 11px;
}

.controls {
  position: absolute;
  inset: auto 0 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 0 16px 34px;
}

/* Outlined and see-through: it floats over the room, and a filled block there reads as a
   piece of UI dropped on the picture. */
.mic {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 22px;
  border: 1.5px solid rgb(255 255 255 / 72%);
  border-radius: 999px;
  background: rgb(20 14 10 / 40%);
  backdrop-filter: blur(8px);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.15s;
}
.mic:hover:not(:disabled) {
  background: rgb(20 14 10 / 58%);
}
.mic:disabled {
  opacity: 0.5;
  cursor: default;
}
/* Listening: warm rather than the usual recording red — nothing here is being recorded
   for anyone else, and red reads as an alarm in a room like this. */
.mic.on {
  border-color: rgb(255 196 120 / 90%);
  background: rgb(140 82 26 / 45%);
}
.mic-icon {
  font-size: 13px;
}

.memory-toggle {
  padding: 11px 16px;
  border: 1.5px solid rgb(255 255 255 / 34%);
  border-radius: 999px;
  background: rgb(20 14 10 / 34%);
  backdrop-filter: blur(8px);
  color: rgb(255 255 255 / 86%);
  font-size: 13px;
  cursor: pointer;
}
.memory-toggle:hover {
  background: rgb(20 14 10 / 52%);
}

/* Memory ---------------------------------------------------------------- */

.memory {
  position: absolute;
  z-index: 3;
  right: 16px;
  bottom: 96px;
  width: min(360px, calc(100% - 32px));
  max-height: 46vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid rgb(255 255 255 / 22%);
  border-radius: 16px;
  background: rgb(28 21 16 / 82%);
  backdrop-filter: blur(18px);
  color: #fff;
  box-shadow: 0 12px 40px rgb(0 0 0 / 38%);
}

.memory-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
}

.memory-meta {
  margin: 0;
  font-size: 11.5px;
  color: rgb(255 255 255 / 62%);
}

.memory-body {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  color: rgb(255 255 255 / 92%);
}
.memory-body.faint {
  color: rgb(255 255 255 / 66%);
}

.memory-empty {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: rgb(255 255 255 / 70%);
}

.forget {
  align-self: flex-start;
  margin-top: 2px;
  padding: 0;
  border: none;
  background: none;
  color: rgb(255 168 168 / 92%);
  font-size: 12px;
  text-decoration: underline;
  cursor: pointer;
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
  background: rgb(20 14 10 / 78%);
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
</style>
