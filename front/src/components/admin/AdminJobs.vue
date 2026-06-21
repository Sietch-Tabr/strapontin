<template>
  <section class="admin-section" id="jobs">

    <div class="param-bloc">
      <h3 class="param-label">Actualisations</h3>
      <EtatChargement v-if="chargement" />
      <div v-else-if="statsJobs.length" class="jobs-liste">
        <div v-for="stat in statsJobs" :key="stat.typeJob" class="job-carte">
          <div class="job-carte-entete">
            <span class="job-pastille" :class="classePastille(stat)" aria-hidden="true"></span>
            <p class="job-carte-label">{{ LABELS_JOBS[stat.typeJob] ?? stat.typeJob }}</p>
            <span
              v-if="jobsEnCours.includes(stat.typeJob)"
              class="job-en-cours"
              aria-busy="true"
              aria-label="En cours"
            >
              <svg class="spinner" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true">
                <path d="M21 12a9 9 0 1 1-6.219-8.56" />
              </svg>
            </span>
          </div>
          <p class="job-carte-detail">
            <span v-if="stat.derniereExec">{{ formaterInstant(stat.derniereExec) }}</span>
            <span v-if="stat.derniereExec && stat.dureeMs != null">{{ ' · ' + formaterDuree(stat.dureeMs) }}</span>
            <span v-if="stat.nbInseres != null && stat.nbInseres > 0">{{ ' · +' + stat.nbInseres + ' insérés' }}</span>
            <span v-if="stat.nbMisAJour != null && stat.nbMisAJour > 0">{{ ' · ' + stat.nbMisAJour + ' màj' }}</span>
            <span v-if="!stat.derniereExec">Pas encore exécuté</span>
            <span v-if="stat.erreur" class="badge badge-erreur">{{ stat.erreur.length > 80 ? stat.erreur.substring(0, 80) + '…' : stat.erreur }}</span>
          </p>
          <p v-if="stat.prochaineExec" class="job-carte-detail">
            Prochaine exécution : {{ formaterInstant(stat.prochaineExec) }}
          </p>
        </div>
      </div>
      <p v-else-if="!chargement" class="etat-vide-texte">Aucun job exécuté pour l'instant.</p>
      <div class="relancer-actions">
        <button
          type="button"
          id="btn-relancer"
          class="btn btn-secondary"
          :disabled="relancerEnCours"
          @click="relancerFetches"
        >
          Relancer les fetches maintenant
        </button>
      </div>
    </div>

    <div class="param-bloc">
      <h3 class="param-label">Worker</h3>
      <div class="worker-carte" :class="{ 'worker-alerte': workerEnAlerte }">
        <div v-if="workerDepuisTexte">
          <p class="worker-carte-label">Dernier job complété</p>
          <p class="worker-carte-detail">{{ workerDepuisTexte }}</p>
          <p v-if="workerEnAlerte" class="worker-alerte-texte" role="alert">Inactif depuis plus de 3 h</p>
        </div>
        <div v-else>
          <p class="worker-carte-detail">Aucun job complété — worker non démarré.</p>
        </div>
      </div>
    </div>

  </section>
</template>

<script setup>
defineOptions({ name: 'AdminJobs' })

import { ref, shallowRef, computed, onMounted, onUnmounted } from 'vue'
import { useToasts } from '../../composables/useToasts.js'
import { formaterInstant } from '../../composables/useDates.js'
import EtatChargement from '../EtatChargement.vue'

const { afficherToast } = useToasts()

const LABELS_JOBS = {
  'fetch:artistes:batch': 'Artistes suivis',
  'fetch:region':         'Concerts par région',
  'notifier:digest':      'Notifications digest',
  'purger:concerts':      'Purge concerts',
  'recalculer:matches':   'Recalcul matchs',
  'fetch:salle':          'Salle',
}

const chargement = ref(true)
const statsJobs = shallowRef([])
const jobsEnCours = shallowRef([])
const workerLastSuccess = ref(null)
const relancerEnCours = ref(false)
let pollingInterval = null
// #571 : annule les fetch en vol à la destruction du composant (pas de maj de refs mortes).
const controleur = new AbortController()

const workerEnAlerte = computed(() => {
  if (!workerLastSuccess.value) return false
  return (Date.now() - new Date(workerLastSuccess.value).getTime()) / 60000 > 180
})

const workerDepuisTexte = computed(() => {
  if (!workerLastSuccess.value) return null
  const min = Math.floor((Date.now() - new Date(workerLastSuccess.value).getTime()) / 60000)
  if (min < 1) return "à l'instant"
  if (min < 60) return 'il y a ' + min + ' min'
  const h = Math.floor(min / 60)
  const m = min % 60
  return m > 0 ? 'il y a ' + h + ' h ' + m + ' min' : 'il y a ' + h + ' h'
})

function classePastille(stat) {
  if (jobsEnCours.value.includes(stat.typeJob)) return 'pastille-en-cours'
  if (stat.erreur) return 'pastille-erreur'
  if (stat.derniereExec) return 'pastille-ok'
  return 'pastille-vide'
}

function formaterDuree(ms) {
  if (ms == null) return ''
  return ms < 1000 ? ms + ' ms' : Math.floor(ms / 1000) + ' s'
}

async function charger() {
  try {
    const rep = await fetch('/api/admin/jobs', { signal: controleur.signal })
    if (!rep.ok) return
    const d = await rep.json()
    statsJobs.value = d.statsJobs ?? []
    jobsEnCours.value = d.jobsEnCours ?? []
    workerLastSuccess.value = d.workerLastSuccess ?? null
    if (jobsEnCours.value.length > 0) demarrerPolling()
  } catch (e) {
    if (e.name === 'AbortError') return
    afficherToast('Erreur lors du chargement des jobs.', 'erreur')
  } finally {
    chargement.value = false
  }
}

async function relancerFetches() {
  relancerEnCours.value = true
  try {
    const rep = await fetch('/api/admin/relancer', { method: 'POST', signal: controleur.signal })
    if (rep.ok) {
      afficherToast('Fetches relancés.', 'succes')
      demarrerPolling()
    } else {
      relancerEnCours.value = false
      afficherToast('Erreur — worker inaccessible.', 'erreur')
    }
  } catch (e) {
    if (e.name === 'AbortError') return
    relancerEnCours.value = false
    afficherToast('Erreur réseau.', 'erreur')
  }
}

function demarrerPolling() {
  arreterPolling()
  pollingInterval = setInterval(async () => {
    try {
      const rep = await fetch('/api/admin/jobs', { signal: controleur.signal })
      if (!rep.ok) return
      const d = await rep.json()
      statsJobs.value = d.statsJobs ?? statsJobs.value
      jobsEnCours.value = d.jobsEnCours ?? []
      if (jobsEnCours.value.length === 0) {
        arreterPolling()
        relancerEnCours.value = false
        relancerStatut.value = ''
      }
    } catch (e) {
      if (e.name === 'AbortError') return
      arreterPolling()
    }
  }, 3000)
}

function arreterPolling() {
  if (pollingInterval) {
    clearInterval(pollingInterval)
    pollingInterval = null
  }
}

onMounted(charger)
onUnmounted(() => {
  arreterPolling()
  controleur.abort()
})
</script>
