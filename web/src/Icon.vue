<script setup lang="ts">
/**
 * Line icons. The stroke uses currentColor and the size follows the font size, so
 * colour and size are both decided by the CSS where the icon is used — unlike emoji,
 * which bring their own glyphs and colours on every platform.
 */
const props = defineProps<{ name: 'mic' | 'mic-off' | 'camera' | 'camera-off' }>()

const PATHS: Record<string, string[]> = {
  mic: [
    'M12 2a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V5a3 3 0 0 1 3-3z',
    'M19 10v1a7 7 0 0 1-14 0v-1',
    'M12 18v4',
    'M8 22h8',
  ],
  'mic-off': [
    'M9 9V5a3 3 0 0 1 6 0v4',
    'M15 12v-1',
    'M19 10v1a7 7 0 0 1-11.9 5',
    'M5 10v1',
    'M12 18v4',
    'M8 22h8',
    'M3 3l18 18',
  ],
  camera: ['M15 10.5V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-3.5l7 4v-11z'],
  'camera-off': [
    'M15 10.5V7a2 2 0 0 0-2-2H8',
    'M2 8v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-2',
    'M22 6.5v11l-7-4',
    'M3 3l18 18',
  ],
}
</script>

<template>
  <svg
    class="icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(d, i) in PATHS[props.name]" :key="i" :d="d" />
  </svg>
</template>

<style scoped>
.icon {
  width: 1em;
  height: 1em;
  display: block;
}
</style>
