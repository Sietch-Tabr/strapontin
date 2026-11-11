<template>
  <form
    :id="idFormulaire"
    class="filtres-form"
    :class="{ visible }"
    @submit.prevent="emit('appliquer')"
  >
    <div class="filtres-ligne">
      <div class="filtres-recherche" role="search">
        <input
          v-model="q"
          type="search"
          :placeholder="libelleRecherche + '…'"
          :aria-label="libelleRecherche"
          autocomplete="off"
        />
        <button type="submit" class="bouton bouton-secondaire">
          <svg class="icone" aria-hidden="true">
            <use href="/icons/lucide-sprite.svg#icon-search" />
          </svg>
          <span>Rechercher</span>
        </button>
      </div>

      <FiltreListeConcerts
        :id="'filtre-mois' + suffixeIds"
        v-model="mois"
        libelle="Filtrer par mois"
        libelle-tous="Tous les mois"
        :options="donnees?.moisDisponibles ?? []"
        @change="emit('appliquer')"
      />

      <FiltreListeConcerts
        :id="'filtre-genre' + suffixeIds"
        v-model="genre"
        libelle="Filtrer par genre"
        libelle-tous="Tous les genres"
        :options="donnees?.genresDisponibles ?? []"
        @change="emit('appliquer')"
      />

      <FiltreListeConcerts
        :id="'filtre-classification' + suffixeIds"
        v-model="classification"
        libelle="Filtrer par classification"
        libelle-tous="Toutes classifications"
        :options="donnees?.classificationsDisponibles ?? []"
        @change="emit('appliquer')"
      />

      <label :for="'filtre-tri' + suffixeIds" class="lecteur-ecran"
        >Trier les résultats</label
      >
      <select
        :id="'filtre-tri' + suffixeIds"
        v-model="tri"
        @change="emit('appliquer')"
      >
        <option value="date">Par date</option>
        <option value="nouveaute">Par nouveauté</option>
      </select>

      <RouterLink :to="lienReinitialiser" class="bouton bouton-texte"
        >Réinitialiser</RouterLink
      >
    </div>
  </form>
</template>

<script setup>
import FiltreListeConcerts from "./FiltreListeConcerts.vue";
defineOptions({ name: "FormulaireFiltresConcerts" });

// Formulaire de filtres commun à Découverte et à Spectacles : recherche, mois, genre,
// classification et tri ; les valeurs proposées viennent de la réponse du serveur.
defineProps({
  idFormulaire: { type: String, required: true },
  suffixeIds: { type: String, default: "" },
  libelleRecherche: { type: String, required: true },
  lienReinitialiser: { type: String, required: true },
  visible: { type: Boolean, default: false },
  donnees: { type: Object, default: null },
});
const q = defineModel("q", { type: String, default: "" });
const mois = defineModel("mois", { type: String, default: "" });
const genre = defineModel("genre", { type: String, default: "" });
const classification = defineModel("classification", {
  type: String,
  default: "",
});
const tri = defineModel("tri", { type: String, default: "date" });
const emit = defineEmits(["appliquer"]);
</script>
