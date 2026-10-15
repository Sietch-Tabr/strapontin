<template>
  <dialog
    :id="'dialog-' + cle"
    ref="dialogRef"
    class="modale-etapes"
    aria-labelledby="titre-etape"
    aria-modal="true"
    @close="onFermetureNative"
  >
    <div class="dialog-entete">
      <h2 id="titre-etape" class="dialog-titre">{{ etapeCourante.titre }}</h2>
      <button
        type="button"
        class="bouton-icone"
        aria-label="Fermer"
        @click="fermer"
      >
        <svg width="18" height="18" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-x" />
        </svg>
      </button>
    </div>

    <div class="dialog-corps etapes-corps">
      <svg class="etapes-icone" width="32" height="32" aria-hidden="true">
        <use :href="'/icons/lucide-sprite.svg#' + etapeCourante.icone" />
      </svg>
      <p>{{ etapeCourante.texte }}</p>
      <slot v-if="etapeCourante.cle" :name="etapeCourante.cle" />
    </div>

    <div
      class="etapes-progression"
      role="group"
      :aria-label="`Étape ${index + 1} sur ${etapes.length}`"
    >
      <span
        v-for="(_, i) in etapes"
        :key="i"
        class="etapes-point"
        :class="{ 'etapes-point--actif': i === index }"
      ></span>
    </div>

    <div class="dialog-pied">
      <button
        v-if="index > 0"
        type="button"
        class="bouton bouton-texte"
        @click="precedent"
      >
        Précédent
      </button>
      <button
        v-if="index < etapes.length - 1"
        type="button"
        class="bouton bouton-primaire"
        @click="suivant"
      >
        Suivant
      </button>
      <button
        v-else
        type="button"
        class="bouton bouton-primaire"
        @click="terminer"
      >
        Terminer
      </button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: "ModaleEtapes" });

import { computed, ref } from "vue";
import { useDialog } from "../composables/useDialog.js";

const props = defineProps({
  cle: { type: String, required: true },
  etapes: { type: Array, required: true },
});
const emit = defineEmits(["termine", "ferme"]);

const { dialogRef, ouvrir: ouvrirDialog, fermer: fermerDialog } = useDialog();
const index = ref(0);
const etapeCourante = computed(() => props.etapes[index.value]);

// Le "Terminer" et le bouton de fermeture déclenchent tous deux la fermeture native du
// <dialog> (close()), qui émet elle-même l'événement 'close' — ce drapeau évite de compter
// une completion (terminer) comme un abandon (ferme) quand les deux se produisent ensemble.
let viaTerminer = false;

function suivant() {
  if (index.value < props.etapes.length - 1) index.value++;
}
function precedent() {
  if (index.value > 0) index.value--;
}
function terminer() {
  viaTerminer = true;
  emit("termine");
  fermerDialog();
}
function fermer() {
  fermerDialog();
}
function onFermetureNative() {
  if (!viaTerminer) emit("ferme");
  viaTerminer = false;
}

function ouvrir() {
  index.value = 0;
  ouvrirDialog();
}

defineExpose({ ouvrir, fermer });
</script>

<style scoped>
.etapes-corps {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: var(--espace-8);
  padding: var(--espace-8) 0;
}

.etapes-icone {
  color: var(--primary);
}

.etapes-progression {
  display: flex;
  justify-content: center;
  gap: var(--espace-4);
  margin-top: var(--espace-4);
}

.etapes-point {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 50%;
  background: var(--border);
  transition: background 0.15s;
}

.etapes-point--actif {
  background: var(--primary);
}
</style>
