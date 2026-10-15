<template>
  <div v-if="visible" class="pwa-install-banniere" role="status">
    <svg width="20" height="20" aria-hidden="true">
      <use href="/icons/lucide-sprite.svg#icon-smartphone" />
    </svg>
    <span class="pwa-install-message"
      >Installez Strapontin pour un accès rapide, même hors ligne.</span
    >
    <div class="pwa-install-actions">
      <button
        type="button"
        class="bouton bouton-primaire bouton-petit"
        @click="installer"
      >
        Installer
      </button>
      <button
        type="button"
        class="bouton bouton-texte bouton-petit"
        @click="plusTard"
      >
        Plus tard
      </button>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: "InstallPwaBanner" });

import { usePwaInstall } from "../../composables/usePwaInstall.js";

const { visible, installer, plusTard } = usePwaInstall();
</script>

<style scoped>
.pwa-install-banniere {
  position: fixed;
  left: 0;
  right: 0;
  bottom: calc(var(--nav-h) + env(safe-area-inset-bottom));
  z-index: 40;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1.25rem;
  background: var(--bg-card);
  border-top: 1px solid var(--border);
  box-shadow: var(--ombre-bandeau-bas);
}

@media (width >= 1025px) {
  .pwa-install-banniere {
    left: var(--sidebar-w);
    bottom: 0;
  }
}

.pwa-install-message {
  flex: 1;
  font-size: 0.875rem;
}

.pwa-install-actions {
  display: flex;
  gap: 0.5rem;
  flex-shrink: 0;
}
</style>
