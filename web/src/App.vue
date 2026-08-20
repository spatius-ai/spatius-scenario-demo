<script setup lang="ts">
import { ref } from 'vue'
import Classroom from './Classroom.vue'
import LiveRoom from './LiveRoom.vue'
import ServiceDesk from './ServiceDesk.vue'
import CompanionRoom from './CompanionRoom.vue'
import ConfigScreen from './ConfigScreen.vue'

// Always start on the config page: it shows the values the backend is actually
// running with, so they get a look before a scene opens and starts billing.
const scene = ref<string | null>(null)
</script>

<template>
  <Classroom v-if="scene === 'tutoring'" @exit="scene = null" />
  <LiveRoom v-else-if="scene === 'live'" @exit="scene = null" />
  <ServiceDesk v-else-if="scene === 'service'" @exit="scene = null" />
  <CompanionRoom v-else-if="scene === 'companion'" @exit="scene = null" />
  <ConfigScreen v-else @submit="(picked) => (scene = picked)" />
</template>
