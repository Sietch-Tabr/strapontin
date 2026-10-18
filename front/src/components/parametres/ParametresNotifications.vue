<template>
  <section id="notifications" class="parametres-section">
    <h2 class="lecteur-ecran">Notifications</h2>

    <EtatChargement v-if="chargement" />
    <template v-else>
      <form class="form-vertical" @submit.prevent="sauvegarderNotifications">
        <div class="param-bloc">
          <fieldset class="form-checkboxes">
            <legend class="form-label">Fréquence</legend>
            <p class="param-aide">
              Elle s'applique à l'email comme au push. {{ PLAGE_SILENCIEUSE }}
            </p>
            <label
              v-for="option in FREQUENCES"
              :key="option.valeur"
              class="notif-radio"
            >
              <input v-model="frequence" type="radio" :value="option.valeur" />
              <span>{{ option.libelle }}</span>
            </label>
          </fieldset>
        </div>

        <div class="param-bloc">
          <h3 class="param-libelle">Email</h3>
          <div class="form-checkboxes">
            <label>
              <input
                v-model="canalEmail"
                type="checkbox"
                :disabled="!resendDisponible"
              />
              <span>Recevoir les notifications par email</span>
            </label>
          </div>
          <p v-if="!resendDisponible" class="param-aide">
            Les emails ne sont pas disponibles pour le moment.
          </p>
          <div class="form-checkboxes notif-contenus">
            <label>
              <input
                v-model="notifArtistes"
                type="checkbox"
                :disabled="!canalEmail"
              />
              <span>Concerts des artistes suivis</span>
            </label>
            <label>
              <input
                v-model="notifSalles"
                type="checkbox"
                :disabled="!canalEmail"
              />
              <span>Concerts des salles suivies</span>
            </label>
          </div>
        </div>

        <div v-if="pushSupporte" class="param-bloc">
          <h3 class="param-libelle">Push</h3>
          <p v-if="!vapidDisponible" class="param-aide">
            Les notifications push ne sont pas configurées (clé VAPID manquante
            dans la configuration admin).
          </p>
          <p v-else-if="pushPermission === 'denied'" class="param-aide">
            Les notifications sont bloquées dans votre navigateur. Pour les
            activer, modifiez les permissions du site dans les paramètres de
            votre navigateur.
          </p>
          <template v-else>
            <div class="form-checkboxes">
              <label>
                <input
                  type="checkbox"
                  :checked="canalPush"
                  :disabled="pushEnCours"
                  @change="basculerPush($event)"
                />
                <span>Recevoir les notifications push</span>
              </label>
            </div>
            <p v-if="nbAppareilsAbonnes === 0" class="param-aide">
              Aucun appareil n'est abonné : en cochant, vous activez les
              notifications sur cet appareil.
            </p>
            <div class="form-checkboxes notif-contenus">
              <label>
                <input
                  v-model="notifArtistesPush"
                  type="checkbox"
                  :disabled="!canalPush"
                />
                <span>Concerts des artistes suivis</span>
              </label>
              <label>
                <input
                  v-model="notifSallesPush"
                  type="checkbox"
                  :disabled="!canalPush"
                />
                <span>Concerts des salles suivies</span>
              </label>
              <label>
                <input
                  v-model="notifNouvelleVersion"
                  type="checkbox"
                  :disabled="!canalPush"
                />
                <span>Nouvelle version de l'application</span>
              </label>
            </div>
            <p v-if="messagePush" class="alerte alerte-info" role="status">
              {{ messagePush }}
            </p>
          </template>
        </div>

        <button type="submit" class="bouton bouton-primaire">
          Enregistrer
        </button>
      </form>

      <div v-if="pushSupporte && vapidDisponible" class="param-bloc">
        <h3 class="param-libelle">Appareils avec notifications push</h3>
        <ul class="liste-appareils">
          <li
            v-for="abonnement in abonnements"
            :key="abonnement.endpoint"
            class="ligne-appareil"
          >
            <span class="appareil-info">
              <span>
                {{ abonnement.appareil || "Appareil inconnu" }}
                <span
                  v-if="abonnement.endpoint === endpointNavigateur"
                  class="badge badge-nouveau"
                  >Cet appareil</span
                >
                <span
                  v-if="abonnement.enEchecDepuis"
                  class="badge badge-avertissement"
                  >Envois refusés</span
                >
              </span>
              <span class="param-aide"
                >Abonné depuis le
                {{ formaterDateHeure(abonnement.creeLe) }}</span
              >
              <span v-if="abonnement.enEchecDepuis" class="param-aide">
                Dernier envoi refusé le
                {{ formaterDateHeure(abonnement.enEchecDepuis) }} :
                {{ abonnement.cause }}.
              </span>
            </span>
            <button
              type="button"
              class="bouton bouton-danger bouton-petit"
              @click="revoquerAppareil(abonnement)"
            >
              Révoquer
            </button>
          </li>
          <li v-if="abonnements.length === 0" class="param-aide">
            Aucun appareil abonné aux notifications push.
          </li>
        </ul>
        <div class="param-actions">
          <button
            v-if="
              pushNonEnregistre || (!pushAbonne && pushPermission !== 'denied')
            "
            type="button"
            class="bouton bouton-secondaire"
            :disabled="pushEnCours"
            @click="activerSurCetAppareil"
          >
            Activer sur cet appareil
          </button>
          <button
            v-if="pushAbonne"
            type="button"
            class="bouton bouton-secondaire"
            :disabled="testEnCours"
            @click="testerPush"
          >
            Envoyer une notification de test
          </button>
        </div>
      </div>
    </template>
    <DialogueConfirmation ref="dialogueConfirmation" />
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "ParametresNotifications" });

