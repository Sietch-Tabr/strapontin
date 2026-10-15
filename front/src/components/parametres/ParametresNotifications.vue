<template>
  <section id="notifications" class="parametres-section">
    <h2 class="lecteur-ecran">Notifications</h2>

    <EtatChargement v-if="chargement" />
    <template v-else>
      <div class="param-bloc">
        <h3 class="param-libelle">Notifications email</h3>
        <form class="form-vertical" @submit.prevent="sauvegarderNotifications">
          <fieldset class="form-checkboxes" :disabled="!resendDisponible">
            <legend class="form-label">Fréquence des emails</legend>
            <label class="notif-radio">
              <input
                v-model="frequenceNotifications"
                type="radio"
                value="jamais"
              />
              <span>Jamais</span>
            </label>
            <label class="notif-radio">
              <input
                v-model="frequenceNotifications"
                type="radio"
                value="quotidien"
              />
              <span>Récapitulatif quotidien (8h)</span>
            </label>
            <label class="notif-radio">
              <input
                v-model="frequenceNotifications"
                type="radio"
                value="hebdomadaire"
              />
              <span>Récapitulatif hebdomadaire (dimanche 10h)</span>
            </label>
            <label class="notif-radio">
              <input
                v-model="frequenceNotifications"
                type="radio"
                value="temps_reel"
              />
              <span>À chaque nouveau concert</span>
            </label>
            <p v-if="!resendDisponible" class="form-aide">
              Les emails ne sont pas disponibles (clé Resend non configurée).
            </p>
          </fieldset>

          <fieldset class="notif-grille">
            <legend class="form-label">Concerts à notifier</legend>
            <div class="notif-grille__entete" aria-hidden="true">
              <span></span>
              <span>Email</span>
              <span>Push</span>
            </div>
            <div class="notif-grille__ligne">
              <span class="notif-grille__libelle">Artistes suivis</span>
              <label class="notif-grille__case">
                <span class="lecteur-ecran">Email — artistes suivis</span>
                <input
                  v-model="notifArtistes"
                  type="checkbox"
                  :disabled="!resendDisponible"
                />
              </label>
              <label class="notif-grille__case">
                <span class="lecteur-ecran">Push — artistes suivis</span>
                <input v-model="notifArtistesPush" type="checkbox" />
              </label>
            </div>
            <div class="notif-grille__ligne">
              <span class="notif-grille__libelle">Salles suivies</span>
              <label class="notif-grille__case">
                <span class="lecteur-ecran">Email — salles suivies</span>
                <input
                  v-model="notifSalles"
                  type="checkbox"
                  :disabled="!resendDisponible"
                />
              </label>
              <label class="notif-grille__case">
                <span class="lecteur-ecran">Push — salles suivies</span>
                <input v-model="notifSallesPush" type="checkbox" />
              </label>
            </div>
            <div class="notif-grille__ligne">
              <span class="notif-grille__libelle"
                >Nouvelle version de l'application</span
              >
              <span></span>
              <label class="notif-grille__case">
                <span class="lecteur-ecran"
                  >Push — nouvelle version de l'application</span
                >
                <input v-model="notifNouvelleVersion" type="checkbox" />
              </label>
            </div>
          </fieldset>

          <p v-if="pushSansAppareil" class="alerte alerte-avertissement">
            Aucun appareil n'est abonné aux notifications push : celles de vos
            artistes et salles ne vous parviennent pas. Activez-les ci-dessous
            sur au moins un appareil.
          </p>

          <button type="submit" class="bouton bouton-primaire">
            Enregistrer
          </button>
        </form>
      </div>

      <div v-if="pushSupporte" class="param-bloc param-bloc-push">
        <h3 class="param-libelle">Notifications push</h3>
        <div v-if="!vapidDisponible">
          <p class="param-aide">
            Les notifications push ne sont pas configurées (clé VAPID manquante
            dans la configuration admin).
          </p>
        </div>
        <div v-else-if="pushPermission === 'denied'">
          <p class="param-aide">
            Les notifications sont bloquées dans votre navigateur. Pour les
            activer, modifiez les permissions du site dans les paramètres de
            votre navigateur.
          </p>
        </div>
        <div v-else-if="pushNonEnregistre">
          <p class="param-aide">
            Cet appareil n'est plus enregistré pour les notifications push : il
            ne les reçoit plus. Réactivez-les pour l'enregistrer à nouveau.
          </p>
          <button
            type="button"
            class="bouton bouton-secondaire"
            :disabled="pushEnCours || frequenceNotifications === 'jamais'"
            @click="abonnerPush"
          >
            Réactiver les notifications push
          </button>
        </div>
        <div v-else-if="pushAbonne">
          <p class="param-aide">
            Les notifications push sont activées sur cet appareil.
          </p>
          <div class="param-actions">
            <button
              type="button"
              class="bouton bouton-secondaire bouton-petit"
              :disabled="testEnCours"
              @click="testerPush"
            >
              Envoyer une notification de test
            </button>
            <button
              type="button"
              class="bouton bouton-secondaire"
              :disabled="pushEnCours"
              @click="desabonnerPush"
            >
              Désactiver les notifications push
            </button>
          </div>
        </div>
        <div v-else>
          <p class="param-aide">
            Recevez une notification sur cet appareil dès qu'un nouveau concert
            correspond à vos préférences.
          </p>
          <p v-if="frequenceNotifications === 'jamais'" class="param-aide">
            Activez d'abord une fréquence de notification ci-dessus — sur
            "Jamais", aucune notification push ne serait envoyée.
          </p>
          <button
            type="button"
            class="bouton bouton-secondaire"
            :disabled="pushEnCours || frequenceNotifications === 'jamais'"
            @click="abonnerPush"
          >
            Activer les notifications push
          </button>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "ParametresNotifications" });

