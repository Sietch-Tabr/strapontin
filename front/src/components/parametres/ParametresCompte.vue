<template>
  <section class="parametres-section" id="compte">
    <h2 class="sr-only">Compte</h2>

    <div class="param-bloc">
      <h3 class="param-label">Profil</h3>
      <form class="form-vertical form-compact" @submit.prevent="sauvegarderProfil">
        <div class="form-group">
          <label for="email">Email</label>
          <input
            id="email"
            v-model="email"
            type="email"
            maxlength="254"
            autocomplete="email"
          />
          <p v-if="profil?.pendingEmail" class="form-aide">
            En attente de confirmation : {{ profil.pendingEmail }}
          </p>
        </div>
        <button type="submit" class="btn btn-secondary">Enregistrer</button>
      </form>
    </div>

    <div class="param-bloc">
      <h3 class="param-label" id="label-identifiant">Identifiant de connexion</h3>
      <form class="form-inline" @submit.prevent="sauvegarderIdentifiant">
        <label for="identifiant" class="sr-only">Identifiant de connexion</label>
        <input
          id="identifiant"
          v-model="identifiant"
          type="text"
          minlength="3"
          maxlength="30"
          pattern="[a-zA-Z0-9_]+"
          autocomplete="username"
        />
        <button type="submit" class="btn btn-secondary">Modifier</button>
      </form>
    </div>

    <div class="param-bloc">
      <h3 class="param-label">Authentification à deux facteurs (TOTP)</h3>
      <div v-if="twoFactorActif">
        <p class="param-aide">TOTP activé sur votre compte.</p>
        <form class="form-vertical form-compact" @submit.prevent="desactiverTotp">
          <div class="form-group">
            <label for="totp-desact-code">Code de confirmation</label>
            <input
              id="totp-desact-code"
              v-model="totpDesactCode"
              type="text"
              inputmode="numeric"
              pattern="[0-9]{6}"
              maxlength="6"
              autocomplete="one-time-code"
              placeholder="123456"
              required
            />
          </div>
          <button type="submit" class="btn btn-danger btn-sm">Désactiver le TOTP</button>
        </form>
      </div>
      <div v-else>
        <p class="param-aide">
          Protégez votre compte avec une application d'authentification
          (Google Authenticator, Aegis…).
        </p>
        <RouterLink to="/parametres/totp" class="btn btn-secondary btn-sm">
          Configurer le TOTP
        </RouterLink>
      </div>
    </div>

    <div class="param-bloc">
      <h3 class="param-label">Mot de passe</h3>
      <p class="param-aide">La session sera invalidée après le changement.</p>
      <form class="form-vertical form-compact" @submit.prevent="changerMotDePasse">
        <div class="form-group">
          <label for="mdp-actuel">Mot de passe actuel</label>
          <input
            id="mdp-actuel"
            v-model="mdpActuel"
            type="password"
            required
            autocomplete="current-password"
          />
        </div>
        <div class="form-group">
          <label for="mdp-nouveau">Nouveau mot de passe</label>
          <input
            id="mdp-nouveau"
            v-model="mdpNouveau"
            type="password"
            required
            minlength="12"
            autocomplete="new-password"
            placeholder="12 caractères minimum"
          />
          <span class="form-aide" aria-live="polite">
            {{ mdpNouveau.length > 0 ? mdpNouveau.length + ' caractère' + (mdpNouveau.length > 1 ? 's' : '') : '' }}
          </span>
        </div>
        <button type="submit" class="btn btn-secondary">Changer</button>
      </form>
    </div>
  </section>
</template>

<script setup>
defineOptions({ name: 'ParametresCompte' })

import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useToasts } from '../../composables/useToasts.js'

const router = useRouter()
const { afficherToast } = useToasts()

const profil = ref(null)
const email = ref('')
const identifiant = ref('')
const twoFactorActif = ref(false)
const totpDesactCode = ref('')
const mdpActuel = ref('')
const mdpNouveau = ref('')

async function charger() {
  try {
    const rep = await fetch('/api/user/profile')
    if (rep.ok) {
      profil.value = await rep.json()
      email.value = profil.value.email ?? ''
      identifiant.value = profil.value.identifiant ?? ''
      twoFactorActif.value = profil.value.twoFactorActif ?? false
    }
  } catch (_) {
    afficherToast('Erreur lors du chargement du profil.', 'erreur')
  }
}

async function sauvegarderProfil() {
  const emailActuel = profil.value?.email ?? ''
  const emailChange = email.value && email.value.toLowerCase() !== emailActuel.toLowerCase()

  if (emailChange) {
    const rep = await fetch('/api/parametres/email-change', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.value }),
    })
    const corps = await rep.json()
    if (rep.ok) {
      afficherToast(
        'Un email de confirmation a été envoyé à ' + email.value + '.',
        'avertissement',
      )
      if (profil.value) profil.value.pendingEmail = email.value
    } else {
      afficherToast(corps.erreur || "Erreur lors du changement d'email.", 'erreur')
    }
    return
  }
}

async function sauvegarderIdentifiant() {
  const rep = await fetch('/api/user/identifiant', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ identifiant: identifiant.value }),
  })
  const corps = await rep.json()
  if (rep.ok) {
    afficherToast('Identifiant modifié.', 'succes')
  } else {
    afficherToast(corps.erreur || 'Erreur.', 'erreur')
  }
}

async function desactiverTotp() {
  const rep = await fetch('/api/totp/desactiver', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: totpDesactCode.value }),
  })
  const corps = await rep.json()
  if (rep.ok) {
    twoFactorActif.value = false
    totpDesactCode.value = ''
    afficherToast('Authentification à deux facteurs désactivée.', 'succes')
  } else {
    afficherToast(corps.erreur || 'Code TOTP incorrect.', 'erreur')
  }
}

async function changerMotDePasse() {
  const rep = await fetch('/api/user/password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      motDePasseActuel: mdpActuel.value,
      motDePasseNouveau: mdpNouveau.value,
    }),
  })
  if (rep.ok) {
    afficherToast('Mot de passe modifié. Reconnectez-vous.', 'succes')
    router.push('/login?reset=true')
  } else {
    const corps = await rep.json()
    afficherToast(corps.erreur || 'Erreur lors du changement de mot de passe.', 'erreur')
  }
}

onMounted(charger)
</script>
