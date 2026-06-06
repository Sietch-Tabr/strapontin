<template>
  <AuthLayout>
    <h1 class="auth-title">Bienvenue sur Strapontin</h1>
    <p class="auth-subtitle">
      Veuillez personnaliser vos identifiants avant de continuer.
    </p>

    <div v-if="erreur" class="alerte alerte-erreur">{{ erreur }}</div>

    <form @submit.prevent="soumettre" class="form-vertical">
      <div class="form-group">
        <label for="nom">Nom d'affichage</label>
        <input
          id="nom"
          v-model="nom"
          name="nom"
          type="text"
          required
          maxlength="100"
          autocomplete="name"
          placeholder="Votre prénom ou pseudo"
        />
      </div>
      <div class="form-group">
        <label for="identifiant">Identifiant de connexion</label>
        <input
          id="identifiant"
          v-model="identifiant"
          name="identifiant"
          type="text"
          required
          minlength="3"
          maxlength="30"
          pattern="[a-zA-Z0-9_]+"
          autocomplete="username"
          placeholder="Entre 3 et 30 caractères (lettres, chiffres, _)"
        />
      </div>
      <div class="form-group">
        <label for="mot-de-passe-actuel">Mot de passe temporaire</label>
        <input
          id="mot-de-passe-actuel"
          v-model="motDePasseActuel"
          name="motDePasseActuel"
          type="password"
          required
          autocomplete="current-password"
          placeholder="Votre mot de passe temporaire"
        />
      </div>
      <div class="form-group">
        <label for="mot-de-passe-nouveau">Nouveau mot de passe</label>
        <input
          id="mot-de-passe-nouveau"
          v-model="motDePasseNouveau"
          name="motDePasseNouveau"
          type="password"
          required
          minlength="12"
          autocomplete="new-password"
          placeholder="12 caractères minimum"
        />
      </div>
      <button type="submit" class="btn btn-primary btn-full" :disabled="chargement">
        Valider et continuer
      </button>
    </form>
  </AuthLayout>
</template>

<script setup>
defineOptions({ name: 'FirstLoginPage' })

import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'

const router = useRouter()

const nom = ref('')
const identifiant = ref('')
const motDePasseActuel = ref('')
const motDePasseNouveau = ref('')
const chargement = ref(false)
const erreur = ref('')

async function soumettre() {
  erreur.value = ''
  chargement.value = true
  try {
    const rep = await fetch('/api/user/first-login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        nom: nom.value,
        identifiant: identifiant.value,
        motDePasseActuel: motDePasseActuel.value,
        motDePasseNouveau: motDePasseNouveau.value,
      }),
    })
    if (rep.ok) {
      router.push('/login?reset=true')
      return
    }
    const data = await rep.json()
    erreur.value = data.erreur ?? 'Une erreur est survenue.'
  } catch (_) {
    erreur.value = 'Impossible de continuer. Vérifiez votre connexion.'
  } finally {
    chargement.value = false
  }
}
</script>