import { ref, computed, watch, onMounted, onUnmounted } from "vue";
import { useToasts } from "../../composables/useToasts.js";
import EtatChargement from "../EtatChargement.vue";
import {
  abonnerAppareil,
  lireEndpointsServeur,
  lireVapid,
  pushPrisEnCharge,
} from "../../composables/useAbonnementPush.js";

const { afficherToast } = useToasts();

const chargement = ref(true);
const resendDisponible = ref(false);
const frequenceNotifications = ref("jamais");
const notifArtistes = ref(false);
const notifSalles = ref(false);
const notifArtistesPush = ref(false);
const notifSallesPush = ref(false);

// Le serveur coupe le push avec « jamais » : le formulaire affiche le même état.
watch(frequenceNotifications, (frequence) => {
  if (frequence === "jamais") {
    notifArtistesPush.value = false;
    notifSallesPush.value = false;
  }
});
const notifNouvelleVersion = ref(false);

const pushSupporte = ref(false);
const vapidDisponible = ref(false);
const pushPermission = ref("default");
// Abonnement du navigateur, et sa présence côté serveur : un appareil révoqué
// par le serveur reste abonné dans le navigateur sans plus rien recevoir.
const endpointNavigateur = ref(null);
// Abonnements push du compte, tous appareils confondus (null : inconnu).
const endpointsServeur = ref(null);
const nbAppareilsAbonnes = computed(
  () => endpointsServeur.value?.length ?? null,
);
const pushAbonne = computed(
  () =>
    endpointNavigateur.value !== null &&
    (endpointsServeur.value === null ||
      endpointsServeur.value.includes(endpointNavigateur.value)),
);
const pushNonEnregistre = computed(
  () => endpointNavigateur.value !== null && !pushAbonne.value,
);
const pushSansAppareil = computed(
  () =>
    nbAppareilsAbonnes.value === 0 &&
    vapidDisponible.value &&
    frequenceNotifications.value !== "jamais" &&
    (notifArtistesPush.value || notifSallesPush.value),
);
const pushEnCours = ref(false);
const testEnCours = ref(false);
let swRegistration = null;
let cleVapidPublique = null;
const controleur = new AbortController();

