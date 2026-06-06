<template>
  <AuthLayout>
    <h1 class="auth-title">Nouveau mot de passe</h1>
    <p class="auth-subtitle">
      Choisissez un mot de passe d'au moins 12 caractères.
    </p>

    <div v-if="erreur" class="alerte alerte-erreur">{{ erreur }}</div>

    <form @submit.prevent="soumettre">
      <input type="hidden" name="token" :value="token" />
      <div class="form-group">
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
      <button type="submit" class="btn btn-primary btn-full" :disabled="chargement">
        Enregistrer
      </button>
    </form>

    <p class="mt-4 text-sm text-muted texte-centre">
      <RouterLink to="/forgot-password">Renvoyer un lien</RouterLink>
    </p>
  </AuthLayout>
</template>

<script setup>
defineOptions({ name: 'ResetPasswordPage' })

import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'

const route = useRoute()
const router = useRouter()

const token = route.query.token ?? ''
const motDePasse = ref('')
const chargement = ref(false)
const erreur = ref('')
const champMotDePasse = ref(null)

onMounted(() => {
  if (!token) {
    router.replace('/forgot-password')
    return
  }
  champMotDePasse.value?.focus()
})

async function soumettre() {
  erreur.value = ''
  chargement.value = true
  try {
    const rep = await fetch('/api/auth/reset-password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, motDePasse: motDePasse.value }),
    })
    if (rep.ok) {
      router.push('/login?reset=true')
      return
    }
    const data = await rep.json()
    erreur.value = data.erreur ?? 'Lien invalide ou expiré. Recommencez depuis le formulaire.'
  } catch (_) {
    erreur.value = 'Impossible de réinitialiser le mot de passe. Vérifiez votre connexion.'
  } finally {
    chargement.value = false
  }
}
</script>
