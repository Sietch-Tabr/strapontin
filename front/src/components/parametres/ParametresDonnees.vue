<template>
  <section id="donnees" class="parametres-section">
    <h2 class="lecteur-ecran">Données</h2>

    <div class="param-bloc">
      <h3 class="param-libelle">Exporter mes préférences</h3>
      <p class="param-aide">
        Télécharge un fichier JSON avec vos artistes, salles et configuration.
      </p>
      <!-- Même gabarit en chargement et chargé : un bloc plus haut décalait les boutons suivants. -->
      <p
        v-if="chargement || stats"
        class="param-aide"
        role="status"
        :aria-busy="chargement"
      >
        <template v-if="chargement">Chargement des compteurs…</template>
        <template v-else
          >{{ stats.nbArtistes }}/500 artistes — {{ stats.nbSalles }}/200
          salles</template
        >
      </p>
      <a href="/api/preferences" class="bouton bouton-secondaire"
        >Télécharger</a
      >
    </div>

    <div class="param-bloc">
      <h3 class="param-libelle">Importer des préférences</h3>
      <p class="param-aide">Importez un fichier JSON exporté par Strapontin.</p>
      <form
        class="form-vertical form-compact"
        @submit.prevent="importerPreferences"
      >
        <div class="form-groupe">
          <label for="import-fichier" class="lecteur-ecran"
            >Fichier de préférences</label
          >
          <div class="form-fichier">
            <input
              id="import-fichier"
              ref="importFichierRef"
              type="file"
              accept=".json,application/json"
              required
              class="form-fichier__input"
              @change="majNomFichier"
            />
            <button
              type="button"
              class="bouton bouton-secondaire"
              @click="importFichierRef?.click()"
            >
              Choisir un fichier
            </button>
            <span class="form-fichier__nom texte-secondaire">{{
              nomFichier
            }}</span>
          </div>
          <p class="form-aide">Fichier .json exporté depuis Strapontin</p>
        </div>
        <fieldset class="form-groupe mode-import">
          <legend>Mode d'import</legend>
          <div
            v-for="mode in MODES_IMPORT"
            :key="mode.valeur"
            class="mode-import-option"
          >
            <button
              type="button"
              class="bouton bouton-bascule"
              :class="{ actif: importMode === mode.valeur }"
              :aria-pressed="importMode === mode.valeur ? 'true' : 'false'"
              @click="importMode = mode.valeur"
            >
              {{ mode.libelle }}
            </button>
            <p
              class="form-aide"
              :class="{ 'form-aide-avertissement': mode.destructif }"
            >
              {{ mode.explication }}
            </p>
          </div>
        </fieldset>
        <div class="form-groupe form-checkboxes">
          <label>
            <input v-model="importerConfig" type="checkbox" />
            Importer la configuration (départements, notifications, thème)
          </label>
        </div>
        <button type="submit" class="bouton bouton-secondaire">Importer</button>
      </form>
    </div>

    <div class="param-bloc param-bloc-danger">
      <h3 class="param-libelle">Supprimer mon compte</h3>
      <p class="param-aide">
        Supprime définitivement votre compte et toutes vos données. Cette action
        est irréversible.
      </p>
      <button
        type="button"
        class="bouton bouton-danger"
        @click="ouvrirDialogSuppression"
      >
        Supprimer mon compte
      </button>
    </div>
  </section>

  <dialog
    id="dialog-supprimer-compte"
    ref="dialogSuppressionRef"
    aria-labelledby="titre-dialog-supprimer-compte"
    aria-modal="true"
  >
    <h2 id="titre-dialog-supprimer-compte" class="dialog-titre">
      Supprimer mon compte ?
    </h2>
    <p>
      Supprime définitivement votre compte et toutes vos données. Cette action
      est irréversible.
    </p>
    <div class="form-groupe">
      <label for="confirmation-suppression">
        Saisissez <strong>supprimer</strong> pour confirmer
      </label>
      <input
        id="confirmation-suppression"
        v-model="confirmationSuppression"
        type="text"
        autocomplete="off"
        autocorrect="off"
        spellcheck="false"
      />
    </div>
    <div class="dialog-pied">
      <button
        type="button"
        class="bouton bouton-danger"
        :disabled="confirmationSuppression !== 'supprimer'"
        @click="supprimerCompte"
      >
        Supprimer mon compte
      </button>
      <button
        type="button"
        class="bouton bouton-texte"
        @click="fermerDialogSuppression"
      >
        Annuler
      </button>
    </div>
  </dialog>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "ParametresDonnees" });