async function charger() {
  try {
    const [repConfig, vapid] = await Promise.all([
      appelApi("/api/user/config", { signal: controleur.signal }),
      lireVapid(controleur.signal),
      compterAppareilsAbonnes(),
    ]);
    if (repConfig.ok) {
      const config = await lireJson(repConfig);
      resendDisponible.value = config.resendDisponible ?? false;
      frequenceNotifications.value = config.frequenceNotifications ?? "jamais";
      notifArtistes.value = config.notifArtistes ?? false;
      notifSalles.value = config.notifSalles ?? false;
      notifArtistesPush.value = config.notifArtistesPush ?? false;
      notifSallesPush.value = config.notifSallesPush ?? false;
      notifNouvelleVersion.value = config.notifNouvelleVersion ?? false;
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

async function compterAppareilsAbonnes() {
  endpointsServeur.value = await lireEndpointsServeur(controleur.signal);
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

async function sauvegarderNotifications() {
  try {
    const rep = await appelApi("/api/user/config/notifications", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        frequenceNotifications: frequenceNotifications.value,
        notifArtistes: notifArtistes.value,
        notifSalles: notifSalles.value,
        notifArtistesPush: notifArtistesPush.value,
        notifSallesPush: notifSallesPush.value,
        notifNouvelleVersion: notifNouvelleVersion.value,
      }),
      signal: controleur.signal,
    });
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

async function abonnerPush() {
  if (!swRegistration || !cleVapidPublique) return;
  pushEnCours.value = true;
  try {
    const resultat = await abonnerAppareil(cleVapidPublique, controleur.signal);
    if (resultat.statut === "refuse") {
      pushPermission.value = resultat.permission;
    } else if (resultat.statut === "ok") {
      endpointNavigateur.value = resultat.endpoint;
      await compterAppareilsAbonnes();
      afficherToast("Notifications push activées.", "succes");
    } else {
      afficherToast(
        resultat.message ||
          "Impossible d'activer les notifications push sur cet appareil.",
        "erreur",
      );
    }
  } catch (_) {
    afficherToast(
      "Impossible d'activer les notifications push sur cet appareil.",
      "erreur",
    );
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
      // Abonnement retiré par le serveur : l'état affiché doit le refléter.
      if (["APPAREIL_INCONNU", "APPAREIL_REVOQUE"].includes(corps.raison)) {
        await compterAppareilsAbonnes();
      }
    }
  } catch (_) {
    afficherToast("Erreur réseau.", "erreur");
  } finally {
    testEnCours.value = false;
  }
}

async function desabonnerPush() {
  if (!swRegistration) return;
  pushEnCours.value = true;
  try {
    const abonnement = await swRegistration.pushManager.getSubscription();
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
    await compterAppareilsAbonnes();
    document.cookie = "push_opt_in=; path=/; max-age=0; SameSite=Strict";
    afficherToast("Notifications push désactivées.", "succes");
  } catch (_) {
    afficherToast(
      "Impossible de désactiver les notifications push sur cet appareil.",
      "erreur",
    );
  } finally {
    pushEnCours.value = false;
  }
}

onMounted(charger);
onUnmounted(() => controleur.abort());
</script>

<style scoped>
.param-bloc-push {
  margin-top: 1.5rem;
}

.notif-grille__entete,
.notif-grille__ligne {
  display: grid;
  grid-template-columns: 1fr 3rem 3rem;
  align-items: center;
  gap: 0;
  clear: left;
}

.notif-grille__entete {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--fg-muted);
  text-align: center;
  margin-bottom: 0.25rem;
}

.notif-grille__entete span:first-child {
  text-align: left;
}

.notif-grille__ligne {
  padding: 0.25rem 0;
}

.notif-grille__libelle {
  font-size: 0.875rem;
}

.notif-grille__case {
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
}

.notif-radio {
  display: flex;
  align-items: center;
  gap: 0.5rem;
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
