<template>
  <AuthLayout>
    <h1 class="auth-title">Strapontin</h1>
    <p class="auth-subtitle">
      Saisissez le code de votre application d'authentification.
    </p>

    <div v-if="erreur" class="alerte alerte-erreur">{{ erreur }}</div>

    <form @submit.prevent="soumettre" autocomplete="off">
      <div class="form-group">
        <label for="code">Code à 6 chiffres</label>
        <input
          id="code"
          ref="champCode"
          v-model="code"
          name="code"
          type="text"
          inputmode="numeric"
          autocomplete="one-time-code"
          required
        />
      </div>
      <button type="submit" class="btn btn-primary btn-full" :disabled="chargement">
        Vérifier
      </button>
    </form>

    <p class="mt-4 text-sm text-muted texte-centre">
      <a href="#" @click.prevent="seDeconnecter">Se déconnecter</a>
    </p>
  </AuthLayout>
</template>

<script setup>
defineOptions({ name: 'TotpChallengePage' })

import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'
import { useSession } from '../../composables/useSession.js'

const router = useRouter()
const { chargerSession } = useSession()

const code = ref('')
const chargement = ref(false)
const erreur = ref('')
const champCode = ref(null)

onMounted(() => {
  champCode.value?.focus()
})

async function soumettre() {
  erreur.value = ''
  chargement.value = true
  try {
    const rep = await fetch('/api/auth/totp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: code.value.replace(/\D/g, '') }),
    })
    if (rep.ok) {
      // Recharge la session pour que le garde de route reconnaisse l'utilisateur authentifié,
      // sinon le push('/') est intercepté et renvoie vers /login.
      await chargerSession()
      router.push('/')
      return
    }
    if (rep.status === 401) {
      router.push('/login?totp=trop_tentatives')
      return
    }
    if (rep.status === 409) {
      router.push('/')
      return
    }
    const data = await rep.json()
    erreur.value = data.erreur ?? 'Code incorrect.'
    code.value = ''
    champCode.value?.focus()
  } catch (_) {
    erreur.value = 'Impossible de vérifier le code. Vérifiez votre connexion.'
  } finally {
    chargement.value = false
  }
}

async function seDeconnecter() {
  await fetch('/api/auth/logout', { method: 'POST' })
  router.push('/login?deconnecte=true')
}
</script>
