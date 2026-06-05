<template>
  <div
    v-if="visible"
    id="banniere-systeme"
    class="banniere"
    :class="'banniere--' + banniere.type"
    :data-banniere-id="banniere.id"
    :data-banniere-type="banniere.type"
  >
    <span class="banniere-message">{{ banniere.message }}</span>
    <button type="button" id="btn-fermer-banniere" class="banniere-fermer" aria-label="Fermer la bannière" @click="fermer">
      <svg class="icon" aria-hidden="true"><use href="/icons/lucide-sprite.svg#icon-x" /></svg>
    </button>
  </div>
</template>

<script setup>
defineOptions({ name: 'BanniereSysteme' })

import { ref, watch, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  banniere: { type: Object, default: null },
})

const visible = ref(false)
let minuterie = null
let intervalPoll = null

const DELAIS = { info: 10000, warning: 20000 }

watch(
  () => props.banniere,
  (val) => {
    if (minuterie) clearTimeout(minuterie)
    if (!val?.message) {
      visible.value = false
      return
    }
    if (sessionStorage.getItem('banniere_dismissed_id') === val.id) {
      visible.value = false
      return
    }
    visible.value = true
    const delai = DELAIS[val.type]
    if (delai) {
      minuterie = setTimeout(() => {
        visible.value = false
        if (val.id) sessionStorage.setItem('banniere_dismissed_id', val.id)
      }, delai)
    }
  },
  { immediate: true },
)

onMounted(() => {
  intervalPoll = setInterval(async () => {
    try {
      const rep = await fetch('/api/banniere/json')
      const data = await rep.json()
      // Le composant parent gère la mise à jour via prop — on émet un événement
      emit('update:banniere', data)
    } catch (_) {}
  }, 60000)
})

onUnmounted(() => {
  if (minuterie) clearTimeout(minuterie)
  if (intervalPoll) clearInterval(intervalPoll)
})

const emit = defineEmits(['update:banniere'])

function fermer() {
  if (props.banniere?.id) {
    sessionStorage.setItem('banniere_dismissed_id', props.banniere.id)
  }
  visible.value = false
  if (minuterie) clearTimeout(minuterie)
}
</script>
