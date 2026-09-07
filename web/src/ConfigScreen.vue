<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchBackendConfig, saveBackendConfig, type BackendConfig } from './config'
import { asset } from './asset'
import { lang, t } from './i18n'
import LangToggle from './LangToggle.vue'

const emit = defineEmits<{ submit: [scene: string] }>()

const config = ref<BackendConfig>({})
const loading = ref(true)
const saving = ref(false)
/**
 * Only the reason is stored; the sentence around it is assembled in a computed.
 *
 * Built eagerly it would freeze whatever language was current when the request failed,
 * so switching afterwards leaves the old language sitting on screen.
 */
const errorReason = ref('')
const errorFromBackend = ref(false)
const error = computed(() =>
  errorReason.value
    ? errorFromBackend.value
      ? `${errorReason.value} — ${t.value.backendDown}`
      : errorReason.value
    : '',
)
/**
 * Two steps: fill in both providers' credentials, then pick a teacher.
 *
 * Credentials are found by following screenshots into each console, so the images need
 * room on screen; picking an avatar is just a couple of choices. Cramming both into one
 * screen leaves neither with enough space, whereas split up, each step does one thing.
 */
const step = ref<1 | 2>(1)

onMounted(async () => {
  try {
    config.value = await fetchBackendConfig()
  } catch (err) {
    errorReason.value = (err as Error).message
    errorFromBackend.value = true
  } finally {
    loading.value = false
  }
})

async function submit(): Promise<void> {
  saving.value = true
  errorReason.value = ''
  errorFromBackend.value = false
  try {
    await saveBackendConfig(config.value)
    emit('submit', scene.value)
  } catch (err) {
    errorReason.value = (err as Error).message
  } finally {
    saving.value = false
  }
}

/**
 * Saves the credentials before moving to step two.
 *
 * Submitting only on the last step is not enough: credentials are copied over from the
 * consoles one field at a time, and anything that interrupts step two (closing the page,
 * a reload, going back to a console to look something up) would lose all of it and mean
 * starting over.
 *
 * A failed save still advances — the final step submits the whole thing again, so there
 * is no reason to block here.
 */
async function next(): Promise<void> {
  step.value = 2
  try {
    await saveBackendConfig(config.value)
  } catch {
    // Ignored: the final step writes it again, and only that failure is worth reporting.
  }
}

/** The grouping only affects layout — the whole set is written back in one submit. */
const SPATIUS_FIELDS = computed(
  () =>
    [
      // App ID first: it is the one shown on the app page itself, and the key is
      // generated from there — asking for them the other way round reads backwards.
      ['SPATIUS_APP_ID', t.value.fieldAppId],
      ['SPATIUS_API_KEY', t.value.fieldApiKey],
    ] as const,
)

const LIVEKIT_FIELDS = computed(
  () =>
    [
      ['LIVEKIT_URL', t.value.fieldLivekitUrl],
      ['LIVEKIT_API_KEY', t.value.fieldApiKey],
      ['LIVEKIT_API_SECRET', t.value.fieldApiSecret],
    ] as const,
)

/**
 * The Agora path needs only three fields: the model and the voice are configured on the
 * agent in Agora's console, and publishing it gives a pipeline id to reference, so ASR /
 * LLM / TTS need not be filled in here (verified: starting an agent with pipeline_id
 * alone returns RUNNING, and update still overrides the persona set in the console).
 *
 * The other two cannot be avoided: signing a join token requires the App ID and the
 * primary certificate, which live at the RTC layer rather than in the agent config.
 */
const AGORA_FIELDS = computed(
  () =>
    [
      ['AGORA_APP_ID', t.value.fieldAppId],
      ['AGORA_APP_CERTIFICATE', t.value.fieldAppCertificate],
      ['AGORA_PIPELINE_ID', t.value.fieldPipelineId],
    ] as const,
)

/**
 * The sample rate at which the avatar receives audio, which has to match the output of
 * the TTS configured in the console.
 *
 * A dropdown rather than a text field: there are only these few valid values (the set
 * Motion Server supports), and a wrong one raises no error — the avatar simply stays
 * silent. The default of 24000 is also what TTS providers that do not expose a rate,
 * such as OpenAI, actually output, so most people never touch it.
 */
const SAMPLE_RATES = [8000, 16000, 22050, 24000, 32000, 44100, 48000] as const
const DEFAULT_SAMPLE_RATE = '24000'

