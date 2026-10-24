<template>
  <dialog
    id="dialogue-confirmation"
    ref="dialogRef"
    aria-labelledby="dialogue-confirmation-titre"
    aria-modal="true"
    @close="conclure('annuler')"
  >
    <h2 id="dialogue-confirmation-titre" class="dialog-titre">
      {{ contenu.titre }}
    </h2>
    <p class="dialog-corps">{{ contenu.message }}</p>
    <div class="dialog-pied">
      <button
        type="button"
        class="bouton bouton-primaire"
        @click="conclure('action')"
      >
        {{ contenu.action }}
      </button>
      <button
        v-if="contenu.secondaire"
        type="button"
        class="bouton bouton-danger"
        @click="conclure('secondaire')"
      >
        {{ contenu.secondaire }}
      </button>
      <button
        type="button"
        class="bouton bouton-texte"
        @click="conclure('annuler')"
      >
        {{ contenu.annuler }}
      </button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: "DialogueConfirmation" });

import { ref, shallowRef } from "vue";

// Une seule instance par page : identifiants fixes.
const dialogRef = ref(null);
const contenu = shallowRef({
  titre: "",
  message: "",
  action: "",
  secondaire: "",
  annuler: "Annuler",
});
let resoudre = null;

/// Ouvre le dialogue ; la promesse vaut "action", "secondaire" ou "annuler" (fermeture comprise).
function choisir({
  titre,
  message,
  action,
  secondaire = "",
  annuler = "Annuler",
}) {
  contenu.value = { titre, message, action, secondaire, annuler };
  dialogRef.value?.showModal();
  return new Promise((resolve) => {
    resoudre = resolve;
  });
}

/// Confirmation simple : la promesse vaut true si l'utilisateur confirme.
async function demander(options) {
  return (await choisir(options)) === "action";
}

function conclure(choix) {
  const r = resoudre;
  resoudre = null;
  if (dialogRef.value?.open) dialogRef.value.close();
  r?.(choix);
}

defineExpose({ demander, choisir });
</script>