import { ref, computed, watch, onMounted, onUnmounted } from "vue";
import { useToasts } from "../../composables/useToasts.js";
import DialogueConfirmation from "../DialogueConfirmation.vue";
import EtatChargement from "../EtatChargement.vue";
import { FREQUENCES, PLAGE_SILENCIEUSE } from "../../utils/notifications.js";
import {
  abonnerAppareil,
  lireVapid,
  pushPrisEnCharge,
} from "../../composables/useAbonnementPush.js";

const { afficherToast } = useToasts();

const dialogueConfirmation = ref(null);
const chargement = ref(true);
const resendDisponible = ref(false);
const frequence = ref("jamais");
const notifArtistes = ref(false);
const notifSalles = ref(false);
const notifArtistesPush = ref(false);
const notifSallesPush = ref(false);
const notifNouvelleVersion = ref(false);
// Dernières préférences enregistrées : la révocation du dernier appareil retire le push sans
// enregistrer les autres modifications en cours dans le formulaire.
let enregistrees = null;

// Un canal est actif dès qu'un de ses contenus est coché : « Jamais » ne sert aucun canal.
const canalEmail = computed({
  get: () => notifArtistes.value || notifSalles.value,
  set: (actif) => {
    notifArtistes.value = actif;
    notifSalles.value = actif;
    if (actif && frequence.value === "jamais") frequence.value = "hebdomadaire";
  },
});
const canalPush = computed(
  () => notifArtistesPush.value || notifSallesPush.value,
);

watch(frequence, (valeur) => {
  if (valeur === "jamais") {
    notifArtistes.value = false;
    notifSalles.value = false;
    notifArtistesPush.value = false;
    notifSallesPush.value = false;
    notifNouvelleVersion.value = false;
  }
});

const pushSupporte = ref(false);
const vapidDisponible = ref(false);
const pushPermission = ref("default");
const endpointNavigateur = ref(null);
// Appareils abonnés du compte, tous navigateurs confondus.
const abonnements = ref([]);
const nbAppareilsAbonnes = computed(() => abonnements.value.length);
const pushAbonne = computed(
  () =>
    endpointNavigateur.value !== null &&
    abonnements.value.some((a) => a.endpoint === endpointNavigateur.value),
);
const pushNonEnregistre = computed(
  () => endpointNavigateur.value !== null && !pushAbonne.value,
);
const messagePush = ref("");
const pushEnCours = ref(false);
const testEnCours = ref(false);
let swRegistration = null;
let cleVapidPublique = null;
const controleur = new AbortController();

function formaterDateHeure(iso) {
  return new Date(iso).toLocaleString("fr-FR", {
    dateStyle: "short",
    timeStyle: "short",
  });
}

function instantane() {
  return {
    frequenceNotifications: frequence.value,
    notifArtistes: notifArtistes.value,
    notifSalles: notifSalles.value,
    notifArtistesPush: notifArtistesPush.value,
    notifSallesPush: notifSallesPush.value,
    notifNouvelleVersion: notifNouvelleVersion.value,
  };
}

async function charger() {
  try {
    const [repConfig, vapid] = await Promise.all([
      appelApi("/api/user/config", { signal: controleur.signal }),
      lireVapid(controleur.signal),
      chargerAbonnements(),
    ]);
    if (repConfig.ok) {
      const config = await lireJson(repConfig);
      resendDisponible.value = config.resendDisponible ?? false;
      frequence.value = config.frequenceNotifications ?? "jamais";
      notifArtistes.value = config.notifArtistes ?? false;
      notifSalles.value = config.notifSalles ?? false;
      notifArtistesPush.value = config.notifArtistesPush ?? false;
      notifSallesPush.value = config.notifSallesPush ?? false;
      notifNouvelleVersion.value = config.notifNouvelleVersion ?? false;
      enregistrees = instantane();
    }
    vapidDisponible.value = vapid.disponible;
    cleVapidPublique = vapid.clePublique;
    await initialiserPush();
  } catch (e) {
    if (e.name !== "AbortError") {
      afficherToast("Erreur lors du chargement des notifications.", "erreur");
    }
  } finally {
    chargement.value = false;
  }
}

