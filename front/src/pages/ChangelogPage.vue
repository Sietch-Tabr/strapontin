<template>
  <div>
    <div class="page-entete">
      <h1>Nouveautés</h1>
    </div>

    <div class="contenu-largeur-etroite changelog">
      <p class="page-sous-titre">
        Historique des améliorations et corrections apportées à Strapontin.
      </p>

      <template
        v-for="(version, index) in versionsChangelog"
        :key="version.entete"
      >
        <!-- La plus récente version reste toujours dépliée ; toutes les précédentes sont
             repliées par défaut — une seule version dépliée à la fois. -->
        <section v-if="index === 0">
          <h2 class="changelog-version">{{ version.entete }}</h2>
          <ChangelogEntrees :categories="version.categories" />
        </section>
        <details v-else>
          <summary>
            <h2 class="changelog-version">{{ version.entete }}</h2>
          </summary>
          <div class="details-corps">
            <ChangelogEntrees :categories="version.categories" />
          </div>
        </details>
      </template>
    </div>
  </div>
</template>

<script setup>
import { appelApi } from "../utils/api.js";
defineOptions({ name: "ChangelogPage" });

import { onMounted } from "vue";
import ChangelogEntrees from "../components/ChangelogEntrees.vue";
import { versionsChangelog } from "./changelogVersions.js";

onMounted(async () => {
  try {
    await appelApi("/api/changelog/marquer-vue", { method: "POST" });
  } catch (e) {
    console.error("Échec marquage changelog vu", e);
  }
});
</script>

<style scoped>
.changelog-version {
  font-size: var(--texte-base);
  font-weight: 600;
  margin-bottom: var(--espace-8);
  display: flex;
  align-items: center;
  gap: var(--espace-4);
}

.changelog {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.changelog > section {
  padding-bottom: var(--espace-20);
}

.changelog > details {
  padding-bottom: var(--espace-20);
}

.changelog > section + details,
.changelog > details + details {
  padding-top: var(--espace-20);
  border-top: 1px solid var(--border);
}

.changelog > details > summary {
  cursor: pointer;
  list-style: none;
  user-select: none;
}

.changelog > details > summary::-webkit-details-marker {
  display: none;
}

.changelog > details > summary .changelog-version {
  margin-bottom: 0;
}

.changelog > details > summary .changelog-version::before {
  content: "▶";
  font-size: var(--texte-4xs);
  transition: transform 0.2s;
  flex-shrink: 0;
}

.changelog > details[open] > summary .changelog-version::before {
  transform: rotate(90deg);
}

.changelog > details > .details-corps {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.25s ease;
}

.changelog > details[open] > .details-corps {
  grid-template-rows: 1fr;
}

.changelog > details > .details-corps > .changelog-entrees {
  overflow: hidden;
  min-height: 0;
  padding-top: var(--espace-8);
}
</style>