const sampleRate = computed({
  get: () => config.value.AGORA_AVATAR_SAMPLE_RATE || DEFAULT_SAMPLE_RATE,
  set: (value: string) => {
    config.value.AGORA_AVATAR_SAMPLE_RATE = value
  },
})

/** The currently selected transport. Defaults to livekit, matching the backend. */
const transport = computed({
  get: () => (config.value.TRANSPORT === 'agora' ? 'agora' : 'livekit'),
  set: (value: string) => {
    config.value.TRANSPORT = value
  },
})

/** Validates only the fields the current transport needs: switching to Agora should not
 *  be blocked by empty LiveKit fields. */
const TRANSPORT_FIELDS = computed(() =>
  transport.value === 'agora' ? AGORA_FIELDS.value : LIVEKIT_FIELDS.value,
)

/** Step two unlocks only once the credentials are complete — one missing field means no
 *  connection, so it is better to stop here. */
const credentialsReady = computed(() =>
  [...SPATIUS_FIELDS.value, ...TRANSPORT_FIELDS.value].every(([key]) =>
    (config.value[key] ?? '').trim(),
  ),
)

/**
 * The voices on offer, all hosted by LiveKit Inference so switching needs no extra
 * credentials.
 *
 * Each entry has a sample reading the same line, so they can be compared by ear rather
 * than by name — which of them reads Mandarin, and how each one sounds, is not something
 * the model name tells you. Regenerate the samples with the script in the repo notes if
 * the list changes.
 *
 * Not everything Inference carries: see `TTSModels` in livekit.agents.inference for the
 * full set, which also includes deepgram, rime and xai.
 *
 * The male/female label describes the voice each model **defaults** to, not a property of
 * the model: set TTS_VOICE in .env to pin any other voice from that provider's library and
 * the label no longer applies.
 */
const TTS_OPTIONS = [
  // Grouped by the voice each model defaults to, since that is the first thing anyone
  // picking one cares about. The reading is what these were sorted by — the model name
  // says nothing about it.
  { value: 'fishaudio/s2.1-pro', sample: asset('voice-fishaudio_s21_pro.wav'), sampleEn: asset('voice-fishaudio_s21_pro_en.wav'), voice: 'female', note: '' },
  { value: 'fishaudio/s2-pro', sample: asset('voice-fishaudio_s2_pro.wav'), sampleEn: asset('voice-fishaudio_s2_pro_en.wav'), voice: 'female', note: '' },
  { value: 'cartesia/sonic-3', sample: asset('voice-cartesia_sonic_3.wav'), sampleEn: asset('voice-cartesia_sonic_3_en.wav'), voice: 'male', note: '' },
  { value: 'cartesia/sonic-2', sample: asset('voice-cartesia_sonic_2.wav'), sampleEn: asset('voice-cartesia_sonic_2_en.wav'), voice: 'male', note: '' },
  {
    value: 'cartesia/sonic-turbo',
    sample: asset('voice-cartesia_sonic_turbo.wav'), sampleEn: asset('voice-cartesia_sonic_turbo_en.wav'),
    voice: 'male',
    note: '',
  },
  {
    value: 'inworld/inworld-tts-2',
    sample: asset('voice-inworld_inworld_tts_2.wav'), sampleEn: asset('voice-inworld_inworld_tts_2_en.wav'),
    voice: 'male',
    note: '',
  },
  {
    value: 'inworld/inworld-tts-1.5',
    sample: asset('voice-inworld_inworld_tts_15.wav'), sampleEn: asset('voice-inworld_inworld_tts_15_en.wav'),
    voice: 'male',
    note: '',
  },
] as const

/**
 * Voice preview. Only one clip plays at a time: clicking two voices in a row should have
 * the second displace the first, or the two overlap and neither can be judged.
 */
const playing = ref('')
let audio: HTMLAudioElement | null = null
/** One <audio> per clip: once created it is buffered, so a second click plays instantly. */
const cache = new Map<string, HTMLAudioElement>()