async function chargerAbonnements() {
  try {
    const rep = await appelApi("/api/push/abonnements", {
      signal: controleur.signal,
    });
    if (rep.ok) abonnements.value = await lireJson(rep);
  } catch (e) {
    if (e.name !== "AbortError") {
      afficherToast("Impossible de charger les appareils push.", "erreur");
    }
  }
}

async function initialiserPush() {
  if (!pushPrisEnCharge()) {
    pushSupporte.value = false;
    return;
  }
  pushSupporte.value = true;
  pushPermission.value = Notification.permission;

  if (vapidDisponible.value && pushPermission.value !== "denied") {
    try {
      swRegistration = await navigator.serviceWorker.ready;
      const abonnement = await swRegistration.pushManager.getSubscription();
      endpointNavigateur.value = abonnement?.endpoint ?? null;
    } catch (_) {
      endpointNavigateur.value = null;
    }
  }
}

async function enregistrer(corps) {
  const rep = await appelApi("/api/user/config/notifications", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(corps),
    signal: controleur.signal,
  });
  if (rep.ok) enregistrees = { ...corps };
  return rep;
}

async function sauvegarderNotifications() {
  try {
    const rep = await enregistrer(instantane());
    if (rep.ok) {
      afficherToast("Préférences de notification enregistrées.", "succes");
    } else {
      const corps = await lireJson(rep);
      afficherToast(corps.erreur || "Erreur.", "erreur");
    }
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Erreur réseau lors de l'enregistrement.", "erreur");
  }
}

function retirerPushDuFormulaire() {
  notifArtistesPush.value = false;
  notifSallesPush.value = false;
  notifNouvelleVersion.value = false;
}

// Cocher le canal sans appareil abonné propose l'abonnement de cet appareil, et la case ne
// reste cochée qu'une fois l'abonnement réussi ; décocher propose de révoquer cet appareil.
async function basculerPush(evenement) {
  const coche = evenement.target.checked;
  messagePush.value = "";
  if (coche) {
    const abouti = await activerPush();
    if (!abouti) evenement.target.checked = false;
    return;
  }
  retirerPushDuFormulaire();
  if (pushAbonne.value) {
    const confirme = await dialogueConfirmation.value?.demander({
      titre: "Révoquer aussi cet appareil ?",
      message:
        "Les notifications push sont décochées. Révoquer cet appareil le désabonne aussi ; sinon il reste abonné sans rien recevoir.",
      action: "Révoquer cet appareil",
    });
    if (confirme) await desabonnerCetAppareil();
  } else if (nbAppareilsAbonnes.value > 0) {
    messagePush.value =
      "D'autres appareils restent abonnés : révoquez-les dans la liste ci-dessous pour les désabonner.";
  }
}

async function activerPush() {
  if (frequence.value === "jamais") frequence.value = "hebdomadaire";
  if (nbAppareilsAbonnes.value === 0) {
    const abonne = await abonnerPush();
    if (!abonne) return false;
  }
  notifArtistesPush.value = true;
  notifSallesPush.value = true;
  return true;
}

async function activerSurCetAppareil() {
  const abonne = await abonnerPush();
  if (abonne && !canalPush.value) {
    afficherToast(
      "Cet appareil est abonné. Cochez « Recevoir les notifications push » pour choisir les contenus.",
      "succes",
    );
  }
}

async function abonnerPush() {
  if (!swRegistration || !cleVapidPublique) {
    messagePush.value =
      "Les notifications push ne sont pas disponibles sur cet appareil.";
    return false;
  }
  pushEnCours.value = true;
  try {
    const resultat = await abonnerAppareil(cleVapidPublique, controleur.signal);
    if (resultat.statut === "refuse") {
      pushPermission.value = resultat.permission;
      return false;
    }
    if (resultat.statut === "ok") {
      endpointNavigateur.value = resultat.endpoint;
      await chargerAbonnements();
      afficherToast("Notifications push activées.", "succes");
      return true;
    }
    afficherToast(
      resultat.message ||
        "Impossible d'activer les notifications push sur cet appareil.",
      "erreur",
    );
    return false;
  } catch (_) {
    afficherToast(
      "Impossible d'activer les notifications push sur cet appareil.",
      "erreur",
    );
    return false;
  } finally {
    pushEnCours.value = false;
  }
}

