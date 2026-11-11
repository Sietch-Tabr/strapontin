<template>
  <dialog
    ref="dialogRef"
    aria-labelledby="titre-fusion-referentiel"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-fusion-referentiel" class="dialog-titre">
        Fusionner deux {{ pluriel }}
      </h2>
    </div>
    <p v-if="itemA && itemB">
      Choisissez {{ article }} à conserver — l'autre sera
      {{ feminin ? "absorbée" : "absorbé" }} (concerts, abonnements et sources
      externes réassignés) puis {{ feminin ? "supprimée" : "supprimé" }}. Action
      irréversible.
    </p>
    <div v-if="itemA && itemB" class="champ">
      <label class="champ-label">
        <input v-model="cible" type="radio" name="cible" :value="itemA.id" />
        Conserver « {{ itemA.nomAffiche || itemA.nom }} »
      </label>
      <label class="champ-label">
        <input v-model="cible" type="radio" name="cible" :value="itemB.id" />
        Conserver « {{ itemB.nomAffiche || itemB.nom }} »
      </label>
    </div>
    <div class="dialog-pied">
      <button
        type="button"
        class="bouton bouton-danger"
        :disabled="enCours || !cible"
        @click="emit('confirmer')"
      >
        Fusionner
      </button>
      <button type="button" class="bouton bouton-texte" @click="emit('fermer')">
        Annuler
      </button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: "DialogueFusionReferentiel" });

import { computed, ref } from "vue";

// Dialogue de fusion de deux salles ou de deux artistes du référentiel : le parent l'ouvre et le
// ferme comme un <dialog> (showModal, close) et lit le choix par v-model.
const props = defineProps({
  entite: { type: String, required: true },
  itemA: { type: Object, default: null },
  itemB: { type: Object, default: null },
  enCours: { type: Boolean, default: false },
});
const cible = defineModel({ type: String, default: "" });
const emit = defineEmits(["confirmer", "fermer"]);

const feminin = computed(() => props.entite === "salle");
const pluriel = computed(() => props.entite + "s");
const article = computed(() => `l'${props.entite}`);

const dialogRef = ref(null);
defineExpose({
  showModal: () => dialogRef.value?.showModal(),
  close: () => dialogRef.value?.close(),
});
</script>
