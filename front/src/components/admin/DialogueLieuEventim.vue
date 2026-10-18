<template>
  <dialog
    ref="dialogRef"
    aria-labelledby="titre-lieu-eventim"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-lieu-eventim" class="dialog-titre">
        Lieu Eventim de « {{ salle?.nom }} »
      </h2>
    </div>
    <form @submit.prevent="enregistrer">
      <p class="param-aide">
        Eventim interroge cette salle par le nom exact de son lieu, tel qu'il
        l'affiche. Un nom inexact ne renvoie aucun concert : testez avant
        d'enregistrer. Laissez vide pour retirer le lieu.
      </p>
      <div class="form-champ">
        <label class="form-label" for="champ-lieu-eventim"
          >Nom exact du lieu</label
        >
        <input
          id="champ-lieu-eventim"
          v-model="libelle"
          type="text"
          class="form-control"
          maxlength="200"
          autocomplete="off"
          @input="resultatTest = null"
        />
      </div>
      <p
        v-if="resultatTest"
        class="resultat-test"
        :class="
          resultatTest.injoignable ? 'resultat-test-ko' : 'resultat-test-ok'
        "
        aria-live="polite"
      >
        {{ formaterResultatTestLieu(resultatTest) }}
      </p>
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
          class="bouton bouton-secondaire"
          :disabled="enCours || !libelleCourant"
          @click="tester"
        >
          Tester
        </button>
        <button
          v-if="lieuEnregistre"
          type="button"
          class="bouton bouton-secondaire"
          :disabled="enCours"
          title="Lance la recherche des concerts de cette salle sans attendre le prochain cycle"
          @click="lancerFetch"
        >
          Lancer un fetch
        </button>
        <button type="button" class="bouton bouton-secondaire" @click="fermer">
          Fermer
        </button>
      </div>
    </form>
  </dialog>
</template>

<script setup>
defineOptions({ name: "DialogueLieuEventim" });

import { computed, ref } from "vue";
import { appelApi, lireJson } from "../../utils/api.js";
import { formaterResultatTestLieu } from "../../utils/lieuEventim.js";
import { useDialog } from "../../composables/useDialog.js";
import { useToasts } from "../../composables/useToasts.js";

const emit = defineEmits(["enregistre"]);

const { afficherToast } = useToasts();
const { dialogRef, ouvrir: ouvrirDialog, fermer } = useDialog();

const salle = ref(null);
const libelle = ref("");
const lieuEnregistre = ref("");
const resultatTest = ref(null);
const enCours = ref(false);
const libelleCourant = computed(() => libelle.value.trim());

/// @param cible salle `{id, nom, lieuEventim}` dont on gère le lieu
function ouvrir(cible) {
  salle.value = cible;
  libelle.value = cible.lieuEventim ?? "";
  lieuEnregistre.value = cible.lieuEventim ?? "";
  resultatTest.value = null;
  ouvrirDialog();
}

// Même enchaînement qu'à la saisie d'un lieu : un conflit (409) et l'échec du test sont
// signalés par le serveur, rien n'est enregistré silencieusement.
async function enregistrer() {
  if (enCours.value) return;
  enCours.value = true;
  try {
    const rep = await appelApi(
      `/api/admin/salles/${salle.value.id}/lieu-eventim`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ libelle: libelleCourant.value }),
      },
    );
    if (rep.ok) {
      lieuEnregistre.value = libelleCourant.value;
      afficherToast(
        libelleCourant.value
          ? "Lieu Eventim enregistré."
          : "Lieu Eventim retiré.",
        "succes",
      );
      emit("enregistre");
    } else {
      const data = await lireJson(rep);
      afficherToast(
        data.erreur || "Erreur lors de l'enregistrement du lieu.",
        "erreur",
      );
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours.value = false;
  }
}

async function tester() {
  if (!libelleCourant.value || enCours.value) return;
  enCours.value = true;
  resultatTest.value = null;
  try {
    const rep = await appelApi(
      `/api/admin/salles/${salle.value.id}/tester-lieu-eventim`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ libelle: libelleCourant.value }),
      },
    );
    if (rep.ok) {
      const data = await lireJson(rep);
      resultatTest.value = { nbConcerts: data.nbConcerts ?? 0 };
    } else {
      resultatTest.value = { injoignable: true };
    }
  } catch {
    resultatTest.value = { injoignable: true };
  } finally {
    enCours.value = false;
  }
}

async function lancerFetch() {
  if (enCours.value) return;
  enCours.value = true;
  try {
    const rep = await appelApi(`/api/admin/salles/${salle.value.id}/fetch`, {
      method: "POST",
    });
    if (rep.ok) {
      afficherToast("Recherche des concerts de la salle lancée.", "succes");
    } else {
      const data = await lireJson(rep);
      afficherToast(data.erreur || "Erreur lors du déclenchement.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours.value = false;
  }
}

defineExpose({ ouvrir });
</script>

<style scoped>
.resultat-test {
  font-size: var(--texte-xs);
  font-weight: 600;
}

.resultat-test-ok {
  color: var(--succes-text);
}

.resultat-test-ko {
  color: var(--danger-text);
}
</style>
