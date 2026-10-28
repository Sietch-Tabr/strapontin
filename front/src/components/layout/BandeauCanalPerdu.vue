<template>
  <p v-if="canalPerdu" class="alerte alerte-avertissement bandeau-canal-perdu">
    Vos notifications push sont arrêtées : votre dernier appareil a été retiré
    et vos choix push ont été décochés. Réactivez les notifications sur
    l'appareil, puis recochez les contenus souhaités.
    <RouterLink to="/parametres#notifications"
      >Réactiver mes notifications</RouterLink
    >
  </p>
  <p
    v-else-if="pushJamaisActive"
    class="alerte alerte-avertissement bandeau-canal-perdu"
  >
    Vos notifications push ne sont pas activées : aucun appareil n'est
    enregistré et l'email est désactivé.
    <RouterLink to="/parametres#notifications"
      >Activer mes notifications</RouterLink
    >
  </p>
</template>

<script setup>
defineOptions({ name: "BandeauCanalPerdu" });

import { onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { appelApi, lireJson } from "../../utils/api.js";

const route = useRoute();
const router = useRouter();
const canalPerdu = ref(false);
const pushJamaisActive = ref(false);

// Relu à chaque navigation : l'état change dès que l'utilisateur réactive un canal.
async function charger() {
  if (route.meta.authPage) return;
  try {
    const rep = await appelApi("/api/user/config/canal-perdu");
    const etat = rep.ok ? await lireJson(rep) : {};
    canalPerdu.value = etat?.canalPerdu === true;
    pushJamaisActive.value = etat?.pushJamaisActive === true;
  } catch (_) {
    canalPerdu.value = false;
    pushJamaisActive.value = false;
  }
}

onMounted(async () => {
  await router.isReady();
  await charger();
});
watch(() => route.path, charger);
</script>

<style scoped>
.bandeau-canal-perdu {
  margin-bottom: var(--espace-8);
}
</style>
