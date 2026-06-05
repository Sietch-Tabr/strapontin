<template>
  <div>
    <div class="page-header">
      <h1>Découverte</h1>
      <p class="page-subtitle">Explorez tous les concerts disponibles par département.</p>
    </div>

    <div v-if="donnees" class="decouverte-header">
      <span class="sync-info" v-if="donnees.derniereSyncRegion">
        Dernière synchro :
        <time :datetime="donnees.derniereSyncRegion">{{ formaterInstant(donnees.derniereSyncRegion) }}</time>
      </span>
      <span class="sync-info" v-else>Jamais synchronisé</span>
      <button type="button" class="btn btn-sm btn-secondary" @click="actualiser">
        <svg class="icon" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-refresh-cw" />
        </svg>
        Actualiser
      </button>
    </div>

    <div v-if="donnees" class="decouverte-depts">
      <span class="section-label">Départements :</span>
      <div class="chips">
        <a
          v-for="dept in donnees.deptsUtilisateur"
          :key="dept"
          href="#"
          :class="{ actif: deptsActifs.includes(dept) }"
          :aria-current="deptsActifs.includes(dept) ? 'true' : undefined"
          :title="syncDepts[dept] ? 'Dernière synchro : ' + syncDepts[dept] : 'Jamais synchronisé'"
          @click.prevent="toggleDept(dept)"
        >{{ dept }}</a>
      </div>
      <p v-if="donnees.deptsUtilisateur.length === 0" class="form-aide">
        Configurez vos départements dans
        <RouterLink to="/parametres#preferences">Paramètres</RouterLink> pour les pré-sélectionner.
      </p>
    </div>

    <div class="filtres-toggle">
      <button
        type="button"
        class="btn btn-secondary btn-sm"
        :aria-expanded="filtresVisibles ? 'true' : 'false'"
        @click="filtresVisibles = !filtresVisibles"
      >
        <svg class="icon" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-sliders-horizontal" />
        </svg>
        <span>Filtres{{ nbFiltresActifs > 0 ? ' · ' + nbFiltresActifs : '' }}</span>
      </button>
    </div>

    <form class="filtres-form" :class="{ visible: filtresVisibles }" @submit.prevent="appliquerFiltres">
      <div class="filtres-ligne">
        <input
          type="text"
          v-model="qLocal"
          placeholder="Rechercher un artiste ou une salle…"
          aria-label="Rechercher un artiste ou une salle"
          autocomplete="off"
        />

        <label for="filtre-mois-dec" class="sr-only">Filtrer par mois</label>
        <select id="filtre-mois-dec" v-model="moisLocal" @change="appliquerFiltres">
          <option value="">Tous les mois</option>
          <option v-for="m in donnees?.moisDisponibles ?? []" :key="m" :value="m">{{ m }}</option>
        </select>

        <label for="filtre-genre-dec" class="sr-only">Filtrer par genre</label>
        <select id="filtre-genre-dec" v-model="genreLocal" @change="appliquerFiltres">
          <option value="">Tous les genres</option>
          <option v-for="g in donnees?.genresDisponibles ?? []" :key="g" :value="g">{{ g }}</option>
        </select>

        <label for="filtre-classification-dec" class="sr-only">Filtrer par classification</label>
        <select id="filtre-classification-dec" v-model="classificationLocal" @change="appliquerFiltres">
          <option value="">Toutes classifications</option>
          <option v-for="c in donnees?.classificationsDisponibles ?? []" :key="c" :value="c">{{ c }}</option>
        </select>

        <label for="filtre-tri-dec" class="sr-only">Trier les résultats</label>
        <select id="filtre-tri-dec" v-model="triLocal" @change="appliquerFiltres">
          <option value="date">Par date</option>
          <option value="nouveaute">Par nouveauté</option>
        </select>

        <button type="submit" class="btn btn-secondary">Filtrer</button>
        <RouterLink to="/decouverte" class="btn btn-texte">Réinitialiser</RouterLink>
      </div>
    </form>

    <p
      v-if="donnees"
      class="page-subtitle"
      aria-live="polite"
      aria-atomic="true"
    >
      {{ donnees.totalConcerts }} concert{{ donnees.totalConcerts !== 1 ? 's' : '' }} trouvé{{ donnees.totalConcerts !== 1 ? 's' : '' }}
    </p>

    <div v-if="!donnees || donnees.concerts.length === 0" class="etat-vide">
      <p class="etat-vide-titre">Aucun concert avec ces filtres.</p>
      <p v-if="deptsActifs.length === 0" class="etat-vide-texte">
        Sélectionnez un département ci-dessus ou configurez vos départements dans
        <RouterLink to="/parametres#preferences">Paramètres</RouterLink>.
      </p>
      <p v-else class="etat-vide-texte">
        <RouterLink to="/decouverte">Réinitialiser les filtres</RouterLink>
      </p>
    </div>

    <div v-if="donnees && donnees.concerts.length > 0" class="decouverte-conteneur">
      <div class="table-wrapper">
        <table>
          <thead>
            <tr>
              <th scope="col">Artiste</th>
              <th scope="col">Date</th>
              <th scope="col">Où</th>
              <th scope="col">Genre</th>
              <th scope="col"><span class="sr-only">Lien</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="concert in donnees.concerts" :key="concert.id">
              <td data-label="Artiste">
                <span class="concert-artiste">{{ concert.artiste }}</span>
                <span v-if="concert.statut === 'cancelled'" class="badge badge-annule">Annulé</span>
                <span v-if="concert.statut === 'rescheduled' || concert.statut === 'postponed'" class="badge badge-reporte">Reporté</span>
                <span v-if="concert.statut === 'offsale'" class="badge badge-complet">Complet</span>
              </td>
              <td data-label="Date">
                <time class="concert-date" :datetime="concert.date">{{ formaterAvecHeure(concert.date, concert.heure) }}</time>
              </td>
              <td data-label="Où" :class="{ 'cel-vide': !concert.salle && !concert.ville }">
                <span v-if="concert.salle">{{ concert.salle }}</span>
                <span v-if="concert.salle && concert.ville" class="concert-separateur"> · </span>
                <span v-if="concert.ville" class="concert-ville">{{ concert.ville }}</span>
              </td>
              <td data-label="Genre" :class="{ 'cel-vide': !concert.genre }">{{ concert.genre }}</td>
              <td data-label="" :class="{ 'cel-vide': !concert.url }">
                <a
                  v-if="concert.url"
                  :href="concert.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="btn btn-xs btn-secondary"
                  aria-label="Acheter des billets (ouvre dans un nouvel onglet)"
                >
                  <svg class="icon" aria-hidden="true">
                    <use href="/icons/lucide-sprite.svg#icon-ticket" />
                  </svg>
                </a>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="pagination" v-if="donnees && donnees.totalPages > 1">
      <button
        v-if="donnees.page > 0"
        type="button"
        class="btn btn-secondary"
        @click="changerPage(donnees.page - 1)"
      >Précédent</button>
      <span>Page {{ donnees.page + 1 }} / {{ donnees.totalPages }}</span>
      <button
        v-if="donnees.page + 1 < donnees.totalPages"
        type="button"
        class="btn btn-secondary"
        @click="changerPage(donnees.page + 1)"
      >Suivant</button>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'DecouvertePage' })

