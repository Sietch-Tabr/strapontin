<template>
  <dialog
    ref="dialogRef"
    aria-labelledby="titre-edition-referentiel"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-edition-referentiel" class="dialog-titre">
        Modifier le nom affiché
      </h2>
    </div>
    <form @submit.prevent="emit('sauvegarder')">
      <div class="champ">
        <label :for="idChamp" class="champ-label">Nom affiché</label>
        <input
          :id="idChamp"
          v-model="nomAffiche"
          type="text"
          maxlength="200"
          autocomplete="off"
          :placeholder="nomOriginal"
        />
        <p class="param-aide">
          Laisser vide pour revenir à l'enrichissement automatique.
        </p>
      </div>
      <div class="dialog-pied">
        <button
          type="submit"
          class="bouton bouton-primaire"
          :disabled="enCours"
        >
          Enregistrer
        </button>
        <button
          type="button"
          class="bouton bouton-texte"
          @click="emit('fermer')"
        >
          Annuler
        </button>
      </div>
    </form>
  </dialog>
</template>

<script setup>
defineOptions({ name: "DialogueEditionReferentiel" });

import { ref } from "vue";

// Dialogue de modification du nom affiché d'une salle ou d'un artiste du référentiel. Le parent
// l'ouvre et le ferme comme un <dialog> (showModal, close). Un seul référentiel est affiché à la
// fois : l'identifiant du titre est unique.
defineProps({
  idChamp: { type: String, required: true },
  nomOriginal: { type: String, default: "" },
  enCours: { type: Boolean, default: false },
});
const nomAffiche = defineModel("nomAffiche", { type: String, default: "" });
const emit = defineEmits(["sauvegarder", "fermer"]);

const dialogRef = ref(null);
defineExpose({
  showModal: () => dialogRef.value?.showModal(),
  close: () => dialogRef.value?.close(),
});
</script>
