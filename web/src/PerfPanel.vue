<script setup lang="ts">
/**
 * Frame rate and playback readout, behind a toggle.
 *
 * Two sources, deliberately kept apart on screen because they answer different
 * questions. The frame rate monitor is the SDK's own, and measures how fast this
 * machine renders. The playback stats come from the RTC player, and say how much of
 * what the network sent arrived in time. A session can render at a healthy rate while
 * dropping frames, and vice versa; merging them into one "performance" number would
 * hide exactly the case worth seeing.
 *
 * The monitor is off until switched on, which is also how the SDK ships it — enabling
 * it installs per-frame bookkeeping, so a demo left running should not pay for it.
 */
import { ref, onBeforeUnmount, shallowRef } from 'vue'
import type { TutoringSession } from './rtc-session'
import { t } from './i18n'

const props = defineProps<{ session: TutoringSession | null }>()

type Info = Parameters<Parameters<TutoringSession['setPerfMonitor']>[1] & object>[0]

const open = ref(false)
const info = shallowRef<Info | null>(null)
/**
 * The SDK fires the callback on every rendered frame, not once per window, so binding it
 * straight to the UI repaints the numbers 25 times a second and they are unreadable.
 * The values are 2-second aggregates anyway — sampling them twice a second loses nothing
 * and gives the eye something it can actually follow.
 */
let lastShown = 0
const playback = shallowRef<TutoringSession['playbackStats']>(null)

/** Playback counters are read on a timer: they are cumulative totals with no callback. */
let timer: number | null = null

function toggle(): void {
  open.value = !open.value
  if (open.value) start()
  else stop()
}

function start(): void {
  props.session?.setPerfMonitor(true, (next) => {
    const now = performance.now()
    if (now - lastShown < 500) return
    lastShown = now
    info.value = next
  })
  timer = window.setInterval(() => {
    playback.value = props.session?.playbackStats ?? null
  }, 1000)
}

function stop(): void {
  props.session?.setPerfMonitor(false)
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
  info.value = null
  playback.value = null
}

onBeforeUnmount(stop)


/** One decimal is enough to see a trend; more digits just flicker. */
function ms(value: number): string {
  return `${value.toFixed(1)} ms`
}
</script>

<template>
  <div class="perf">
    <button type="button" class="tab" :class="{ on: open }" :aria-expanded="open" @click="toggle">
      {{ t.perfTitle }}
    </button>

    <div v-if="open" class="panel" role="status">
      <template v-if="info">
        <div class="row lead">
          <span>{{ t.perfFps }}</span>
          <strong>{{ Math.round(info.fps) }}</strong>
        </div>
        <div class="row">
          <span>{{ t.perfPresentationFps }}</span>
          <strong>{{ Math.round(info.presentationFps) }}</strong>
        </div>
        <div class="row">
          <span>{{ t.perfJank }}</span>
          <strong>{{ Math.round(info.jankRatioPercent) }}%</strong>
        </div>
        <div class="row">
          <span>{{ t.perfFrameTime }}</span>
          <strong>{{ ms(info.averageFrameTimeMs) }}</strong>
        </div>
        <div class="row">
          <span>{{ t.perfIntervalP95 }}</span>
          <strong>{{ ms(info.frameIntervalP95Ms) }}</strong>
        </div>
        <div class="row">
          <span>{{ t.perfCpu }}</span>
          <strong>{{ info.cpuUsagePercent.toFixed(0) }}%</strong>
        </div>
      </template>
      <p v-else class="waiting">{{ t.perfWaiting }}</p>

      <template v-if="playback">
        <div class="divider">{{ t.perfPlayback }}</div>
        <div class="row">
          <span>{{ t.perfFramesTotal }}</span>
          <strong>{{ playback.totalFrames }}</strong>
        </div>
        <div class="row">
          <span>{{ t.perfDropped }}</span>
          <strong>{{ playback.totalDropped }}</strong>
        </div>
        <div class="row">
          <span>{{ t.perfSkipped }}</span>
          <strong>{{ playback.totalSkipped }}</strong>
        </div>
        <div class="row">
          <span>{{ t.perfStarved }}</span>
          <strong>{{ playback.jitterStarved }}</strong>
        </div>
      </template>

      <p class="note">{{ t.perfNote }}</p>
    </div>
  </div>
</template>

<style scoped>
/* Fixed rather than placed in each scene's header: the four scenes lay their top bars
   out differently, and an expanding panel dropped into those flex rows distorts them.
   Above every z-index in use across the scenes (highest is 10). */
.perf {
  position: fixed;
  right: 12px;
  bottom: 12px;
  z-index: 50;
  display: flex;
  /* Reversed so the panel grows upward from the button, instead of off the bottom
     of the screen. */
  flex-direction: column-reverse;
  align-items: flex-end;
  gap: 6px;
}

.tab {
  padding: 5px 12px;
  border: 1px solid var(--outline-variant);
  border-radius: 999px;
  background: var(--surface);
  backdrop-filter: blur(6px);
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  color: var(--on-surface-variant);
  cursor: pointer;
}

.tab.on {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}

.tab:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.panel {
  width: 200px;
  max-height: 60vh;
  overflow-y: auto;
  padding: 10px 12px;
  border: 1px solid var(--outline-variant);
  border-radius: 12px;
  background: var(--surface);
  backdrop-filter: blur(6px);
  font-size: 12px;
  line-height: 1.6;
  color: var(--on-surface-variant);
}

.row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

/* Tabular figures so the numbers stop jittering sideways as they update. */
.row strong {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.row.lead strong {
  font-size: 15px;
}

.divider {
  margin: 8px 0 4px;
  padding-top: 8px;
  border-top: 1px solid var(--outline-variant);
  font-size: 11px;
  opacity: 0.75;
}

.waiting,
.note {
  margin: 0;
  font-size: 11px;
  opacity: 0.7;
}

.note {
  margin-top: 8px;
}
</style>
