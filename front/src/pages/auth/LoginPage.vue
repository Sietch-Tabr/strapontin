<template>
  <AuthLayout>
    <h1 class="lecteur-ecran">Connexion</h1>
    <div class="auth-logo">
      <img src="/images/logo.webp" alt="Strapontin" />
    </div>
    <p class="auth-sous-titre">Connectez-vous à votre compte</p>

    <div
      v-if="erreurSoumission"
      role="alert"
      aria-live="assertive"
      class="alerte alerte-erreur"
    >
      {{ erreurSoumission }}
    </div>
    <div v-else-if="messageSucces" role="status" class="alerte alerte-succes">
      {{ messageSucces }}
    </div>

    <form @submit.prevent="soumettre">
      <div class="form-groupe">
        <label for="identifiant">Identifiant</label>
        <input
          id="identifiant"
          ref="champIdentifiant"
          v-model="identifiant"
          name="identifiant"
          type="text"
          autocomplete="username"
          required
        />
      </div>
      <div class="form-groupe">
        <label for="mot-de-passe">Mot de passe</label>
        <input
          id="mot-de-passe"
          v-model="motDePasse"
          name="motDePasse"
          type="password"
          autocomplete="current-password"
          required
        />
      </div>
      <button
        type="submit"
        class="bouton bouton-primaire bouton-pleine-largeur"
        :disabled="chargement"
      >
        Se connecter
      </button>
    </form>

    <p class="auth-lien-secondaire">
      <RouterLink to="/forgot-password">Mot de passe oublié ?</RouterLink>
    </p>
  </AuthLayout>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "LoginPage" });

import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import AuthLayout from "../../components/auth/AuthLayout.vue";
import { useSession } from "../../composables/useSession.js";

const route = useRoute();
const router = useRouter();
const { chargerSession } = useSession();

const identifiant = ref("");
const motDePasse = ref("");
const chargement = ref(false);
const erreurSoumission = ref(
  route.query.totp === "trop_tentatives"
    ? "Trop de tentatives de code. Reconnectez-vous, puis saisissez un nouveau code."
    : "",
);
const champIdentifiant = ref(null);

onMounted(() => {
  champIdentifiant.value?.focus();
});

const messageSucces = computed(() => {
  if ("deconnecte" in route.query) return "Vous avez été déconnecté.";
  if ("reset" in route.query)
    return "Mot de passe mis à jour. Connectez-vous avec votre nouveau mot de passe.";
  if ("configure" in route.query)
    return "Application configurée. Connectez-vous avec le compte administrateur.";
  return "";
});

async function soumettre() {
  erreurSoumission.value = "";
  chargement.value = true;
  try {
    const rep = await appelApi("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        identifiant: identifiant.value,
        motDePasse: motDePasse.value,
      }),
    });
    if (!rep.ok) {
      if (rep.status === 429) {
        const corps = await lireJson(rep);
        erreurSoumission.value =
          corps.erreur ??
          "Trop de tentatives de connexion. Réessayez dans quelques minutes.";
      } else if (rep.status === 401) {
        erreurSoumission.value = "Identifiant ou mot de passe incorrect.";
      } else if (rep.status === 403) {
        erreurSoumission.value =
          "Suite à un incident de sécurité, un nouveau mot de passe est exigé. Utilisez « Mot de passe oublié » pour le définir depuis le lien reçu par email.";
      } else {
        erreurSoumission.value =
          "Connexion impossible pour le moment. Réessayez plus tard.";
      }
      return;
    }
    const data = await lireJson(rep);
    if (data.totpRequis) {
      router.push("/auth/totp");
    } else {
      await chargerSession();
      router.push("/");
    }
  } catch (_) {
    erreurSoumission.value =
      "Impossible de se connecter. Vérifiez votre connexion.";
  } finally {
    chargement.value = false;
  }
}
</script>
