<template>
  <p v-if="canalPerdu" class="alerte alerte-avertissement bandeau-canal-perdu">
    Vous ne recevez plus les nouveaux concerts : votre dernier appareil
    n'accepte plus les notifications push et l'email est désactivé.
    <RouterLink to="/parametres#notifications"
      >Réactiver mes notifications</RouterLink
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

// Relu à chaque navigation : l'état change dès que l'utilisateur réactive un canal.
async function charger() {
  if (route.meta.authPage) return;
  try {
    const rep = await appelApi("/api/user/config/canal-perdu");
    canalPerdu.value = rep.ok && (await lireJson(rep)).canalPerdu === true;
  } catch (_) {
    canalPerdu.value = false;
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
  margin-bottom: 1rem;
}
</style>