function preview(option: (typeof TTS_OPTIONS)[number]): void {
  const wasPlaying = playing.value === option.value
  stopPreview()
  // Clicking the same one again stops it.
  if (wasPlaying) return

  // Reuse the existing instance: on the first click the element has not buffered and a
  // direct play() is rejected, and if the failure path only cleared `playing` while
  // keeping the audio, clicking the same voice again would fall into the stop branch —
  // presenting as "nothing on the first click, sound on the second".
  // These voices read both Chinese and English, and a Chinese sample tells you
  // nothing about how one sounds in English — so follow the UI language.
  const src = lang.value === 'en' ? option.sampleEn : option.sample
  const el = cache.get(src) ?? new Audio(src)
  cache.set(src, el)

  audio = el
  playing.value = option.value
  el.currentTime = 0
  el.onended = stopPreview
  void el.play().catch(stopPreview)
}

function stopPreview(): void {
  audio?.pause()
  audio = null
  playing.value = ''
}

/**
 * The scenes on offer. Adding one means appending here and handling the id in App.vue —
 * the card art is the scene's own background, so a new entry needs a matching image in
 * public/.
 */
const SCENE_OPTIONS = [
  { id: 'tutoring', name: 'tutoring', photo: asset('classroom-background.jpg') },
  { id: 'live', name: 'live', photo: asset('live-room-bg.jpg') },
  { id: 'service', name: 'service', photo: asset('bank-hall-bg.jpg') },
  { id: 'companion', name: 'companion', photo: asset('companion-room-bg.jpg') },
] as const

const scene = ref<string>(SCENE_OPTIONS[0].id)

/** Scene names come from the copy table so they follow the language toggle. */
function sceneName(id: string): string {
  if (id === 'live') return t.value.sceneLive
  if (id === 'service') return t.value.sceneService
  if (id === 'companion') return t.value.sceneCompanion
  return t.value.sceneTutoring
}

/**
 * The public sample avatars, copied verbatim from `src/data/characters.ts`, which the web
 * clients in the official spatius-avatar-demo repo share. Keep this in sync with that file
 * rather than starting a separate set here.
 */
const AVATAR_OPTIONS = [
  { id: '41c62a7c-993c-4b6b-b6d3-549ce3c8be00', name: 'Kian', photo: asset('avatar-kian.jpg') },
  { id: 'dbb01388-7c57-47bf-ab59-c492caeb9d90', name: 'Julian', photo: asset('avatar-julian.jpg') },
  { id: 'd51ab422-3db7-47cc-afa8-7273b02bc70b', name: 'Clara', photo: asset('avatar-clara.jpg') },
  { id: 'c7069121-8245-4015-9940-82d0dc0c6bda', name: 'Halima', photo: asset('avatar-halima.jpg') },
  { id: '8b86dda1-98ed-4acd-8a4e-b1a00ba69268', name: 'Leyla', photo: asset('avatar-leyla.jpg') },
  { id: '566981dd-1d95-4844-953e-d67e18b2fde8', name: 'Adrian', photo: asset('avatar-adrian.jpg') },
  { id: '981ed26d-fbfe-42eb-a5d5-56ebd104847b', name: 'Haru', photo: asset('avatar-haru.jpg') },
  { id: '56f31c71-58ff-410f-85d2-11b9658c7b49', name: 'Ethan', photo: asset('avatar-ethan.jpg') },
  { id: 'e06640cb-e011-4806-bd3e-6b07575eff2e', name: 'Samir', photo: asset('avatar-samir.jpg') },
] as const
</script>

