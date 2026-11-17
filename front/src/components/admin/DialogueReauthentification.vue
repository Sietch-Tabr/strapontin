<template>
  <dialog
    ref="dialogRef"
    aria-labelledby="titre-reauthentification"
    aria-modal="true"
    @close="effacer"
  >
    <div class="dialog-entete">
      <h2 id="titre-reauthentification" class="dialog-titre">
        Installer la version {{ version }}
      </h2>
    </div>
    <form @submit.prevent="envoyer">
      <p class="param-aide">
        L'installation exécute du code sur le serveur : confirmez votre mot de
        passe et saisissez le code de votre application d'authentification.
      </p>
      <div class="form-champ">
        <label class="form-label" for="reauth-mot-de-passe"
          >Mot de passe actuel</label
        >
        <input
          id="reauth-mot-de-passe"
          v-model="motDePasse"
          type="password"
          class="form-control"
          maxlength="200"
          autocomplete="current-password"
          required
        />
      </div>
      <div class="form-champ">
        <label class="form-label" for="reauth-code-totp"
          >Code de l'application d'authentification</label
        >
        <input
          id="reauth-code-totp"
          v-model="codeTotp"
          type="text"
          class="form-control"
          inputmode="numeric"
          maxlength="20"
          autocomplete="one-time-code"
          required
        />
      </div>
      <p v-if="erreur" class="alerte alerte-erreur" role="alert">
        {{ erreur }}
      </p>
      <div class="dialog-pied">
        <button
          type="submit"
          class="bouton bouton-primaire"
          :disabled="enCours"
        >
          Installer
        </button>
        <button type="button" class="bouton bouton-texte" @click="fermer">
          Annuler
        </button>
      </div>
    </form>
  </dialog>
</template>

<script setup>
defineOptions({ name: "DialogueReauthentification" });

import { ref } from "vue";
import { appelApi, lireJson } from "../../utils/api.js";
import { corpsDemande } from "../../utils/miseAJour.js";
import { useDialog } from "../../composables/useDialog.js";

const props = defineProps({
  /** Version proposée, celle que la demande installera. */
  version: { type: String, required: true },
});
const emit = defineEmits(["demande"]);

const { dialogRef, ouvrir, fermer } = useDialog();
const motDePasse = ref("");
const codeTotp = ref("");
const erreur = ref("");
const enCours = ref(false);

// Les identifiants ne restent jamais en mémoire du composant une fois le dialogue fermé.
function effacer() {
  motDePasse.value = "";
  codeTotp.value = "";
  erreur.value = "";
}

async function envoyer() {
  if (enCours.value) return;
  enCours.value = true;
  erreur.value = "";
  try {
    const rep = await appelApi("/api/admin/mise-a-jour", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: corpsDemande(props.version, motDePasse.value, codeTotp.value),
    });
    if (rep.ok) {
      fermer();
      emit("demande");
    } else {
      const corps = await lireJson(rep);
      erreur.value = corps.erreur || "La demande a été refusée.";
    }
  } catch {
    erreur.value = "Le serveur ne répond pas : réessayez dans un instant.";
  } finally {
    enCours.value = false;
  }
}

defineExpose({ ouvrir, fermer });
</script>
