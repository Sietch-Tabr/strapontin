<template>
  <div class="toast-conteneur">
    <div
      v-for="toast in toasts"
      :key="toast.id"
      class="alerte"
      :class="'alerte-' + toast.type"
      :role="toast.type === 'erreur' ? 'alert' : 'status'"
      :style="{ '--indice-toast': toast.indice }"
    >
      {{ toast.message }}
      <button type="button" class="toast-fermer" aria-label="Fermer" @click="fermerToast(toast.id)">
        ×
      </button>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ToastContainer' })

import { useToasts } from '../../composables/useToasts.js'

const { toasts, fermerToast } = useToasts()
</script>
