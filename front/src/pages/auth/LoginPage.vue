<template>
  <AuthLayout>
    <h1 class="sr-only">Connexion</h1>
    <div class="auth-logo">
      <img src="/images/logo.webp" alt="Strapontin" />
    </div>
    <p class="auth-subtitle">Connectez-vous à votre compte</p>

    <div v-if="erreurSoumission" role="alert" aria-live="assertive" class="alerte alerte-erreur">
      {{ erreurSoumission }}
    </div>
    <div v-else-if="messageSucces" role="status" class="alerte alerte-succes">
      {{ messageSucces }}
    </div>

    <form @submit.prevent="soumettre">
      <div class="form-group">
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
      <div class="form-group">
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
      <button type="submit" class="btn btn-primary btn-full" :disabled="chargement">
        Se connecter
      </button>
    </form>

    <p class="mt-4 text-sm text-muted texte-centre">
      <RouterLink to="/forgot-password">Mot de passe oublié ?</RouterLink>
    </p>
  </AuthLayout>
</template>

<script setup>
defineOptions({ name: 'LoginPage' })

import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'
import { useSession } from '../../composables/useSession.js'
import { corpsJsonOuVide } from '../../utils/reponseJson.js'

const route = useRoute()
const router = useRouter()
const { chargerSession } = useSession()

const identifiant = ref('')
const motDePasse = ref('')
const chargement = ref(false)
const erreurSoumission = ref(
  route.query.totp === 'trop_tentatives'
    ? 'Trop de tentatives de code. Reconnectez-vous, puis saisissez un nouveau code.'
    : '',
)
const champIdentifiant = ref(null)

onMounted(() => {
  champIdentifiant.value?.focus()
})

const messageSucces = computed(() => {
  if ('deconnecte' in route.query) return 'Vous avez été déconnecté.'
  if ('reset' in route.query) return 'Mot de passe mis à jour. Connectez-vous avec votre nouveau mot de passe.'
  if ('configure' in route.query) return 'Application configurée. Connectez-vous avec le compte administrateur.'
  return ''
})

async function soumettre() {
  erreurSoumission.value = ''
  chargement.value = true
  try {
    const rep = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ identifiant: identifiant.value, motDePasse: motDePasse.value }),
    })
    if (!rep.ok) {
      if (rep.status === 429) {
        const corps = await corpsJsonOuVide(rep)
        erreurSoumission.value =
          corps.erreur ?? 'Trop de tentatives de connexion. Réessayez dans quelques minutes.'
      } else if (rep.status === 401) {
        erreurSoumission.value = 'Identifiant ou mot de passe incorrect.'
      } else {
        erreurSoumission.value = 'Connexion impossible pour le moment. Réessayez plus tard.'
      }
      return
    }
    const data = await rep.json()
    if (data.totpRequis) {
      router.push('/auth/totp')
    } else {
      await chargerSession()
      router.push('/')
    }
  } catch (_) {
    erreurSoumission.value = 'Impossible de se connecter. Vérifiez votre connexion.'
  } finally {
    chargement.value = false
  }
}
</script>
