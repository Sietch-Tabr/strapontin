<template>
  <AuthLayout>
    <div class="auth-logo">
      <img src="/images/logo.webp" alt="Strapontin" />
    </div>
    <p class="auth-subtitle">Créez le premier compte administrateur</p>

    <div v-if="erreur" class="alerte alerte-erreur" role="alert">{{ erreur }}</div>

    <form @submit.prevent="soumettre">
      <div class="form-group">
        <label for="nom">Nom d'affichage</label>
        <input
          id="nom"
          ref="champNom"
          v-model="nom"
          name="nom"
          type="text"
          maxlength="100"
          autocomplete="name"
          required
        />
      </div>
      <div class="form-group">
        <label for="identifiant">Identifiant de connexion</label>
        <input
          id="identifiant"
          v-model="identifiant"
          name="identifiant"
          type="text"
          minlength="3"
          maxlength="30"
          pattern="[a-zA-Z0-9_]+"
          autocomplete="username"
          required
        />
      </div>
      <div class="form-group">
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
      <div class="form-group">
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
      <div class="form-group">
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
      <button type="submit" class="btn btn-primary btn-full" :disabled="chargement">
        Créer le compte
      </button>
    </form>
  </AuthLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'

const router = useRouter()

const nom = ref('')
const identifiant = ref('')
const email = ref('')
const motDePasse = ref('')
const baseUrl = ref('')
const erreur = ref('')
const chargement = ref(false)
const champNom = ref(null)

onMounted(async () => {
  champNom.value?.focus()
  try {
    const rep = await fetch('/api/setup')
    if (rep.ok) {
      const data = await rep.json()
      if (data.deja_configure) {
        router.replace('/login')
      }
    }
  } catch {}
})

async function soumettre() {
  erreur.value = ''
  chargement.value = true
  try {
    const rep = await fetch('/api/setup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        nom: nom.value,
        identifiant: identifiant.value,
        email: email.value,
        motDePasse: motDePasse.value,
        baseUrl: baseUrl.value || null,
      }),
    })
    if (!rep.ok) {
      const data = await rep.json().catch(() => ({}))
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
