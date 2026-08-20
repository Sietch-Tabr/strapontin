<template>
  <div v-if="visible" class="pwa-install-banniere" role="status">
    <svg width="20" height="20" aria-hidden="true">
      <use href="/icons/lucide-sprite.svg#icon-smartphone" />
    </svg>
    <span class="pwa-install-message">Installez Strapontin pour un accès rapide, même hors ligne.</span>
    <div class="pwa-install-actions">
      <button type="button" class="btn btn-primary btn-sm" @click="installer">Installer</button>
      <button type="button" class="btn btn-texte btn-sm" @click="plusTard">Plus tard</button>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'InstallPwaBanner' })

import { usePwaInstall } from '../../composables/usePwaInstall.js'

const { visible, installer, plusTard } = usePwaInstall()
</script>
