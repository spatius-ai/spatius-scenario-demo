<script setup lang="ts">
import { lang, setLang } from './i18n'
</script>

<template>
  <div class="toggle" role="group" aria-label="Language">
    <button
      type="button"
      :class="{ active: lang === 'en' }"
      :aria-pressed="lang === 'en'"
      @click="setLang('en')"
    >
      EN
    </button>
    <button
      type="button"
      :class="{ active: lang === 'zh' }"
      :aria-pressed="lang === 'zh'"
      @click="setLang('zh')"
    >
      中文
    </button>
  </div>
</template>

<style scoped>
.toggle {
  display: inline-flex;
  border: 1px solid var(--outline-variant);
  border-radius: 999px;
  overflow: hidden;
  background: var(--surface);
  /* The classroom lays this over dark video, so a translucent white base stays
     legible against either background. */
  backdrop-filter: blur(6px);
}

button {
  padding: 5px 12px;
  border: none;
  background: transparent;
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  color: var(--on-surface-variant);
  cursor: pointer;
}

button.active {
  background: var(--primary);
  color: #fff;
}

button:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
</style>