<template>
  <div class="config">
    <LangToggle class="lang" />

    <header class="head">
      <ol class="steps">
        <li :class="{ active: step === 1, done: step > 1 }">{{ t.stepCredentials }}</li>
        <li :class="{ active: step === 2 }">{{ t.stepTeacher }}</li>
      </ol>
    </header>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="loading" class="hint">{{ t.loadingConfig }}</p>

    <!-- Step one: each provider's credentials get their own card, with screenshots
         showing where to find them. -->
    <template v-else-if="step === 1">
      <div class="cards">
        <section class="card">
          <h3>{{ t.sectionSpatius }}</h3>
          <label v-for="[key, label] in SPATIUS_FIELDS" :key="key">
            {{ label }}
            <input v-model="config[key]" spellcheck="false" />
          </label>
          <a class="guide" href="https://app.spatius.ai/apps?utm_source=spatius-scenario-demo" target="_blank" rel="noreferrer">
            <img :src="asset('api-key-guide.png')" :alt="t.guideAlt" />
            <span>{{ t.guideCaption }}</span>
          </a>
        </section>

        <!--
          The transport belongs in the same card as the credentials it requires: which one
          you pick and what you then fill in is a single decision.
          Split across two cards, the middle one holds nothing but two radio buttons and
          the right one has to repeat "LiveKit / Agora" in its heading, which reads as two
          unrelated things.
        -->
        <section class="card">
          <h3>{{ t.sectionTransport }}</h3>
          <!-- A segmented control for the either/or: the two transports are mutually
               exclusive peers and do not each warrant a card. -->
          <div class="transports">
            <label
              v-for="option in (['livekit', 'agora'] as const)"
              :key="option"
              class="transport"
              :class="{ on: transport === option }"
            >
              <input v-model="transport" type="radio" name="transport" :value="option" />
              {{ option === 'agora' ? t.transportAgora : t.transportLivekit }}
            </label>
          </div>

          <template v-if="transport === 'livekit'">
            <label v-for="[key, label] in LIVEKIT_FIELDS" :key="key">
              {{ label }}
              <input v-model="config[key]" spellcheck="false" />
            </label>
            <a class="guide" href="https://cloud.livekit.io/" target="_blank" rel="noreferrer">
              <!-- Two steps: open Settings, then look at API keys. -->
              <div class="shots">
                <img :src="asset('livekit-guide-1.jpg')" :alt="t.livekitGuideAlt1" />
                <img :src="asset('livekit-guide-2.jpg')" :alt="t.livekitGuideAlt2" />
              </div>
              <span>{{ t.livekitGuideCaption }}</span>
            </a>
          </template>

          <template v-else>
            <label v-for="[key, label] in AGORA_FIELDS" :key="key">
              {{ label }}
              <input v-model="config[key]" spellcheck="false" />
            </label>
            <a class="guide" href="https://console.agora.io/" target="_blank" rel="noreferrer">
              <!--
                Four steps, in the same order as the three fields above: pick the project
                under Projects -> take the App ID and certificate -> find the agent under
                Agents -> publish it and take the pipeline id from Code.
              -->
              <div class="shots quad">
                <img :src="asset('agora-guide-1.jpg')" :alt="t.agoraGuideAlt1" />
                <img :src="asset('agora-guide-2.jpg')" :alt="t.agoraGuideAlt2" />
                <img :src="asset('agora-guide-3.jpg')" :alt="t.agoraGuideAlt3" />
                <img :src="asset('agora-guide-4.jpg')" :alt="t.agoraGuideAlt4" />
              </div>
              <span>{{ t.agoraPipelineNote }}</span>
            </a>
          </template>
        </section>

        <footer class="actions">
          <p v-if="!credentialsReady" class="hint">{{ t.fillAllFirst }}</p>
          <button class="primary" type="button" :disabled="!credentialsReady" @click="next()">
            {{ t.next }}
          </button>
        </footer>
      </div>
    </template>

    <!-- Step two: pick a teacher. -->
    <template v-else>
      <form class="cards wide" @submit.prevent="submit">
        <section class="card">
          <h3>{{ t.sectionAvatar }}</h3>
          <div class="avatars">
            <label v-for="a in AVATAR_OPTIONS" :key="a.id" class="avatar">
              <input
                v-model="config.SPATIUS_AVATAR_ID"
                type="radio"
                name="avatar"
                :value="a.id"
              />
              <img :src="a.photo" :alt="a.name" />
            </label>
          </div>
        </section>

        <!--
          The voice section appears on both paths, but only one of them can be chosen
          here: LiveKit's models are hosted by Inference, so they can simply be listed and
          previewed, while the Agora voice belongs to the agent in the console and cannot
          be changed from here, so an image points the way instead.
        -->
        <section class="card">
          <h3>{{ t.fieldTtsModel }}</h3>

          <div v-if="transport === 'livekit'" class="voices">
            <label v-for="o in TTS_OPTIONS" :key="o.value" class="voice">
              <input v-model="config.TTS_MODEL" type="radio" name="voice" :value="o.value" />
              <span class="name">
                {{ o.value }}
                <em class="sex">{{ o.voice === 'male' ? t.voiceMale : t.voiceFemale }}</em>
              </span>
              <!-- A button rather than part of the label: previewing should not also
                   select the voice. -->
              <button
                class="play"
                type="button"
                :aria-label="t.preview"
                @click.prevent="preview(o)"
              >
                {{ playing === o.value ? '■' : '▶' }}
              </button>
            </label>
          </div>

          <template v-else>
            <a class="guide" href="https://console.agora.io/" target="_blank" rel="noreferrer">
              <!-- Top image: where the voice is changed. Bottom: the sample rate on the
                   same panel. -->
              <div class="shots">
                <img :src="asset('agora-voice-guide.jpg')" :alt="t.agoraVoiceGuideAlt" />
                <img :src="asset('agora-guide-5.jpg')" :alt="t.agoraGuideAlt5" />
              </div>
            </a>

            <!--
              Recognition is the other half of this: the backend sends an asr block naming
              a vendor, model and credential, and those have to be the ones the console
              agent actually uses. A mismatch is silent — speech comes back transcribed as
              nonsense or as nothing at all, with no error anywhere.
            -->
            <a class="guide" href="https://console.agora.io/" target="_blank" rel="noreferrer">
              <!-- Left: which ASR the backend expects. Right: where the values to copy
                   into agora.py are, for anyone who changed it. -->
              <div class="shots">
                <img :src="asset('agora-asr-guide.jpg')" :alt="t.agoraAsrGuideAlt" />
                <img :src="asset('agora-asr-params-guide.jpg')" :alt="t.agoraAsrParamsGuideAlt" />
              </div>
              <span>{{ t.agoraAsrNote }}</span>
            </a>

            <!--
              The sample rate has to match the output of the TTS above: the avatar does
              not resample, so a mismatch leaves the picture normal and the audio silent,
              with neither side reporting an error. Leave it at 24000 for providers that
              do not expose the setting, such as OpenAI.
            -->
            <label class="rate">
              {{ t.fieldSampleRate }}
              <select v-model="sampleRate">
                <option v-for="rate in SAMPLE_RATES" :key="rate" :value="String(rate)">
                  {{ rate }}
                </option>
              </select>
            </label>
            <p class="note">{{ t.sampleRateNote }}</p>
          </template>
        </section>

        <section class="card">
          <h3>{{ t.sectionScene }}</h3>
          <div class="scenes">
            <label v-for="sc in SCENE_OPTIONS" :key="sc.id" class="scene">
              <input v-model="scene" type="radio" name="scene" :value="sc.id" />
              <img :src="sc.photo" :alt="sceneName(sc.id)" />
              <span>{{ sceneName(sc.id) }}</span>
            </label>
          </div>
        </section>

        <footer class="actions">
          <button class="ghost" type="button" @click="step = 1">{{ t.back }}</button>
          <button class="primary" type="submit" :disabled="saving">
            {{ saving ? t.saving : t.start }}
          </button>
        </footer>
      </form>
    </template>
  </div>
