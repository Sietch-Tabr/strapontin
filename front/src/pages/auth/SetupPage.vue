<template>
  <AuthLayout>
    <div class="auth-logo">
      <img src="/images/logo.webp" alt="Strapontin" />
    </div>
    <p class="auth-sous-titre">Créez le premier compte administrateur</p>

    <div v-if="erreur" class="alerte alerte-erreur" role="alert">{{ erreur }}</div>

    <form @submit.prevent="soumettre">
      <div class="form-groupe">
        <label for="identifiant">Identifiant de connexion</label>
        <input
          id="identifiant"
          ref="champIdentifiant"
          v-model="identifiant"
          name="identifiant"
          type="text"
          minlength="3"
          maxlength="30"
          pattern="[a-zA-Z0-9._\-]+"
          autocomplete="username"
          required
        />
      </div>
      <div class="form-groupe">
        <label for="email">Email</label>
        <input
          id="email"
          v-model="email"
          name="email"
          type="email"
          maxlength="254"
          autocomplete="email"
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
          minlength="12"
          autocomplete="new-password"
          placeholder="12 caractères minimum"
          required
        />
      </div>
      <div class="form-groupe">
        <label for="base-url">URL publique de l'application</label>
        <input
          id="base-url"
          v-model="baseUrl"
          name="baseUrl"
          type="url"
          placeholder="https://moninstance.example.com"
          autocomplete="off"
        />
      </div>
      <button type="submit" class="bouton bouton-primaire bouton-pleine-largeur" :disabled="chargement">
        Créer le compte
      </button>
    </form>
  </AuthLayout>
</template>

<script setup>
defineOptions({ name: 'SetupPage' })

import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'
import { corpsJsonOuVide } from '../../utils/reponseJson.js'

const router = useRouter()

const identifiant = ref('')
const email = ref('')
const motDePasse = ref('')
const baseUrl = ref('')
const erreur = ref('')
const chargement = ref(false)
const champIdentifiant = ref(null)

onMounted(async () => {
  champIdentifiant.value?.focus()
  try {
    const rep = await fetch('/api/setup')
    if (rep.ok) {
      const data = await rep.json()
      if (data.deja_configure) {
        router.replace('/login')
      }
    }
  } catch (e) {
    console.error('Échec vérification statut setup', e)
  }
})

async function soumettre() {
  erreur.value = ''
  chargement.value = true
  try {
    const rep = await fetch('/api/setup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        identifiant: identifiant.value,
        email: email.value,
        motDePasse: motDePasse.value,
        baseUrl: baseUrl.value || null,
      }),
    })
    if (!rep.ok) {
      const data = await corpsJsonOuVide(rep)
      erreur.value = data.erreur || 'Une erreur est survenue.'
      return
    }
    const cible = baseUrl.value
      ? baseUrl.value.replace(/\/+$/, '') + '/login?configure=true'
      : '/login?configure=true'
    window.location.href = cible
  } catch {
    erreur.value = 'Impossible de se connecter au serveur.'
  } finally {
    chargement.value = false
  }
}
</script>
