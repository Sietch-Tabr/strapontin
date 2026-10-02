<template>
  <div>
    <AdminSignalements @voir-fiche="voirFiche" />
    <AdminConcerts />
  </div>
</template>

<script setup>
defineOptions({ name: "AdminATraiter" });

import { useRouter } from "vue-router";
import AdminSignalements from "./AdminSignalements.vue";
import AdminConcerts from "./AdminConcerts.vue";

const router = useRouter();

function voirFiche(entite) {
  router.push({
    hash: "#referentiel",
    query: { entite: entite.type, recherche: entite.nom },
  });
}
</script>