// transmettre l'endpoint de l'appareil courant plutôt que de laisser le serveur
// sélectionner arbitrairement le plus récent — sinon un utilisateur abonné sur plusieurs
// appareils cliquant "Test" depuis l'un d'eux pouvait recevoir la notification sur un autre.
async function testerPush() {
  testEnCours.value = true;
  try {
    let endpoint = null;
    if (swRegistration) {
      const abonnement = await swRegistration.pushManager.getSubscription();
      endpoint = abonnement?.endpoint ?? null;
    }
    const rep = await appelApi("/api/push/tester", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ endpoint }),
      signal: controleur.signal,
    });
    if (rep.ok) {
      afficherToast("Notification de test envoyée.", "succes");
    } else {
      const corps = await lireJson(rep);
      afficherToast(corps.erreur || "L'envoi n'a pas abouti.", "erreur");
    }
    await chargerAbonnements();
    await retirerPushSiAucunAppareil();
  } catch (_) {
    afficherToast("Erreur réseau.", "erreur");
  } finally {
    testEnCours.value = false;
  }
}

async function desabonnerCetAppareil() {
  pushEnCours.value = true;
  try {
    const abonnement = await swRegistration?.pushManager.getSubscription();
    if (abonnement) {
      await appelApi("/api/push", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ endpoint: abonnement.endpoint }),
        signal: controleur.signal,
      });
      await abonnement.unsubscribe();
    }
    endpointNavigateur.value = null;
    document.cookie = "push_opt_in=; path=/; max-age=0; SameSite=Strict";
    await chargerAbonnements();
    await retirerPushSiAucunAppareil();
    afficherToast("Cet appareil est désabonné.", "succes");
  } catch (_) {
    afficherToast(
      "Impossible de désactiver les notifications push sur cet appareil.",
      "erreur",
    );
  } finally {
    pushEnCours.value = false;
  }
}

async function revoquerAppareil(abonnement) {
  try {
    const rep = await appelApi("/api/push", {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ endpoint: abonnement.endpoint }),
      signal: controleur.signal,
    });
    if (!rep.ok) {
      afficherToast("Impossible de révoquer cet appareil.", "erreur");
      return;
    }
    if (abonnement.endpoint === endpointNavigateur.value) {
      const local = await swRegistration?.pushManager.getSubscription();
      await local?.unsubscribe();
      endpointNavigateur.value = null;
    }
    await chargerAbonnements();
    afficherToast("Appareil révoqué.", "succes");
    await retirerPushSiAucunAppareil();
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Erreur réseau lors de la révocation.", "erreur");
  }
}

// Sans appareil, les cases push n'ont plus d'effet : elles sont décochées et enregistrées.
async function retirerPushSiAucunAppareil() {
  if (nbAppareilsAbonnes.value > 0 || !enregistrees) return;
  const avait =
    enregistrees.notifArtistesPush ||
    enregistrees.notifSallesPush ||
    enregistrees.notifNouvelleVersion;
  retirerPushDuFormulaire();
  if (!avait) return;
  const rep = await enregistrer({
    ...enregistrees,
    notifArtistesPush: false,
    notifSallesPush: false,
    notifNouvelleVersion: false,
  });
  if (rep.ok) {
    messagePush.value =
      "Plus aucun appareil n'est abonné : les notifications push ont été désactivées.";
  }
}

onMounted(charger);
onUnmounted(() => controleur.abort());
</script>

<style scoped>
.liste-appareils {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--espace-4);
}

.ligne-appareil {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--espace-8);
  padding: var(--espace-4) 0;
  border-bottom: 1px solid var(--border);
}

.ligne-appareil:last-child {
  border-bottom: none;
}

.appareil-info {
  display: flex;
  flex-direction: column;
  gap: var(--espace-1);
  font-size: var(--texte-m);
}

.notif-contenus {
  margin-top: var(--espace-6);
}

.notif-radio {
  display: flex;
  align-items: center;
  gap: var(--espace-4);
  font-weight: normal;
  cursor: pointer;
}

.notif-radio input[type="radio"] {
  appearance: none;
  width: 1.125rem;
  height: 1.125rem;
  min-width: 1.125rem;
  border: 1.5px solid var(--border);
  border-radius: 50%;
  background: var(--bg);
  cursor: pointer;
  transition:
    background 0.15s,
    border-color 0.15s;
  position: relative;
}

.notif-radio input[type="radio"]:checked {
  border-color: var(--primary);
  background: var(--primary);
  box-shadow: inset 0 0 0 3px var(--bg);
}

.notif-radio input[type="radio"]:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.notif-radio:has(input:disabled) {
  cursor: not-allowed;
}
</style>