</template>

<style scoped>
.config {
  position: relative;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
  padding: 24px;
  box-sizing: border-box;
  overflow: auto;
}

.lang {
  position: absolute;
  top: 20px;
  left: 20px;
  z-index: 1;
}

.head {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  /* Leaves room for the language toggle in the top-left corner. */
  padding-top: 12px;
  text-align: center;
}

/* The step bar shows progress only and is not clickable — jumping to step two with the
   credentials unfinished would serve no purpose. */
.steps {
  display: flex;
  gap: 8px;
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
  counter-reset: step;
}

.steps li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 14px 5px 8px;
  border-radius: 999px;
  background: var(--surface-variant);
  font-size: 12px;
  color: var(--on-surface-variant);
  counter-increment: step;
}

.steps li::before {
  content: counter(step);
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--outline-variant);
  color: #fff;
  font-size: 11px;
}

.steps li.active {
  background: var(--primary);
  color: #fff;
}

.steps li.active::before {
  background: rgba(255, 255, 255, 0.28);
}

.steps li.done::before {
  content: '✓';
  background: var(--correct, var(--primary));
}

.cards {
  display: flex;
  flex-wrap: wrap;
  /*
    Each card is sized to its own content rather than aligned: the two columns genuinely
    hold different amounts (the Agora column's four images make it much taller than the
    Spatius one with its two fields). Aligning them stretches the shorter card, pushing
    its image and caption to the bottom and leaving a large gap in the middle.
  */
  align-items: flex-start;
  justify-content: center;
  gap: 24px;
  width: 100%;
  max-width: 1100px;
}

