<template>
  <div
    v-if="etatConnexion.donneesEnCacheLe !== null"
    class="alerte alerte-avertissement"
    role="status"
  >
    Connexion absente ou trop lente : ces données sont celles de votre dernière
    visite ({{ anciennete }}). Elles seront remplacées dès que la connexion
    revient.
  </div>
  <div
    v-else-if="etatConnexion.injoignable"
    class="alerte alerte-avertissement"
    role="status"
  >
    Serveur injoignable : les données affichées peuvent ne pas être à jour.
    Nouvelle tentative automatique.
  </div>
  <div v-else-if="etatConnexion.lente" class="alerte alerte-info" role="status">
    Connexion lente : les données peuvent tarder à s'afficher.
  </div>
</template>

<script setup>
defineOptions({ name: "BandeauHorsLigne" });

import { computed, onMounted, onUnmounted, ref } from "vue";
import { etatConnexion } from "../../composables/useEtatConnexion.js";
import { formaterAnciennete } from "../../utils/anciennete.js";

// L'ancienneté s'actualise d'elle-même tant que le bandeau reste affiché.
const maintenant = ref(Date.now());
let minuterie = null;
onMounted(() => {
  minuterie = setInterval(() => {
    maintenant.value = Date.now();
  }, 30_000);
});
onUnmounted(() => clearInterval(minuterie));

const anciennete = computed(() =>
  formaterAnciennete(etatConnexion.donneesEnCacheLe, maintenant.value),
);
</script>
