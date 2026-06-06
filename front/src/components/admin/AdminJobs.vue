<template>
  <section class="admin-section" id="jobs">

    <div class="param-bloc">
      <h3 class="param-label">Quota Ticketmaster API</h3>
      <div v-if="quotaTM" class="quota-tm-carte">
        <div class="quota-tm-chiffres">
          <span>
            <strong>{{ quotaTM.dispo }}</strong>
            <span class="quota-tm-sep">{{ ' / ' + quotaTM.total }}</span>
            <span class="quota-tm-unite">appels restants</span>
          </span>
          <span class="quota-tm-pct">{{ pctQuota }}%</span>
        </div>
        <div
          class="quota-tm-barre-fond"
          role="progressbar"
          aria-valuemin="0"
          :aria-valuenow="quotaTM.dispo"
          :aria-valuemax="quotaTM.total"
          aria-label="Quota Ticketmaster"
        >
          <div
            class="quota-tm-barre-remplissage"
            :class="classeBarre"
            :style="{ width: pctQuota + '%' }"
          ></div>
        </div>
        <p
          v-if="etatQuota !== 'ok'"
          class="badge-quota"
          :class="etatQuota === 'critique' ? 'badge-quota-critique' : 'badge-quota-attention'"
        >
          {{ etatQuota === 'critique' ? 'Quota critique (moins de 10 %)' : 'Quota faible (moins de 20 %)' }}
        </p>
        <p v-if="quotaTM.reset > 0" class="param-aide">
          Remise à zéro le {{ formaterTs(quotaTM.reset) }}
        </p>
        <p v-if="quotaTM.misAJour" class="param-aide">
          Actualisé le {{ formaterInstant(quotaTM.misAJour) }}
        </p>
      </div>
      <p v-else class="etat-vide-texte">Disponible après le prochain fetch Ticketmaster.</p>
    </div>

    <div class="param-bloc">
      <h3 class="param-label">Actualisations</h3>
      <div v-if="statsJobs.length" class="jobs-resume">
        <span v-if="nbJobsOk > 0" class="jobs-resume-ok"><strong>{{ nbJobsOk }}</strong> OK</span>
        <span v-if="nbJobsErreur > 0" class="jobs-resume-erreur"><strong>{{ nbJobsErreur }}</strong> en erreur</span>
      </div>
      <div v-if="statsJobs.length" class="jobs-liste">
        <div v-for="stat in statsJobs" :key="stat.typeJob" class="job-carte">
          <div class="job-carte-entete">
            <p class="job-carte-label">{{ LABELS_JOBS[stat.typeJob] ?? stat.typeJob }}</p>
            <span
              v-if="jobsEnCours.includes(stat.typeJob)"
              class="job-en-cours"
              aria-busy="true"
              aria-label="En cours"
            >
              <svg class="spinner" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M21 12a9 9 0 1 1-6.219-8.56" />
              </svg>
              En cours
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
      <p v-else class="etat-vide-texte">Aucun job exécuté pour l'instant.</p>
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
        <span v-if="relancerStatut" id="relancer-statut" class="relancer-statut" aria-live="polite">{{ relancerStatut }}</span>
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
import { formaterInstant, formaterTs } from '../../composables/useDates.js'

const { afficherToast } = useToasts()

const LABELS_JOBS = {
  'fetch:artistes:batch': 'Artistes suivis',
  'fetch:region':         'Concerts par région',
  'notifier:digest':      'Notifications digest',
  'purger:concerts':      'Purge concerts',
  'recalculer:matches':   'Recalcul matchs',
  'fetch:salle':          'Salle',
}

const statsJobs = shallowRef([])
const quotaTM = shallowRef(null)
const jobsEnCours = shallowRef([])
const workerLastSuccess = ref(null)
const etatQuota = ref('ok')
const relancerEnCours = ref(false)
const relancerStatut = ref('')
let pollingInterval = null

const pctQuota = computed(() => {
  if (!quotaTM.value || quotaTM.value.total === 0) return 0
  return Math.round((quotaTM.value.dispo * 100) / quotaTM.value.total)
})

const classeBarre = computed(() => {
  const p = pctQuota.value
  if (p > 30) return 'barre-ok'
  if (p > 10) return 'barre-warn'
  return 'barre-danger'
})

const nbJobsOk = computed(() => statsJobs.value.filter((j) => !j.erreur).length)
const nbJobsErreur = computed(() => statsJobs.value.filter((j) => j.erreur).length)

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

function formaterDuree(ms) {
  if (ms == null) return ''
  return ms < 1000 ? ms + ' ms' : Math.floor(ms / 1000) + ' s'
}

async function charger() {
  try {
    const rep = await fetch('/api/admin/jobs')
    if (!rep.ok) return
    const d = await rep.json()
    statsJobs.value = d.statsJobs ?? []
    quotaTM.value = d.quotaTM ?? null
    jobsEnCours.value = d.jobsEnCours ?? []
    workerLastSuccess.value = d.workerLastSuccess ?? null
    etatQuota.value = d.etatQuota ?? 'ok'
    if (jobsEnCours.value.length > 0) demarrerPolling()
  } catch (_) {
    afficherToast('Erreur lors du chargement des jobs.', 'erreur')
  }
}

async function relancerFetches() {
  relancerEnCours.value = true
  relancerStatut.value = 'Fetches en cours…'
  try {
    const rep = await fetch('/api/admin/relancer', { method: 'POST' })
    if (rep.ok) {
      demarrerPolling()
    } else {
      relancerEnCours.value = false
      relancerStatut.value = 'Erreur — worker inaccessible.'
    }
  } catch (_) {
    relancerEnCours.value = false
    relancerStatut.value = 'Erreur réseau.'
  }
}

function demarrerPolling() {
  arreterPolling()
  pollingInterval = setInterval(async () => {
    try {
      const rep = await fetch('/api/admin/jobs')
      if (!rep.ok) return
      const d = await rep.json()
      statsJobs.value = d.statsJobs ?? statsJobs.value
      jobsEnCours.value = d.jobsEnCours ?? []
      if (jobsEnCours.value.length === 0) {
        arreterPolling()
        relancerEnCours.value = false
        relancerStatut.value = ''
      }
    } catch (_) {
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
onUnmounted(arreterPolling)
</script>
