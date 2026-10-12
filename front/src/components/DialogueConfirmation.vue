<template>
  <dialog
    id="dialogue-confirmation"
    ref="dialogRef"
    aria-labelledby="dialogue-confirmation-titre"
    aria-modal="true"
    @close="conclure(false)"
  >
    <h2 id="dialogue-confirmation-titre" class="dialog-titre">
      {{ contenu.titre }}
    </h2>
    <p class="dialog-corps">{{ contenu.message }}</p>
    <div class="dialog-pied">
      <button
        type="button"
        class="bouton bouton-primaire"
        @click="conclure(true)"
      >
        {{ contenu.action }}
      </button>
      <button
        type="button"
        class="bouton bouton-texte"
        @click="conclure(false)"
      >
        Annuler
      </button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: "DialogueConfirmation" });

import { ref, shallowRef } from "vue";

// Une seule instance par page : identifiants fixes.
const dialogRef = ref(null);
const contenu = shallowRef({ titre: "", message: "", action: "" });
let resoudre = null;

/// Ouvre le dialogue ; la promesse vaut true si l'utilisateur confirme.
function demander({ titre, message, action }) {
  contenu.value = { titre, message, action };
  dialogRef.value?.showModal();
  return new Promise((resolve) => {
    resoudre = resolve;
  });
}

function conclure(confirme) {
  const r = resoudre;
  resoudre = null;
  if (dialogRef.value?.open) dialogRef.value.close();
  r?.(confirme);
}

defineExpose({ demander });
</script>
