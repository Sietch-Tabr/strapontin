<template>
  <div class="source-vue-entete">
    <input
      v-model="nom"
      type="search"
      class="source-recherche"
      :placeholder="placeholderRecherche"
      :aria-label="libelleRecherche"
      @input="emit('recharger')"
    />
    <slot name="apres-recherche" />
    <select
      v-model="statut"
      aria-label="Filtrer par statut"
      @change="emit('recharger')"
    >
      <option value="">Tous statuts</option>
      <slot name="statuts" />
    </select>
    <label class="source-filtre-checkbox">
      <input
        v-model="wikidataManquant"
        type="checkbox"
        @change="emit('recharger')"
      />
      Sans Wikidata
    </label>
    <slot name="apres-wikidata" />
    <button
      type="button"
      class="bouton bouton-danger bouton-petit"
      :disabled="nombreSelection !== 2"
      @click="emit('fusionner')"
    >
      Fusionner ({{ nombreSelection }}/2)
    </button>
  </div>
</template>

<script setup>
defineOptions({ name: "BarreFiltresReferentiel" });

// Barre de filtres commune aux référentiels des salles et des artistes : recherche par nom,
// statut, « Sans Wikidata » et bouton de fusion ; les filtres propres à une entité passent par
// les emplacements.
defineProps({
  libelleRecherche: { type: String, required: true },
  placeholderRecherche: { type: String, required: true },
  nombreSelection: { type: Number, default: 0 },
});
const nom = defineModel("nom", { type: String, default: "" });
const statut = defineModel("statut", { type: String, default: "" });
const wikidataManquant = defineModel("wikidataManquant", {
  type: Boolean,
  default: false,
});
const emit = defineEmits(["recharger", "fusionner"]);
</script>
