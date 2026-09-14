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
      <button
        type="button"
        class="toast-fermer"
        aria-label="Fermer"
        @click="fermerToast(toast.id)"
      >
        ×
      </button>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: "ToastContainer" });

import { useToasts } from "../../composables/useToasts.js";

const { toasts, fermerToast } = useToasts();
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
  background: rgb(0 0 0 / 8%);
}

.sombre .toast-fermer:hover {
  background: rgb(255 255 255 / 8%);
}
</style>
