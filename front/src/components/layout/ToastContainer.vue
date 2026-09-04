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

<style scoped>
  .toast-fermer {
    position: absolute;
    top: 0.5rem;
    right: 0.5rem;
    background: none;
    border: none;
    cursor: pointer;
    font-size: 1.125rem;
    line-height: 1;
    color: inherit;
    opacity: 0.5;
    padding: 0.25rem 0.375rem;
    border-radius: 0.25rem;
  }
  .toast-fermer:hover {
    opacity: 1;
    background: rgba(0, 0, 0, 0.08);
  }
  .sombre .toast-fermer:hover {
    background: rgba(255, 255, 255, 0.08);
  }
</style>
