<template>
  <AuthLayout>
    <h1 class="auth-titre">Mot de passe oublié</h1>
    <p class="auth-sous-titre">
      Entrez votre adresse email pour recevoir un lien de réinitialisation.
    </p>

    <div
      v-if="erreur"
      role="alert"
      aria-live="assertive"
      class="alerte alerte-erreur"
    >
      {{ erreur }}
    </div>

    <div v-if="succes" class="alerte alerte-succes">
      Si cette adresse correspond à un compte, un email vous a été envoyé.
    </div>

    <form v-if="!succes" @submit.prevent="soumettre">
      <div class="form-groupe">
        <label for="email">Adresse email</label>
        <input
          id="email"
          ref="champEmail"
          v-model="email"
          name="email"
          type="email"
          autocomplete="email"
          required
        />
      </div>
      <button
        type="submit"
        class="bouton bouton-primaire bouton-pleine-largeur"
        :disabled="chargement"
      >
        Envoyer le lien
      </button>
    </form>

    <p class="auth-lien-secondaire">
      <RouterLink to="/login">← Retour à la connexion</RouterLink>
    </p>
  </AuthLayout>
</template>

<script setup>
defineOptions({ name: "ForgotPasswordPage" });

import { ref, onMounted } from "vue";
import AuthLayout from "../../components/auth/AuthLayout.vue";

const email = ref("");
const chargement = ref(false);
const succes = ref(false);
const erreur = ref("");
const champEmail = ref(null);

onMounted(() => {
  champEmail.value?.focus();
});

async function soumettre() {
  erreur.value = "";
  chargement.value = true;
  try {
    // Le serveur répond toujours 200, que l'adresse corresponde ou non à un compte
    // (anti-énumération) — seul un échec de la requête elle-même (hors ligne, timeout, avant
    // d'atteindre le serveur) doit être distingué du succès, sans jamais révéler l'existence
    // d'un compte.
    await fetch("/api/auth/forgot-password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: email.value }),
    });
    succes.value = true;
  } catch (_) {
    erreur.value = "Impossible de continuer. Vérifiez votre connexion.";
  } finally {
    chargement.value = false;
  }
}
</script>
