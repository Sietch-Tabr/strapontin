<template>
  <section class="parametres-section" id="preferences">
    <h2 class="sr-only">Préférences</h2>

    <EtatChargement v-if="chargement" />
    <template v-else>

    <div class="param-bloc">
      <h3 class="param-label">Départements de découverte</h3>
      <p class="param-aide">
        Séparés par des virgules (ex. : 75, 92, 93). Utilisés par la page Découverte.
        Pour la Corse, utilisez 2A ou 2B.
      </p>
      <form class="form-inline" @submit.prevent="sauvegarderDepartements">
        <input
          v-model="departements"
          name="departements"
          type="text"
          placeholder="75, 92, 93…"
          autocomplete="off"
          maxlength="1000"
        />
        <button type="submit" class="btn btn-secondary">Enregistrer</button>
      </form>
    </div>

    <div class="param-bloc">
      <h3 class="param-label" id="label-filtre-disponibles">
        Concerts disponibles uniquement
      </h3>
      <p class="param-aide">
        Masque les concerts dont la billetterie est fermée (annulés, complets…)
        sur l'accueil et Mes spectacles.
      </p>
      <form class="form-toggle">
        <label class="switch switch--succes">
          <input
            v-model="filtreDisponibles"
            type="checkbox"
            aria-labelledby="label-filtre-disponibles"
            @change="sauvegarderFiltres"
          />
          <span class="switch-curseur"></span>
        </label>
      </form>
    </div>

    <div class="param-bloc">
      <h3 class="param-label">Thème de l'interface</h3>
      <div class="theme-choix" aria-label="Choisir le thème">
        <button
          v-for="opt in optionsTheme"
          :key="opt.valeur"
          type="button"
          class="btn btn-theme"
          :class="{ actif: theme === opt.valeur }"
          :aria-pressed="theme === opt.valeur ? 'true' : 'false'"
          @click="changerTheme(opt.valeur)"
        >{{ opt.label }}</button>
      </div>
      <p class="param-aide">Le mode Automatique suit la préférence de votre appareil.</p>
    </div>

    </template>
  </section>
</template>

<script setup>
defineOptions({ name: 'ParametresPreferences' })

import { ref, onMounted } from 'vue'
import { useToasts } from '../../composables/useToasts.js'
import { useTheme } from '../../composables/useTheme.js'
import EtatChargement from '../EtatChargement.vue'

const { afficherToast } = useToasts()
const { enregistrerTheme, lireThemeCookie } = useTheme()

const chargement = ref(true)
const departements = ref('')
const filtreDisponibles = ref(false)
const theme = ref('auto')
const optionsTheme = [
  { valeur: 'auto', label: 'Automatique' },
  { valeur: 'clair', label: 'Clair' },
  { valeur: 'sombre', label: 'Sombre' },
]

async function charger() {
  try {
    const rep = await fetch('/api/user/config')
    if (rep.ok) {
      const config = await rep.json()
      departements.value = config.departements?.join(', ') ?? ''
      filtreDisponibles.value = config.filtreDisponibles ?? false
    }
    theme.value = lireThemeCookie() || 'auto'
  } catch (_) {
    afficherToast('Erreur lors du chargement des préférences.', 'erreur')
  } finally {
    chargement.value = false
  }
}

async function sauvegarderDepartements() {
  try {
    const rep = await fetch('/api/user/config/departements', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ departements: departements.value }),
    })
    const corps = await rep.json()
    if (rep.ok) {
      if (corps.ignores?.length) {
        afficherToast('Codes ignorés car non reconnus : ' + corps.ignores.join(', '), 'avertissement')
      } else {
        afficherToast('Départements enregistrés.', 'succes')
      }
    } else {
      afficherToast(corps.erreur || 'Erreur.', 'erreur')
    }
  } catch (e) {
    afficherToast('Erreur réseau.', 'erreur')
  }
}

async function sauvegarderFiltres() {
  try {
    const rep = await fetch('/api/user/config/filtres', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filtreDisponibles: filtreDisponibles.value }),
    })
    if (!rep.ok) afficherToast('Erreur lors de l\'enregistrement du filtre.', 'erreur')
  } catch (e) {
    afficherToast('Erreur réseau.', 'erreur')
  }
}

async function changerTheme(valeur) {
  theme.value = valeur
  enregistrerTheme(valeur)
  try {
    const rep = await fetch('/api/user/config/theme', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ valeur }),
    })
    if (!rep.ok) afficherToast('Erreur lors de l\'enregistrement du thème.', 'erreur')
  } catch (e) {
    afficherToast('Erreur réseau.', 'erreur')
  }
}

onMounted(charger)
</script>
