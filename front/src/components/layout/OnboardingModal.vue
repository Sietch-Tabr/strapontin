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
      "Ajoutez vos artistes et salles préférés : la surveillance des nouveaux concerts se fait automatiquement toutes les 2 heures.",
  },
  {
    icone: "icon-sliders-horizontal",
    titre: "Filtre par département",
    texte:
      "Limitez la découverte à vos départements dans Paramètres pour ne voir que les concerts qui vous concernent.",
  },
  {
    icone: "icon-bell",
    titre: "Notifications email et push",
    texte:
      "Activez les notifications dans Paramètres pour être alerté dès qu'un nouveau concert apparaît.",
  },
  {
    icone: "icon-smartphone",
    titre: "Installation PWA",
    texte:
      "Installez Strapontin sur votre appareil pour un accès rapide, même hors ligne.",
  },
];
</script>
