<template>
  <div>
    <AdminSignalements @voir-fiche="voirFiche" @comparer="comparer" />
    <AdminConcerts />
  </div>
</template>

<script setup>
defineOptions({ name: "AdminATraiter" });

import { useRouter } from "vue-router";
import AdminSignalements from "./AdminSignalements.vue";
import AdminConcerts from "./AdminConcerts.vue";

const router = useRouter();

// Les deux salles s'affichent côte à côte dans le Référentiel, prêtes à être fusionnées.
function comparer(ids) {
  router.push({
    hash: "#referentiel",
    query: { entite: "salle", ids: ids.join(",") },
  });
}

function voirFiche(entite) {
  router.push({
    hash: "#referentiel",
    query: { entite: entite.type, recherche: entite.nom },
  });
}
</script>
