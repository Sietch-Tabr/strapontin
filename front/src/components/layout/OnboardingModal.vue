<template>
  <ModaleEtapes
    ref="modaleRef"
    cle="onboarding"
    :etapes="ETAPES"
    @termine="fermer"
    @ferme="fermer"
  />
</template>

<script setup>
defineOptions({ name: "OnboardingModal" });

import { ref, watch } from "vue";
import ModaleEtapes from "../ModaleEtapes.vue";
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
    icone: "icon-bell",
    titre: "Notifications email et push",
    texte:
      "Activez les notifications dans Paramètres pour être alerté dès qu'un nouveau concert apparaît.",
  },
  {
    icone: "icon-smartphone",
    titre: "Installer l'application",
    texte:
      "Ajoutez Strapontin à l'écran d'accueil de votre appareil pour l'ouvrir comme une application.",
  },
];
</script>
