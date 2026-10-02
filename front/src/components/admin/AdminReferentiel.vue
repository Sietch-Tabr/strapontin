<template>
  <div>
    <div
      class="referentiel-bascule"
      role="group"
      aria-label="Référentiel affiché"
    >
      <button
        v-for="choix in CHOIX"
        :key="choix.valeur"
        type="button"
        class="bouton bouton-petit"
        :class="
          entite === choix.valeur ? 'bouton-primaire' : 'bouton-secondaire'
        "
        :aria-pressed="entite === choix.valeur"
        @click="basculer(choix.valeur)"
      >
        {{ choix.libelle }}
      </button>
    </div>
    <AdminSalles
      v-if="entite === 'salle'"
      :key="'salle:' + recherche"
      :recherche-initiale="recherche"
    />
    <AdminArtistes
      v-else
      :key="'artiste:' + recherche"
      :recherche-initiale="recherche"
    />
  </div>
</template>

<script setup>
defineOptions({ name: "AdminReferentiel" });

import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import AdminSalles from "./AdminSalles.vue";
import AdminArtistes from "./AdminArtistes.vue";

const CHOIX = [
  { valeur: "salle", libelle: "Salles" },
  { valeur: "artiste", libelle: "Artistes" },
];

const route = useRoute();
const router = useRouter();

// L'entité et la recherche vivent dans l'URL : un lien depuis « À traiter » ouvre
// directement la fiche concernée, et le rechargement de la page la conserve.
const entite = computed(() =>
  route.query.entite === "artiste" ? "artiste" : "salle",
);
const recherche = computed(() =>
  typeof route.query.recherche === "string" ? route.query.recherche : "",
);

function basculer(valeur) {
  router.replace({ hash: "#referentiel", query: { entite: valeur } });
}
</script>

<style scoped>
.referentiel-bascule {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
</style>
