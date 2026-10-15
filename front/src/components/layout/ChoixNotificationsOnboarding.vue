<template>
  <div class="choix-notifications">
    <div v-if="config" class="form-checkboxes">
      <label>
        <input
          type="checkbox"
          :checked="config.notifArtistes"
          :disabled="enCours || !config.resendDisponible"
          @change="basculerEmail($event.target.checked)"
        />
        <span>Recevoir un email des nouveaux concerts de mes artistes</span>
      </label>
      <p class="form-aide">
        {{
          config.resendDisponible
            ? libelleFrequence
            : "Les emails ne sont pas disponibles pour le moment."
        }}
      </p>
    </div>

    <template v-if="pushPossible">
      <p v-if="pushActif" class="form-aide" role="status">
        Notifications push activées sur cet appareil.
      </p>
      <button
        v-else
        type="button"
        class="bouton bouton-secondaire bouton-petit"
        :disabled="enCours"
        @click="activerPush"
      >
        Activer les notifications push sur cet appareil
      </button>
    </template>
    <p v-if="message" class="form-aide" role="alert">{{ message }}</p>
  </div>
</template>

<script setup>
defineOptions({ name: "ChoixNotificationsOnboarding" });

import { computed, onMounted, onUnmounted, ref } from "vue";
import { appelApi, lireJson } from "../../utils/api.js";
import {
  avecEmailArtistes,
  avecPushArtistes,
} from "../../utils/notifications.js";
import {
  abonnerAppareil,
  lireVapid,
  pushPrisEnCharge,
} from "../../composables/useAbonnementPush.js";

const config = ref(null);
const cleVapid = ref(null);
const pushActif = ref(false);
const enCours = ref(false);
const message = ref("");
const controleur = new AbortController();

const pushPossible = computed(
  () => pushPrisEnCharge() && cleVapid.value !== null,
);
const LIBELLES_FREQUENCE = {
  hebdomadaire: "Un récapitulatif chaque dimanche à 10h.",
  quotidien: "Un récapitulatif chaque jour à 8h.",
  temps_reel: "Un message à chaque nouveau concert.",
};
const libelleFrequence = computed(() =>
  config.value?.notifArtistes
    ? (LIBELLES_FREQUENCE[config.value.frequenceNotifications] ?? "")
    : "Aucun email ne vous sera envoyé.",
);

async function enregistrer(suivante) {
  const rep = await appelApi("/api/user/config/notifications", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      frequenceNotifications: suivante.frequenceNotifications,
      notifArtistes: suivante.notifArtistes,
      notifSalles: suivante.notifSalles,
      notifArtistesPush: suivante.notifArtistesPush,
      notifSallesPush: suivante.notifSallesPush,
      notifNouvelleVersion: suivante.notifNouvelleVersion,
    }),
    signal: controleur.signal,
  });
  if (!rep.ok) {
    const corps = await lireJson(rep);
    throw new Error(corps.erreur || "Votre choix n'a pas pu être enregistré.");
  }
  config.value = suivante;
}

async function basculerEmail(actif) {
  enCours.value = true;
  message.value = "";
  try {
    await enregistrer(avecEmailArtistes(config.value, actif));
  } catch (e) {
    if (e.name !== "AbortError") message.value = e.message;
  } finally {
    enCours.value = false;
  }
}

async function activerPush() {
  enCours.value = true;
  message.value = "";
  try {
    const resultat = await abonnerAppareil(cleVapid.value, controleur.signal);
    if (resultat.statut === "ok") {
      await enregistrer(avecPushArtistes(config.value));
      pushActif.value = true;
    } else if (resultat.statut === "refuse") {
      message.value =
        "Les notifications ont été refusées dans votre navigateur. Vous pourrez les autoriser plus tard depuis Paramètres.";
    } else {
      message.value =
        resultat.message ||
        "Impossible d'activer les notifications push sur cet appareil.";
    }
  } catch (e) {
    if (e.name !== "AbortError") {
      message.value =
        "Impossible d'activer les notifications push sur cet appareil.";
    }
  } finally {
    enCours.value = false;
  }
}

onMounted(async () => {
  try {
    const [repConfig, vapid] = await Promise.all([
      appelApi("/api/user/config", { signal: controleur.signal }),
      lireVapid(controleur.signal),
    ]);
    if (repConfig.ok) config.value = await lireJson(repConfig);
    if (vapid.disponible) cleVapid.value = vapid.clePublique;
    if (pushPossible.value) {
      const enregistrement = await navigator.serviceWorker.ready;
      pushActif.value =
        (await enregistrement.pushManager.getSubscription()) !== null &&
        !!config.value?.notifArtistesPush;
    }
  } catch (e) {
    if (e.name !== "AbortError") {
      message.value = "Vos préférences n'ont pas pu être chargées.";
    }
  }
});
onUnmounted(() => controleur.abort());
</script>

<style scoped>
.choix-notifications {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--espace-6);
  text-align: left;
}
</style>
