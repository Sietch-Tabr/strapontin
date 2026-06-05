<template>
  <AuthLayout>
    <h1 class="auth-title">Mot de passe oublié</h1>
    <p class="auth-subtitle">
      Entrez votre adresse email pour recevoir un lien de réinitialisation.
    </p>

    <div v-if="succes" class="alerte alerte-succes">
      Si cette adresse correspond à un compte, un email vous a été envoyé.
    </div>

    <form v-if="!succes" @submit.prevent="soumettre">
      <div class="form-group">
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
      <button type="submit" class="btn btn-primary btn-full" :disabled="chargement">
        Envoyer le lien
      </button>
    </form>

    <p class="mt-4 text-sm text-muted texte-centre">
      <RouterLink to="/login">← Retour à la connexion</RouterLink>
    </p>
  </AuthLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import AuthLayout from '../../components/auth/AuthLayout.vue'

const email = ref('')
const chargement = ref(false)
const succes = ref(false)
const champEmail = ref(null)

onMounted(() => {
  champEmail.value?.focus()
})

async function soumettre() {
  chargement.value = true
  try {
    await fetch('/api/auth/forgot-password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.value }),
    })
  } finally {
    succes.value = true
    chargement.value = false
  }
}
</script>
