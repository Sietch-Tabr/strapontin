<template>
  <div
    v-if="incident && !vue"
    class="alerte alerte-erreur banniere-incident"
    role="alert"
  >
    <p class="banniere-incident__titre">{{ incident.titre }}</p>
    <p class="banniere-incident__message">{{ incident.message }}</p>
    <button
      type="button"
      class="bouton bouton-secondaire bouton-petit"
      @click="marquerVue"
    >
      J'ai compris
    </button>
  </div>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "BanniereIncident" });

import { ref, onMounted, onUnmounted } from "vue";

// Communication d'incident de sécurité : lue sans session (pages d'authentification
// comprises), masquée seulement une fois lue sur cet appareil.
const incident = ref(null);
const vue = ref(false);
const controleur = new AbortController();

function cleVue(id) {
  return `incident-vu-${id}`;
}

function lireVue(id) {
  try {
    return localStorage.getItem(cleVue(id)) === "1";
  } catch {
    return false;
  }
}

function marquerVue() {
  vue.value = true;
  try {
    localStorage.setItem(cleVue(incident.value.id), "1");
  } catch {
    // Stockage indisponible (navigation privée) : masquée pour cette page seulement.
  }
}

onMounted(async () => {
  try {
    const rep = await appelApi("/api/incident", { signal: controleur.signal });
    if (rep.status !== 200) return;
    incident.value = await lireJson(rep);
    vue.value = lireVue(incident.value.id);
  } catch (e) {
    if (e.name !== "AbortError") console.error("Échec lecture incident", e);
  }
});

onUnmounted(() => controleur.abort());
</script>

<style scoped>
.banniere-incident {
  margin: 1rem;
}

.banniere-incident__titre {
  font-weight: 700;
}

.banniere-incident__message {
  white-space: pre-line;
}
</style>
