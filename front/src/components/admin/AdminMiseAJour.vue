<template>
  <section id="mise-a-jour" class="admin-section">
    <EtatChargement v-if="chargement" />
    <template v-else-if="donnees">
      <div class="param-bloc">
        <h3 class="param-libelle">Version de l'application</h3>
        <p class="param-aide">
          Version en service : <strong>{{ donnees.versionInstallee }}</strong>
        </p>
        <p v-if="donnees.versionProposee" class="param-aide">
          <span class="badge badge-info">Nouvelle version</span>
          La version {{ donnees.versionProposee }} est disponible.
        </p>
        <p v-else class="param-aide">
          <span class="badge badge-succes">À jour</span>
        </p>
        <p
          v-if="motif && donnees.versionProposee"
          class="alerte alerte-avertissement"
          role="status"
        >
          {{ motif }}
        </p>
        <div v-if="donnees.versionProposee" class="form-actions">
          <button
            type="button"
            class="bouton bouton-primaire"
            :disabled="motif !== null"
            @click="dialogueRef?.ouvrir()"
          >
            Installer la version {{ donnees.versionProposee }}
          </button>
        </div>
      </div>

      <div v-if="etapesVisibles" class="param-bloc">
        <h3 class="param-libelle">Avancement</h3>
        <p v-if="donnees.etat.versionCible" class="param-aide">
          Version visée : {{ donnees.etat.versionCible }}
        </p>
        <ol class="etapes-maj" aria-label="Étapes de la mise à jour">
          <li v-for="etape in ETAPES" :key="etape.cle">
            <span>{{ etape.libelle }}</span>
            <span :class="presentation(etape.cle).classe">{{
              presentation(etape.cle).libelle
            }}</span>
          </li>
        </ol>
        <p v-if="redemarrage" class="param-aide" role="status">
          L'application redémarre : l'affichage reprendra dans un instant.
        </p>
        <p
          v-if="donnees.etat.resultat === 'ECHEC'"
          class="alerte alerte-erreur"
          role="alert"
        >
          La mise à jour a échoué : {{ donnees.etat.message }} L'application
          reste sur la version {{ donnees.versionInstallee }}.
        </p>
        <div v-if="installee" class="alerte alerte-succes" role="status">
          <p>La version {{ donnees.versionInstallee }} est installée.</p>
          <button
            type="button"
            class="bouton bouton-secondaire"
            @click="recharger"
          >
            Recharger l'application
          </button>
        </div>
      </div>

      <DialogueReauthentification
        v-if="donnees.versionProposee"
        ref="dialogueRef"
        :version="donnees.versionProposee"
        @demande="apresDemande"
      />
    </template>
    <p v-else class="alerte alerte-erreur" role="alert">
      L'état de la mise à jour n'a pas pu être lu.
    </p>
  </section>
</template>

<script setup>
defineOptions({ name: "AdminMiseAJour" });

import { computed, onMounted, onUnmounted, ref } from "vue";
import { appelApi, lireJson } from "../../utils/api.js";
import {
  demandeTerminee,
  empreinteEtat,
  estInstallee,
  ETAPES,
  motifImpossible,
  presentationStatut,
  statutEtape,
} from "../../utils/miseAJour.js";
import { useToasts } from "../../composables/useToasts.js";
import EtatChargement from "../EtatChargement.vue";
import DialogueReauthentification from "./DialogueReauthentification.vue";

// Relecture de l'état pendant une mise à jour ; l'arrêt sur délai évite un suivi sans fin si le
// module s'arrête sans rien écrire.
const INTERVALLE_MS = 5000;
const RELECTURES_MAX = 360;

const { afficherToast } = useToasts();
const controleur = new AbortController();
const dialogueRef = ref(null);
const chargement = ref(true);
const donnees = ref(null);
const redemarrage = ref(false);
let minuterie = null;
let relectures = 0;
let empreinteAvant = null;

const motif = computed(() =>
  donnees.value ? motifImpossible(donnees.value) : null,
);
const etapesVisibles = computed(
  () => !donnees.value?.demandeEnAttente && donnees.value?.etat?.etape,
);
const installee = computed(
  () => donnees.value !== null && estInstallee(donnees.value),
);

function presentation(cle) {
  return presentationStatut(statutEtape(cle, donnees.value.etat));
}

async function lire() {
  const rep = await appelApi("/api/admin/mise-a-jour", {
    signal: controleur.signal,
  });
  if (!rep.ok) throw new Error(`HTTP ${rep.status}`);
  donnees.value = await lireJson(rep);
}

async function charger() {
  try {
    await lire();
    if (donnees.value.enCours) suivre();
  } catch (e) {
    if (e.name !== "AbortError") donnees.value = null;
  } finally {
    chargement.value = false;
  }
}

// Le web redémarre pendant l'installation : une lecture qui échoue n'arrête pas le suivi.
async function relire() {
  relectures += 1;
  try {
    await lire();
    redemarrage.value = false;
  } catch (e) {
    if (e.name === "AbortError") return;
    redemarrage.value = true;
  }
  const termine =
    donnees.value !== null &&
    !redemarrage.value &&
    (empreinteAvant === null
      ? !donnees.value.enCours
      : demandeTerminee(donnees.value, empreinteAvant));
  if (termine || relectures >= RELECTURES_MAX) arreter();
}

function suivre() {
  arreter();
  relectures = 0;
  minuterie = setInterval(relire, INTERVALLE_MS);
}

function arreter() {
  if (minuterie) {
    clearInterval(minuterie);
    minuterie = null;
  }
}

async function apresDemande() {
  afficherToast("Mise à jour demandée.", "succes");
  empreinteAvant = empreinteEtat(donnees.value?.etat ?? null);
  await relire();
  suivre();
}

function recharger() {
  globalThis.location.reload();
}

onMounted(charger);
onUnmounted(() => {
  arreter();
  controleur.abort();
});
</script>

<style scoped>
.etapes-maj {
  list-style: none;
  margin: 0 0 var(--espace-8);
  padding: 0;
}

.etapes-maj li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--espace-8);
  padding: var(--espace-4) 0;
  border-bottom: var(--trait-fin) solid var(--border);
}
</style>
