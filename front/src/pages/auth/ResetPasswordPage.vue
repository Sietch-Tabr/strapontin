<template>
  <AuthLayout>
    <h1 class="auth-titre">
      {{ estInvite ? "Créer votre compte" : "Nouveau mot de passe" }}
    </h1>
    <p class="auth-sous-titre">
      <template v-if="estInvite">
        Choisissez votre identifiant de connexion et un mot de passe d'au moins
        12 caractères.
      </template>
      <template v-else>
        Choisissez un mot de passe d'au moins 12 caractères.
      </template>
    </p>

    <div
      v-if="erreur"
      role="alert"
      aria-live="assertive"
      class="alerte alerte-erreur"
    >
      {{ erreur }}
    </div>

    <form v-if="!chargementType" @submit.prevent="soumettre">
      <input type="hidden" name="token" :value="token" />
      <div v-if="estInvite" class="form-groupe">
        <label for="identifiant">Identifiant de connexion</label>
        <input
          id="identifiant"
          ref="champIdentifiant"
          v-model="identifiant"
          name="identifiant"
          type="text"
          required
          minlength="3"
          maxlength="30"
          pattern="[a-zA-Z0-9._\-]+"
          autocomplete="username"
          placeholder="Entre 3 et 30 caractères (lettres, chiffres, . - _)"
        />
      </div>
      <div class="form-groupe">
        <label for="mot-de-passe">Nouveau mot de passe</label>
        <input
          id="mot-de-passe"
          ref="champMotDePasse"
          v-model="motDePasse"
          name="motDePasse"
          type="password"
          autocomplete="new-password"
          minlength="12"
          required
        />
      </div>
      <button
        type="submit"
        class="bouton bouton-primaire bouton-pleine-largeur"
        :disabled="chargement"
      >
        {{ estInvite ? "Créer mon compte" : "Enregistrer" }}
      </button>
    </form>

    <p v-if="!estInvite" class="auth-lien-secondaire">
      <RouterLink to="/forgot-password">Renvoyer un lien</RouterLink>
    </p>
  </AuthLayout>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "ResetPasswordPage" });

import { ref, onMounted, nextTick } from "vue";
import { useRoute, useRouter } from "vue-router";
import AuthLayout from "../../components/auth/AuthLayout.vue";
import { useSession } from "../../composables/useSession.js";

const route = useRoute();
const router = useRouter();
const { chargerSession } = useSession();

const token = route.query.token ?? "";
const identifiant = ref("");
const motDePasse = ref("");
const chargement = ref(false);
const chargementType = ref(true);
const erreur = ref("");
const estInvite = ref(false);
const champMotDePasse = ref(null);
const champIdentifiant = ref(null);

onMounted(async () => {
  if (!token) {
    router.replace("/forgot-password");
    return;
  }
  try {
    const rep = await appelApi(
      `/api/auth/type-token?token=${encodeURIComponent(token)}`,
    );
    if (rep.ok) {
      const data = await lireJson(rep);
      estInvite.value = data.type === "invite";
    }
  } catch (_) {
    // fallback mode reset classique
  } finally {
    chargementType.value = false;
  }
  await nextTick();
  if (estInvite.value) {
    champIdentifiant.value?.focus();
  } else {
    champMotDePasse.value?.focus();
  }
});

async function soumettre() {
  erreur.value = "";
  chargement.value = true;
  try {
    const corps = { token, motDePasse: motDePasse.value };
    if (estInvite.value) corps.identifiant = identifiant.value;
    const rep = await appelApi("/api/auth/reset-password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(corps),
    });
    if (rep.ok) {
      if (estInvite.value) {
        await chargerSession();
        router.push("/");
      } else {
        router.push("/login?reset=true");
      }
      return;
    }
    const data = await lireJson(rep);
    erreur.value =
      data.erreur ??
      "Lien invalide ou expiré. Recommencez depuis le formulaire.";
  } catch (_) {
    erreur.value = "Impossible de continuer. Vérifiez votre connexion.";
  } finally {
    chargement.value = false;
  }
}
</script>