/* Step two has three columns, one more than step one, so the max width is raised to keep
   the columns from being squeezed. */
.cards.wide {
  max-width: none;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  /* Equal widths: the same basis with no content-driven flexing, so the images in each
     column come out the same width. */
  flex: 1 1 0;
  min-width: 340px;
  padding: 24px 28px;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--outline-variant);
  box-shadow: 0 4px 10px 0 rgba(43, 38, 35, 0.05);
}

.card.narrow {
  flex: 0 1 520px;
}

.card h3 {
  margin: 0;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--on-surface-variant);
}

.card h3.spaced {
  margin-top: 10px;
}

label {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12px;
  color: var(--on-surface-variant);
}

/* Avatars: two columns, with the selected one outlined in the accent colour.
   
   Capped at two and a half rows and scrolled. The list grows as characters are added, and
   left to its full height it pushes the voice list and the scene picker off the bottom of
   the step — the half row is what says there is more below rather than leaving a clean
   edge that reads as the end. */
.avatars {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  /* Two and a half rows, so the list scrolls rather than pushing the voice and scene
     pickers off the bottom of the step. The half row is what says there is more below —
     a clean edge reads as the end of the list.

     Derived from the tiles rather than a fixed pixel value. Each is a square in a
     two-column grid, so 2.5 rows plus the two gaps between them come to 1.25 times the
     list's own width — expressed as an aspect ratio, which resolves against the width
     where a percentage max-height would resolve against the parent's height. */
  aspect-ratio: 1 / 1.25;
  overflow-y: auto;
  /* Keeps the scrollbar clear of the tiles rather than overlapping them. */
  padding-right: 4px;
}

.avatar {
  position: relative;
  gap: 8px;
  cursor: pointer;
}

.avatar input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.avatar img {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  border-radius: 14px;
  border: 2px solid transparent;
  transition: border-color 0.15s ease;
}

.avatar:hover img {
  border-color: var(--outline-variant);
}

.avatar input:checked ~ img {
  border-color: var(--primary);
}

.avatar input:focus-visible ~ img {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

/* Scenes: two across. The tiles are illustration and a half-width one is large enough to
   read the art in, where a third of the row reduces it to a thumbnail. */
.scenes {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.scene {
  position: relative;
  gap: 8px;
  cursor: pointer;
}

.scene input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.scene img {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  border-radius: 14px;
  border: 2px solid transparent;
  transition: border-color 0.15s ease;
}

/* The name sits centred on the image: scene art is illustration and, unlike an avatar,
   has no face to keep clear. A dark scrim underneath keeps white text legible on light
   illustrations. */
.scene span {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  /* Room to breathe: a two-word English name wraps rather than running edge to edge. */
  padding: 0 12px;
  box-sizing: border-box;
  border-radius: 14px;
  background: rgba(15, 32, 68, 0.38);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.3;
  text-align: center;
  text-shadow: 0 1px 6px rgba(0, 0, 0, 0.35);
  pointer-events: none;
}

.scene:hover img {
  border-color: var(--outline-variant);
}

.scene input:checked ~ img {
  border-color: var(--primary);
}

/* Selected: the scrim recedes a little to show more of the image. */
.scene input:checked ~ span {
  background: rgba(15, 32, 68, 0.18);
}

/* Voices: one per row, with preview on the right. */
/* Small explanatory text, following a heading. */
/* Small explanatory text. Below a segmented control it takes a top margin to separate it
   from the fields underneath. */
.note {
  margin: 8px 0 10px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--on-surface-variant);
}

/* The sample rate follows the guide image: read the value off the image, then come back
   and pick it. */
.rate {
  margin-top: 12px;
}

.rate + .note {
  margin-bottom: 0;
}

/* Segmented control: two segments in a row inside a border, with the selected one
   filled. */
.transports {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--outline-variant);
  border-radius: 9px;
  background: rgba(0, 0, 0, 0.03);
}

.transport {
  padding: 7px 10px;
  border-radius: 7px;
  font-size: 13px;
  color: var(--on-surface-variant);
  text-align: center;
  cursor: pointer;
  user-select: none;
}

