<template>
  <div>
    <div class="page-header">
      <h1>Nouveautés</h1>
    </div>

    <div class="max-w-xl changelog">
      <p class="page-subtitle">Historique des améliorations et corrections apportées à Strapontin.</p>

      <template v-for="(version, index) in versionsChangelog" :key="version.entete">
        <!-- La plus récente version reste toujours dépliée ; toutes les précédentes sont
             repliées par défaut — une seule version dépliée à la fois. -->
        <section v-if="index === 0">
          <h2 class="changelog-version">{{ version.entete }}</h2>
          <ChangelogEntrees :categories="version.categories" />
        </section>
        <details v-else>
          <summary><h2 class="changelog-version">{{ version.entete }}</h2></summary>
          <div class="details-corps">
            <ChangelogEntrees :categories="version.categories" />
          </div>
        </details>
      </template>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ChangelogPage' })

import { onMounted } from 'vue'
import ChangelogEntrees from '../components/ChangelogEntrees.vue'
import { versionsChangelog } from './changelogVersions.js'

onMounted(async () => {
  try {
    await fetch('/api/changelog/marquer-vue', { method: 'POST' })
  } catch (e) {
    console.error('Échec marquage changelog vu', e)
  }
})
</script>