import { ref, shallowRef, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useDialog } from "../../composables/useDialog.js";
import { useToasts } from "../../composables/useToasts.js";

const router = useRouter();
const { afficherToast } = useToasts();
const {
  dialogRef: dialogSuppressionRef,
  ouvrir: ouvrirDialogSuppressionModal,
  fermer: fermerDialogSuppressionModal,
} = useDialog();

const chargement = ref(true);
const stats = shallowRef(null);
const importFichierRef = ref(null);
// La valeur « ecraser » est celle du contrat avec le serveur ; l'interface dit « Remplacer ».
const MODES_IMPORT = [
  {
    valeur: "fusionner",
    libelle: "Fusionner",
    explication:
      "Ajoute les artistes et salles du fichier à ceux que vous suivez déjà.",
    destructif: false,
  },
  {
    valeur: "ecraser",
    libelle: "Remplacer",
    explication:
      "Attention : remplace tous vos artistes et salles suivis par ceux du fichier ; ceux qui n'y figurent pas sont retirés.",
    destructif: true,
  },
];
const importMode = ref("fusionner");
const importerConfig = ref(false);
const confirmationSuppression = ref("");
const nomFichier = ref("Aucun fichier sélectionné");

function majNomFichier() {
  const fichierEl = importFichierRef.value;
  nomFichier.value = fichierEl?.files?.[0]?.name ?? "Aucun fichier sélectionné";
}

async function charger() {
  try {
    const rep = await appelApi("/api/parametres/stats");
    if (rep.ok) stats.value = await lireJson(rep);
  } catch (_) {
    afficherToast("Erreur lors du chargement des statistiques.", "erreur");
  } finally {
    chargement.value = false;
  }
}

async function importerPreferences() {
  const fichierEl = importFichierRef.value;
  if (!fichierEl?.files?.length) return;

  const formData = new FormData();
  formData.append("fichier", fichierEl.files[0]);
  formData.append("mode", importMode.value);
  formData.append("importerConfig", importerConfig.value ? "true" : "false");

  try {
    const rep = await appelApi("/api/preferences/importer", {
      method: "POST",
      body: formData,
    });
    const corps = await lireJson(rep);
    if (rep.ok) {
      const a = corps.artistes ?? 0;
      const s = corps.salles ?? 0;
      const r = corps.rejetes ?? 0;
      const msg =
        a +
        " artiste" +
        (a > 1 ? "s" : "") +
        " et " +
        s +
        " salle" +
        (s > 1 ? "s" : "") +
        " importés" +
        (r > 0 ? " (" + r + " rejeté" + (r > 1 ? "s" : "") + ")" : "") +
        ".";
      afficherToast(msg, "succes");
      const repStats = await appelApi("/api/parametres/stats");
      if (repStats.ok) stats.value = await lireJson(repStats);
      fichierEl.value = "";
      nomFichier.value = "Aucun fichier sélectionné";
    } else {
      afficherToast(corps.erreur || "Erreur lors de l'import.", "erreur");
    }
  } catch (_) {
    afficherToast("Erreur réseau lors de l'import.", "erreur");
  }
}

function ouvrirDialogSuppression() {
  confirmationSuppression.value = "";
  ouvrirDialogSuppressionModal();
}

function fermerDialogSuppression() {
  fermerDialogSuppressionModal();
}

async function supprimerCompte() {
  if (confirmationSuppression.value !== "supprimer") return;
  try {
    const rep = await appelApi("/api/user", { method: "DELETE" });
    if (rep.ok || rep.status === 204) {
      fermerDialogSuppression();
      afficherToast("Compte supprimé.", "succes");
      router.push("/login");
    } else {
      fermerDialogSuppression();
      const corps = await lireJson(rep);
      afficherToast(
        corps.erreur || "Erreur lors de la suppression du compte.",
        "erreur",
      );
    }
  } catch (_) {
    fermerDialogSuppression();
    afficherToast("Erreur réseau lors de la suppression du compte.", "erreur");
  }
}

onMounted(charger);
</script>

<style scoped>
.mode-import {
  border: none;
  padding: 0;
}

.mode-import legend {
  font-size: var(--texte-m);
  font-weight: 500;
  margin-bottom: var(--espace-3);
}

.mode-import-option {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--espace-3) var(--espace-6);
  margin-bottom: var(--espace-4);
}

.mode-import-option .form-aide {
  margin: 0;
}

.form-fichier {
  display: flex;
  align-items: center;
  gap: var(--espace-6);
}

.form-fichier__input {
  display: none;
}

.form-fichier__nom {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
</style>