.transport.on {
  background: var(--surface);
  color: #2b2623;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}

/* The radio input is kept for semantics only; the selected state is expressed by the
   segment itself. */
.transport input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.voices {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.voice {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--outline-variant);
  border-radius: 10px;
  cursor: pointer;
}

.voice:has(input:checked) {
  border-color: var(--primary);
  background: rgba(154, 154, 192, 0.12);
}

.voice input {
  margin: 0;
  accent-color: var(--primary);
}

.voice .name {
  flex: 1;
  font-size: 13px;
  color: #2b2623;
}

.voice em {
  margin-left: 6px;
  font-style: normal;
  font-size: 11px;
  color: var(--on-surface-variant);
}

/* The reading is the first thing anyone scanning the list is looking for, so it gets a
   chip of its own rather than sitting in the same grey as the model name. */
.voice em.sex {
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--surface-variant);
  color: var(--on-surface-variant);
  font-size: 10.5px;
}

.play {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  border: 1px solid var(--outline-variant);
  border-radius: 50%;
  background: var(--surface);
  font-size: 11px;
  color: var(--primary);
  cursor: pointer;
}

.play:hover {
  border-color: var(--primary);
}

input,
select {
  padding: 10px 12px;
  border: 1px solid var(--outline-variant);
  border-radius: 10px;
  background: var(--surface);
  font: inherit;
  font-size: 14px;
  color: #2b2623;
}

input:focus-visible,
select:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 1px;
  border-color: transparent;
}

/* Guide images follow the inputs they belong to, so following them to the right console
   page never means leaving this block. */
.guide {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 4px;
  text-decoration: none;
}

/* Two side by side: stacking them would make this column far longer than the other. Side
   by side each is only half a column wide, too small to make out the red boxes, so the
   caption spells out the full path (Settings -> API keys) and the images only indicate
   roughly where to look — clicking through opens the full-size original. */
.shots {
  display: flex;
  gap: 8px;
}

.shots img {
  min-width: 0;
  flex: 1;
}

/* Four images in two rows: one row would leave each a quarter of a column wide, at which
   point the text in the screenshots is unreadable. */
.shots.quad {
  display: grid;
  grid-template-columns: 1fr 1fr;
}

.guide img {
  width: 100%;
  border-radius: 12px;
  border: 1px solid var(--outline-variant);
  /* Shown whole. Cropping them to a shared aspect ratio lined the columns up but cut the
     bottom off the taller console screenshots, hiding the very field the caption is
     pointing at. */
  height: auto;
  object-fit: contain;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
  transform-origin: center;
}

/* Hover zooms in to make the red boxes readable: shrunk down, the detail is lost. The
   enlarged image overlaps other elements, so it needs a raised z-index, and since
   transform only affects stacking order on positioned elements, position comes with it. */
.guide img:hover {
  position: relative;
  z-index: 2;
  transform: scale(2);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}

/* The side-by-side pair is only half a column wide, where 2x is still not enough, so
   they go to 3x. */
.shots img:hover {
  transform: scale(3);
}

@media (prefers-reduced-motion: reduce) {
  .guide img {
    transition: none;
  }
}

.guide span {
  font-size: 12px;
  line-height: 1.6;
  color: var(--on-surface-variant);
}

.guide:hover span {
  color: var(--primary);
}

/* Pushing the image area to the bottom of the card puts both columns' screenshots on the
   same line. */
/*
  The images are no longer pinned to the bottom of the card: that was for the equal-height
  layout, and now that each card is sized to its content, keeping it would only open a gap
  between the fields and the image.
*/
.card .guide {
  margin-top: 12px;
}

.hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--on-surface-variant);
}

.error {
  margin: 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(214, 69, 69, 0.1);
  color: var(--wrong);
  font-size: 13px;
  line-height: 1.6;
}

.actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  /* Separated from the block above: pressed against it, it reads as part of that block,
     when it is actually the next step for the whole page. */
  padding: 20px 0 8px;
  /* Inside .cards it takes a row of its own, below the columns. */
  flex-basis: 100%;
}

.primary {
  padding: 16px 56px;
  border: none;
  border-radius: 14px;
  background: var(--primary);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  cursor: pointer;
}

.primary:disabled {
  background: var(--outline-variant);
  color: var(--on-surface-variant);
  cursor: not-allowed;
}

.ghost {
  padding: 16px 36px;
  border: 1px solid var(--outline-variant);
  border-radius: 14px;
  background: transparent;
  font-size: 18px;
  color: var(--on-surface-variant);
  cursor: pointer;
}
</style>
