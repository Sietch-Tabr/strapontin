<template>
  <section
    v-if="visible"
    class="checklist-premiers-pas"
    aria-labelledby="titre-checklist"
  >
    <p id="titre-checklist" class="aide-libelle">Premiers pas</p>
    <ul class="checklist-liste">
      <li
        v-for="item in items"
        :key="item.cle"
        class="checklist-item"
        :class="{ 'checklist-item--fait': item.fait }"
      >
        <span
          class="checklist-marque"
          :class="{ 'checklist-marque--fait': item.fait }"
        >
          <svg v-if="item.fait" width="12" height="12" aria-hidden="true">
            <use href="/icons/lucide-sprite.svg#icon-check" />
          </svg>
        </span>
        <span>{{ item.texte }}</span>
      </li>
    </ul>
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../utils/api.js";
import { canalJoignable } from "../utils/notifications.js";
import { lireEndpointsServeur } from "../composables/useAbonnementPush.js";
defineOptions({ name: "ChecklistPremiersPas" });

import { computed, onMounted, ref } from "vue";

const departementsConfigures = ref(false);
const artisteOuSalleSuivi = ref(false);
const notificationActive = ref(false);
const charge = ref(false);

const items = computed(() => [
  {
    cle: "departements",
    texte: "Configurer vos départements",
    fait: departementsConfigures.value,
  },
  {
    cle: "suivi",
    texte: "Suivre un artiste ou une salle",
    fait: artisteOuSalleSuivi.value,
  },
  {
    cle: "notification",
    texte: "Activer un canal de notification",
    fait: notificationActive.value,
  },
]);

const visible = computed(
  () => charge.value && items.value.some((i) => !i.fait),
);

onMounted(async () => {
  try {
    const [repConfig, repStats, endpoints] = await Promise.all([
      appelApi("/api/user/config"),
      appelApi("/api/parametres/stats"),
      lireEndpointsServeur(),
    ]);
    if (repConfig.ok) {
      const config = await lireJson(repConfig);
      departementsConfigures.value = (config.departements?.length ?? 0) > 0;
      notificationActive.value = canalJoignable(config, endpoints?.length ?? 0);
    }
    if (repStats.ok) {
      const stats = await lireJson(repStats);
      artisteOuSalleSuivi.value =
        (stats.nbArtistes ?? 0) + (stats.nbSalles ?? 0) > 0;
    }
  } catch (e) {
    console.error("Échec chargement checklist premiers pas", e);
  } finally {
    charge.value = true;
  }
});
</script>

<style scoped>
.checklist-premiers-pas {
  margin-bottom: var(--espace-12);
}

.checklist-liste {
  display: flex;
  flex-direction: column;
  gap: var(--espace-4);
}

.checklist-item {
  display: flex;
  align-items: center;
  gap: var(--espace-5);
  font-size: var(--texte-m);
}

.checklist-item--fait {
  color: var(--fg-muted);
  text-decoration: line-through;
}

.checklist-marque {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 1.125rem;
  height: 1.125rem;
  border-radius: 50%;
  border: 1.5px solid var(--border);
  color: var(--primary-fg);
}

.checklist-marque--fait {
  background: var(--primary);
  border-color: var(--primary);
}
</style>
