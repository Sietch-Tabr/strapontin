<template>
  <dialog
    id="dialog-confirmer-suppr-suivi"
    ref="dialogRef"
    aria-labelledby="titre-confirmer-suppr-suivi"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-confirmer-suppr-suivi" class="dialog-titre">
        {{ titre }}
      </h2>
    </div>
    <p>
      Arrêter de suivre « <strong>{{ nom }}</strong> » ?
    </p>
    <div class="dialog-pied">
      <button
        type="button"
        class="bouton bouton-danger"
        @click="emit('confirmer')"
      >
        Supprimer
      </button>
      <button type="button" class="bouton bouton-texte" @click="fermer()">
        Annuler
      </button>
    </div>
  </dialog>
</template>

<script setup>
import { useDialog } from "../composables/useDialog.js";
defineOptions({ name: "DialogueSuppressionSuivi" });

// Confirmation de l'arrêt du suivi d'un artiste ou d'une salle : une seule instance par page,
// le parent l'ouvre et le ferme par `ouvrir` et `fermer`.
defineProps({
  titre: { type: String, required: true },
  nom: { type: String, default: "" },
});
const emit = defineEmits(["confirmer"]);
const { dialogRef, ouvrir, fermer } = useDialog();
defineExpose({ ouvrir, fermer });
</script>
