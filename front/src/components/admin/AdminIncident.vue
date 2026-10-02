<template>
  <div class="param-bloc">
    <h3 class="param-libelle">Communication d'incident de sécurité</h3>
    <p class="param-aide">
      Affichée à tous, y compris sur la page de connexion, jusqu'à sa clôture.
      Prévenir avant toute régénération des clés VAPID : elle invalide tous les
      abonnements push.
    </p>

    <EtatChargement v-if="chargement" />
    <template v-else>
      <div v-if="etat.active" class="alerte alerte-avertissement">
        <p>
          <strong>{{ etat.active.titre }}</strong> — publiée le
          {{ formaterInstant(etat.active.publieeLe) }} ({{
            etat.active.nbEmails
          }}
          email(s), {{ etat.active.nbPush }} push)
        </p>
        <button
          type="button"
          class="bouton bouton-secondaire bouton-petit"
          :disabled="enCours"
          @click="terminer"
        >
          Clore la communication
        </button>
      </div>

      <form
        class="form-vertical"
        @submit.prevent="dialogConfirmationRef.showModal()"
      >
        <div class="form-groupe">
          <label for="incident-titre">Titre</label>
          <input
            id="incident-titre"
            v-model="titre"
            type="text"
            required
            maxlength="120"
          />
        </div>
        <div class="form-groupe">
          <label for="incident-message">Message</label>
          <textarea
            id="incident-message"
            v-model="message"
            required
            maxlength="2000"
            rows="5"
          ></textarea>
        </div>
        <div class="form-groupe form-checkboxes">
          <label>
            <input v-model="email" type="checkbox" />
            Prévenir chaque compte par email ({{ etat.nbComptes }} comptes,
            {{ etat.envoisEmailRestants }} envois restants)
          </label>
          <label>
            <input v-model="push" type="checkbox" />
            Notifier tous les abonnements push
          </label>
          <label>
            <input v-model="changementMdpForce" type="checkbox" />
            Exiger un nouveau mot de passe de tous les comptes
          </label>
        </div>
        <p
          v-if="email && etat.nbComptes > etat.envoisEmailRestants"
          class="alerte alerte-erreur"
        >
          Quota d'emails insuffisant pour prévenir tous les comptes aujourd'hui.
        </p>
        <button type="submit" class="bouton bouton-danger" :disabled="enCours">
          Publier la communication
        </button>
      </form>
    </template>
  </div>

  <dialog
    ref="dialogConfirmationRef"
    aria-labelledby="titre-confirmation-incident"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-confirmation-incident" class="dialog-titre">
        Publier la communication ?
      </h2>
    </div>
    <p>Les envois cochés ne pourront pas être annulés.</p>
    <div class="dialog-pied">
      <button
        type="button"
        class="bouton bouton-danger"
        :disabled="enCours"
        @click="publier"
      >
        Publier
      </button>
      <button
        type="button"
        class="bouton bouton-texte"
        @click="dialogConfirmationRef.close()"
      >
        Annuler
      </button>
    </div>
  </dialog>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "AdminIncident" });

import { ref, reactive, onMounted, onUnmounted } from "vue";
import EtatChargement from "../EtatChargement.vue";
import { useToasts } from "../../composables/useToasts.js";
import { formaterInstant } from "../../composables/useDates.js";

const { afficherToast } = useToasts();
const chargement = ref(true);
const enCours = ref(false);
const etat = reactive({ active: null, nbComptes: 0, envoisEmailRestants: 0 });
const titre = ref("");
const message = ref("");
const email = ref(false);
const push = ref(false);
const changementMdpForce = ref(false);
const dialogConfirmationRef = ref(null);
const controleur = new AbortController();

async function charger() {
  try {
    const rep = await appelApi("/api/admin/incident", {
      signal: controleur.signal,
    });
    if (rep.ok) Object.assign(etat, await lireJson(rep));
  } catch (e) {
    if (e.name !== "AbortError")
      afficherToast("Erreur lors du chargement de l'incident.", "erreur");
  } finally {
    chargement.value = false;
  }
}

async function publier() {
  dialogConfirmationRef.value?.close();
  enCours.value = true;
  try {
    const rep = await appelApi("/api/admin/incident", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        titre: titre.value,
        message: message.value,
        email: email.value,
        push: push.value,
        changementMdpForce: changementMdpForce.value,
      }),
      signal: controleur.signal,
    });
    const corps = await lireJson(rep);
    if (rep.ok) {
      afficherToast(
        `Communication publiée (${corps.nbEmails} email(s), ${corps.nbPush} push).`,
        "succes",
      );
      titre.value = "";
      message.value = "";
      await charger();
    } else {
      afficherToast(corps.erreur || "Erreur lors de la publication.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours.value = false;
  }
}

async function terminer() {
  enCours.value = true;
  try {
    const rep = await appelApi("/api/admin/incident/terminer", {
      method: "POST",
      signal: controleur.signal,
    });
    if (rep.ok) {
      afficherToast("Communication close.", "succes");
      await charger();
    } else {
      afficherToast("Erreur lors de la clôture.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours.value = false;
  }
}

onMounted(charger);
onUnmounted(() => controleur.abort());
</script>
