<template>
  <ModaleEtapes
    ref="modaleRef"
    cle="onboarding"
    :etapes="ETAPES"
    @termine="fermer"
    @ferme="fermer"
  >
    <template #notifications>
      <ChoixNotificationsOnboarding v-if="ouvert" />
    </template>
  </ModaleEtapes>
</template>

<script setup>
defineOptions({ name: "OnboardingModal" });

import { ref, watch } from "vue";
import ModaleEtapes from "../ModaleEtapes.vue";
import ChoixNotificationsOnboarding from "./ChoixNotificationsOnboarding.vue";
import { useOnboarding } from "../../composables/useOnboarding.js";

const { ouvert, fermer: fermerOnboarding } = useOnboarding();
const modaleRef = ref(null);

function fermer() {
  fermerOnboarding();
}

watch(ouvert, (val) => {
  if (val) modaleRef.value?.ouvrir();
  else modaleRef.value?.fermer();
});

const ETAPES = [
  {
    icone: "icon-music",
    titre: "Suivi artistes et salles",
    texte:
      "Ajoutez vos artistes et salles préférés : leurs nouveaux concerts sont recherchés automatiquement, toutes les deux heures en journée.",
  },
  {
    icone: "icon-sliders-horizontal",
    titre: "Vos départements",
    texte:
      "Choisissez vos départements dans Paramètres : ils limitent les concerts de vos artistes et définissent la page Découverte.",
  },
  {
    cle: "notifications",
    icone: "icon-bell",
    titre: "Notifications email et push",
    texte:
      "Choisissez comment être prévenu des nouveaux concerts de vos artistes. Vous pourrez changer d'avis à tout moment dans Paramètres.",
  },
  {
    icone: "icon-smartphone",
    titre: "Installer l'application",
    texte:
      "Ajoutez Strapontin à l'écran d'accueil de votre appareil pour l'ouvrir comme une application.",
  },
];
</script>