import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { formaterAvecHeure, formaterInstant } from '../composables/useDates.js'
import { useToasts } from '../composables/useToasts.js'

const route = useRoute()
const router = useRouter()
const { afficherToast } = useToasts()

const donnees = ref(null)
const filtresVisibles = ref(false)

const qLocal = ref('')
const moisLocal = ref('')
const genreLocal = ref('')
const classificationLocal = ref('')
const triLocal = ref('date')

const deptsActifs = computed(() => {
  const d = route.query.dept
  if (!d) return []
  return Array.isArray(d) ? d : [d]
})

const syncDepts = computed(() => {
  if (!donnees.value?.syncParDept) return {}
  return Object.fromEntries(
    donnees.value.syncParDept
      .filter(s => s.derniereExec)
      .map(s => [s.dept, formaterInstant(s.derniereExec)])
  )
})

const nbFiltresActifs = computed(() => {
  let n = 0
  if (route.query.q) n++
  if (route.query.mois) n++
  if (route.query.genre) n++
  if (route.query.classification) n++
  return n
})

function syncFiltresLocaux() {
  qLocal.value = route.query.q ?? ''
  moisLocal.value = route.query.mois ?? ''
  genreLocal.value = route.query.genre ?? ''
  classificationLocal.value = route.query.classification ?? ''
  triLocal.value = route.query.tri ?? 'date'
}

async function charger() {
  try {
    const params = new URLSearchParams()
    deptsActifs.value.forEach(d => params.append('dept', d))
    if (route.query.q) params.set('q', route.query.q)
    if (route.query.mois) params.set('mois', route.query.mois)
    if (route.query.genre) params.set('genre', route.query.genre)
    if (route.query.classification) params.set('classification', route.query.classification)
    if (triLocal.value && triLocal.value !== 'date') params.set('tri', triLocal.value)
    if (route.query.page) params.set('page', route.query.page)
    const rep = await fetch('/api/decouverte?' + params)
    if (!rep.ok) throw new Error()
    donnees.value = await rep.json()
  } catch {
    afficherToast('Impossible de charger les concerts.', 'erreur')
  }
}

function appliquerFiltres() {
  const q = { ...route.query }
  delete q.page
  if (qLocal.value) q.q = qLocal.value; else delete q.q
  if (moisLocal.value) q.mois = moisLocal.value; else delete q.mois
  if (genreLocal.value) q.genre = genreLocal.value; else delete q.genre
  if (classificationLocal.value) q.classification = classificationLocal.value; else delete q.classification
  if (triLocal.value && triLocal.value !== 'date') q.tri = triLocal.value; else delete q.tri
  router.push({ query: q })
}

function toggleDept(dept) {
  const q = { ...route.query }
  delete q.page
  const actuel = deptsActifs.value
  const nouveau = actuel.includes(dept)
    ? actuel.filter(d => d !== dept)
    : [...actuel, dept]
  if (nouveau.length > 0) {
    q.dept = nouveau
  } else {
    delete q.dept
  }
  router.push({ query: q })
}

function changerPage(page) {
  router.push({ query: { ...route.query, page: String(page) } })
}

async function actualiser() {
  try {
    const rep = await fetch('/api/decouverte/fetcher', { method: 'POST' })
    if (!rep.ok) throw new Error()
    afficherToast('Synchronisation déclenchée — résultats mis à jour dans quelques minutes.', 'succes')
  } catch {
    afficherToast('Impossible de déclencher la synchronisation.', 'erreur')
  }
}

watch(() => route.query, () => {
  syncFiltresLocaux()
  charger()
}, { deep: true })

onMounted(() => {
  syncFiltresLocaux()
  charger()
})
</script>
