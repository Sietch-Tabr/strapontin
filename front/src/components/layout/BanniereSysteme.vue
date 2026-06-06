<template>
  <Transition name="banniere-slide">
    <div
      v-if="visible && banniere"
      class="banniere-overlay"
      :class="'banniere-overlay--' + banniere.type"
      :role="banniere.type === 'error' ? 'alert' : 'status'"
    >
      <svg class="banniere-icone icon" aria-hidden="true">
        <use :href="'/icons/lucide-sprite.svg#' + icone" />
      </svg>
      <span class="banniere-message">{{ banniere.message }}</span>
      <button
        type="button"
        class="banniere-fermer"
        aria-label="Fermer la bannière"
        @click="fermer"
      >
        <svg class="icon" aria-hidden="true"><use href="/icons/lucide-sprite.svg#icon-x" /></svg>
      </button>
      <div
        v-if="delai > 0"
        class="banniere-progression"
        :style="{ '--duree-banniere': delai + 'ms' }"
      ></div>
    </div>
  </Transition>
</template>

<script setup>
defineOptions({ name: 'BanniereSysteme' })

import { computed } from 'vue'
import { useBanniere } from '../../composables/useBanniere.js'

const { banniere, visible, fermer } = useBanniere()

const DELAIS = { info: 10000, warning: 20000 }
const delai = computed(() => DELAIS[banniere.value?.type] ?? 0)

const ICONES = { info: 'icon-info', warning: 'icon-triangle-alert', error: 'icon-circle-x' }
const icone = computed(() => ICONES[banniere.value?.type] ?? 'icon-info')
</script>
