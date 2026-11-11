<template>
  <section id="inviter" class="parametres-section">
    <h2 class="lecteur-ecran">Inviter</h2>

    <EtatChargement v-if="chargement" />
    <template v-else>
      <div class="param-bloc">
        <h3 class="param-libelle">Inviter une personne</h3>
        <p class="param-aide">
          Strapontin est ouvert sur invitation. La personne reçoit un lien
          valable 7 jours pour créer son compte ; aucun compte n'existe tant
          qu'elle ne l'a pas activé.
        </p>
        <p id="aide-restantes" class="param-aide" aria-live="polite">
          {{ phraseRestantes }}
        </p>
        <form class="form-vertical form-compact" @submit.prevent="inviter">
          <div class="form-groupe">
            <label for="invitation-email">Adresse email</label>
            <input
              id="invitation-email"
              v-model="email"
              type="email"
              maxlength="254"
              autocomplete="off"
              required
              :disabled="!saisiePossible"
              aria-describedby="aide-restantes"
            />
          </div>
          <button
            type="submit"
            class="bouton bouton-primaire"
            :disabled="!saisiePossible || envoiEnCours"
          >
            Envoyer l'invitation
          </button>
        </form>
      </div>

      <div class="param-bloc">
        <h3 class="param-libelle">Vos invitations</h3>
        <ul class="liste-appareils">
          <li
            v-for="invitation in invitations"
            :key="invitation.id"
            class="ligne-appareil"
          >
            <span class="appareil-info">
              {{ invitation.email }}
              <span
                class="badge"
                :class="
                  invitation.statut === 'activee'
                    ? 'badge-succes'
                    : 'badge-source'
                "
                >{{ libelleStatut(invitation.statut) }}</span
              >
              <span class="param-aide"
                >Envoyée le {{ formaterDate(invitation.creeLe) }}</span
              >
            </span>
            <span v-if="peutAgir(invitation.statut)" class="ligne-actions">
              <button
                type="button"
                class="bouton bouton-secondaire bouton-petit"
                :aria-label="'Renvoyer l\'invitation à ' + invitation.email"
                @click="renvoyer(invitation)"
              >
                Renvoyer
              </button>
              <button
                type="button"
                class="bouton bouton-danger bouton-petit"
                :aria-label="'Annuler l\'invitation à ' + invitation.email"
                @click="annuler(invitation)"
              >
                Annuler l'invitation
              </button>
            </span>
          </li>
          <li v-if="invitations.length === 0" class="param-aide">
            Aucune invitation envoyée.
          </li>
        </ul>
      </div>
    </template>
  </section>
</template>

<script setup>
defineOptions({ name: "ParametresInvitations" });

import { ref, computed, onMounted, onUnmounted } from "vue";
import { appelApi, lireJson } from "../../utils/api.js";
import {
  libelleRestantes,
  libelleStatut,
  peutAgir,
  peutInviter,
} from "../../utils/invitations.js";
import { useToasts } from "../../composables/useToasts.js";
import EtatChargement from "../EtatChargement.vue";

const { afficherToast } = useToasts();
const controleur = new AbortController();

const chargement = ref(true);
const email = ref("");
const envoiEnCours = ref(false);
const plafond = ref(0);
const restantes = ref(0);
const invitations = ref([]);

const phraseRestantes = computed(() =>
  libelleRestantes(restantes.value, plafond.value),
);
const saisiePossible = computed(() =>
  peutInviter(restantes.value, plafond.value),
);

function formaterDate(iso) {
  return new Date(iso).toLocaleDateString("fr-FR", { dateStyle: "short" });
}

async function charger() {
  try {
    const rep = await appelApi("/api/invitations", {
      signal: controleur.signal,
    });
    if (rep.ok) {
      const etat = await lireJson(rep);
      plafond.value = etat.plafond;
      restantes.value = etat.restantes;
      invitations.value = etat.invitations;
    } else {
      afficherToast("Impossible de charger vos invitations.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError")
      afficherToast("Impossible de charger vos invitations.", "erreur");
  } finally {
    chargement.value = false;
  }
}

async function appeler(url, options, messageSucces, messageReseau) {
  try {
    const rep = await appelApi(url, { ...options, signal: controleur.signal });
    const corps = await lireJson(rep);
    if (rep.ok) {
      afficherToast(messageSucces, "succes");
    } else {
      afficherToast(corps?.erreur || "Une erreur est survenue.", "erreur");
    }
    await charger();
    return rep.ok;
  } catch (e) {
    if (e.name !== "AbortError") afficherToast(messageReseau, "erreur");
    return false;
  }
}

async function inviter() {
  if (!saisiePossible.value || envoiEnCours.value) return;
  envoiEnCours.value = true;
  const reussi = await appeler(
    "/api/invitations",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: email.value }),
    },
    "Invitation envoyée.",
    "Erreur réseau lors de l'envoi de l'invitation.",
  );
  if (reussi) email.value = "";
  envoiEnCours.value = false;
}

function renvoyer(invitation) {
  return appeler(
    `/api/invitations/${invitation.id}/renvoyer`,
    { method: "POST" },
    "Invitation renvoyée.",
    "Erreur réseau lors du renvoi.",
  );
}

function annuler(invitation) {
  return appeler(
    `/api/invitations/${invitation.id}`,
    { method: "DELETE" },
    "Invitation annulée.",
    "Erreur réseau lors de l'annulation.",
  );
}

onMounted(charger);
onUnmounted(() => controleur.abort());
</script>
