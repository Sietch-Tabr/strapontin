<template>
  <div>
    <div class="page-entete">
      <h1>Mes spectacles</h1>
      <p v-if="donnees" class="page-sous-titre">
        {{ donnees.totalConcerts }} concert{{ donnees.totalConcerts !== 1 ? 's' : '' }} à venir
      </p>
    </div>

    <div class="chips">
      <a
        href="#"
        :class="{ actif: sourceArtistesActive }"
        :aria-current="sourceArtistesActive ? 'true' : undefined"
        @click.prevent="toggleSource('artistes')"
      >Mes artistes</a>
      <a
        href="#"
        :class="{ actif: sourceSallesActive }"
        :aria-current="sourceSallesActive ? 'true' : undefined"
        @click.prevent="toggleSource('salles')"
      >Mes salles</a>
      <a
        href="#"
        :class="{ actif: quinzaineActive }"
        :aria-current="quinzaineActive ? 'true' : undefined"
        @click.prevent="toggleQuinzaine"
      >15 prochains jours</a>
      <a
        href="#"
        :class="{ actif: masquesActive }"
        :aria-current="masquesActive ? 'true' : undefined"
        @click.prevent="toggleMasques"
      >Masqués</a>
      <a
        href="#"
        :class="{ actif: indisponiblesActive }"
        :aria-current="indisponiblesActive ? 'true' : undefined"
        @click.prevent="toggleIndisponibles"
      >Indisponibles</a>
    </div>

    <div
      v-if="quinzaineActive && !!route.query.mois"
      class="alerte alerte-info"
      role="status"
    >
      Le filtre « 15 prochains jours » et le filtre par mois sont actifs
      simultanément — les résultats affichés correspondent à leur intersection.
    </div>

    <div class="filtres-bascule">
      <button
        type="button"
        class="bouton bouton-secondaire bouton-petit"
        id="btn-filtres-vue"
        :aria-expanded="filtresVisibles ? 'true' : 'false'"
        @click="filtresVisibles = !filtresVisibles"
      >
        <svg class="icone" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-sliders-horizontal" />
        </svg>
        <span>Filtres{{ nbFiltresActifs > 0 ? ' · ' + nbFiltresActifs : '' }}</span>
      </button>
    </div>

    <form
      class="filtres-form"
      :class="{ visible: filtresVisibles }"
      @submit.prevent="appliquerFiltres"
    >
      <div class="filtres-ligne">
        <input
          type="text"
          v-model="qLocal"
          placeholder="Rechercher un artiste…"
          aria-label="Rechercher un artiste"
          autocomplete="off"
        />

        <label for="filtre-mois" class="lecteur-ecran">Filtrer par mois</label>
        <select id="filtre-mois" v-model="moisLocal" @change="appliquerFiltres">
          <option value="">Tous les mois</option>
          <option v-for="m in donnees?.moisDisponibles ?? []" :key="m" :value="m">{{ m }}</option>
        </select>

        <label for="filtre-genre" class="lecteur-ecran">Filtrer par genre</label>
        <select id="filtre-genre" v-model="genreLocal" @change="appliquerFiltres">
          <option value="">Tous les genres</option>
          <option v-for="g in donnees?.genresDisponibles ?? []" :key="g" :value="g">{{ g }}</option>
        </select>

        <label for="filtre-classification" class="lecteur-ecran">Filtrer par classification</label>
        <select id="filtre-classification" v-model="classificationLocal" @change="appliquerFiltres">
          <option value="">Toutes classifications</option>
          <option v-for="c in donnees?.classificationsDisponibles ?? []" :key="c" :value="c">{{ c }}</option>
        </select>

        <label for="filtre-tri" class="lecteur-ecran">Trier les résultats</label>
        <select id="filtre-tri" v-model="triLocal" @change="appliquerFiltres">
          <option value="date">Par date</option>
          <option value="nouveaute">Par nouveauté</option>
        </select>

        <button type="submit" class="bouton bouton-secondaire">Filtrer</button>
        <RouterLink to="/spectacles" class="bouton bouton-texte">Réinitialiser</RouterLink>
      </div>
    </form>

    <EtatChargement v-if="chargement && !donnees" />

    <div v-if="donnees && donnees.concerts.length === 0" class="etat-vide">
      <p v-if="!donnees.aSuivi" class="etat-vide-titre">Aucun artiste ni salle suivi.</p>
      <p v-else class="etat-vide-titre">Aucun concert avec ces filtres.</p>
      <p v-if="!donnees.aSuivi" class="etat-vide-texte">
        <RouterLink to="/artistes">Ajouter des artistes</RouterLink> ou
        <RouterLink to="/salles">des salles</RouterLink> pour commencer.
      </p>
      <p v-else class="etat-vide-texte">
        <RouterLink to="/spectacles">Réinitialiser les filtres</RouterLink>
      </p>
    </div>

    <div v-if="donnees && donnees.concerts.length > 0" class="spectacles-conteneur">
      <div class="table-conteneur">
        <table>
          <thead>
            <tr>
              <th scope="col">Artiste</th>
              <th scope="col">Date</th>
              <th scope="col">Où</th>
              <th scope="col"><span class="lecteur-ecran">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="concert in donnees.concerts" :key="concert.id">
              <td data-label="Artiste">
                <span class="concert-artiste">{{ concert.artiste }}</span>
                <span v-if="concert.estNew" class="badge badge-nouveau">Nouveau</span>
                <span v-if="concert.statut === 'cancelled'" class="badge badge-annule">Annulé</span>
                <span v-if="concert.statut === 'rescheduled' || concert.statut === 'postponed'" class="badge badge-reporte">Reporté</span>
                <span v-if="concert.statut === 'offsale'" class="badge badge-complet">Complet</span>
                <span v-if="concert.statut === 'presale'" class="badge badge-prevente">{{ concert.presaleDebut ? 'Prévente le ' + formaterPresale(concert.presaleDebut) : 'Prévente' }}</span>
                <span v-if="concert.informationNonConfirmee" class="badge badge-a-verifier" title="Ce concert n'a plus été retrouvé lors des dernières vérifications automatiques — à confirmer avant de se déplacer.">À vérifier</span>
              </td>
              <td data-label="Date">
                <time class="concert-date" :datetime="concert.date">{{ formaterAvecHeure(concert.date, concert.heure) }}</time>
              </td>
              <td data-label="Où" :class="{ 'cel-vide': !concert.salle && !concert.ville }">
                <span v-if="concert.salle">{{ concert.salle }}</span>
                <span v-if="concert.salle && concert.ville" class="concert-separateur"> · </span>
                <span v-if="concert.ville" class="concert-ville">{{ concert.ville }}</span>
              </td>
              <td data-label="" class="actions-cellule">
                <a
                  v-if="concert.url"
                  :href="concert.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="bouton bouton-tres-petit bouton-secondaire"
                  aria-label="Acheter des billets (ouvre dans un nouvel onglet)"
                >
                  <svg class="icone" aria-hidden="true">
                    <use href="/icons/lucide-sprite.svg#icon-ticket" />
                  </svg>
                </a>
                <button
                  v-if="masquesActive"
                  type="button"
                  class="bouton bouton-petit bouton-texte"
                  @click="demasquerConcert(concert)"
                >Réafficher</button>
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
        class="bouton bouton-secondaire"
        @click="changerPage(donnees.page - 1)"
      >Précédent</button>
      <span>Page {{ donnees.page + 1 }} / {{ donnees.totalPages }}</span>
      <button
        v-if="donnees.page + 1 < donnees.totalPages"
        type="button"
        class="bouton bouton-secondaire"
        @click="changerPage(donnees.page + 1)"
      >Suivant</button>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'SpectaclesPage' })

import { ref, shallowRef, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { formaterAvecHeure } from '../composables/useDates.js'
import { useToasts } from '../composables/useToasts.js'
import EtatChargement from '../components/EtatChargement.vue'

const route = useRoute()
const router = useRouter()
const { afficherToast } = useToasts()

const chargement = ref(false)
const donnees = shallowRef(null)
const filtresVisibles = ref(false)

const qLocal = ref('')
const moisLocal = ref('')
const genreLocal = ref('')
const classificationLocal = ref('')
const triLocal = ref('date')

const sources = computed(() => {
  const s = route.query.source
  if (!s) return []
  return Array.isArray(s) ? s : [s]
})

const sourceArtistesActive = computed(() => sources.value.includes('artistes') && !sources.value.includes('salles'))
const sourceSallesActive = computed(() => !sources.value.includes('artistes') && sources.value.includes('salles'))
const quinzaineActive = computed(() => route.query.quinzaine === 'true')
const masquesActive = computed(() => route.query.masques === 'true')
const indisponiblesActive = computed(() => route.query.indisponibles === '1')

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

const controleurComposant = new AbortController()

// Un nouvel appel (changement de filtre pendant qu'un chargement précédent est en cours)
// annule le précédent plutôt que de laisser deux requêtes concurrentes risquer d'appliquer
// leurs résultats dans le désordre.
let controleurActuel = null

async function charger() {
  controleurActuel?.abort()
  const controleur = new AbortController()
  controleurActuel = controleur
  chargement.value = true
  try {
    const params = new URLSearchParams()
    if (route.query.q) params.set('q', route.query.q)
    if (route.query.mois) params.set('mois', route.query.mois)
    if (route.query.genre) params.set('genre', route.query.genre)
    if (route.query.classification) params.set('classification', route.query.classification)
    if (quinzaineActive.value) params.set('quinzaine', 'true')
    if (triLocal.value && triLocal.value !== 'date') params.set('tri', triLocal.value)
    if (masquesActive.value) params.set('masques', 'true')
    sources.value.forEach(s => params.append('source', s))
    if (indisponiblesActive.value) params.set('indisponibles', '1')
    if (route.query.page) params.set('page', route.query.page)
    const rep = await fetch('/api/spectacles?' + params, { signal: controleur.signal })
    if (!rep.ok) throw new Error()
    donnees.value = await rep.json()
  } catch (e) {
    if (e.name === 'AbortError') return
    afficherToast('Impossible de charger les spectacles.', 'erreur')
  } finally {
    if (controleurActuel === controleur) chargement.value = false
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

function toggleSource(source) {
  const q = { ...route.query }
  delete q.page
  const active = source === 'artistes' ? sourceArtistesActive.value : sourceSallesActive.value
  if (active) {
    delete q.source
  } else {
    q.source = source
  }
  router.push({ query: q })
}

function toggleQuinzaine() {
  const q = { ...route.query }
  delete q.page
  if (quinzaineActive.value) {
    delete q.quinzaine
  } else {
    q.quinzaine = 'true'
  }
  router.push({ query: q })
}

function toggleMasques() {
  const q = { ...route.query }
  delete q.page
  if (masquesActive.value) {
    delete q.masques
  } else {
    q.masques = 'true'
  }
  router.push({ query: q })
}

function toggleIndisponibles() {
  const q = { ...route.query }
  delete q.page
  if (indisponiblesActive.value) {
    delete q.indisponibles
  } else {
    q.indisponibles = '1'
  }
  router.push({ query: q })
}

function changerPage(page) {
  router.push({ query: { ...route.query, page: String(page) } })
}

async function demasquerConcert(concert) {
  try {
    const rep = await fetch(`/api/spectacles/matches/${concert.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ masquer: false }),
      signal: controleurComposant.signal,
    })
    if (!rep.ok) throw new Error()
    donnees.value.concerts.splice(donnees.value.concerts.indexOf(concert), 1)
    donnees.value.totalConcerts--
  } catch (e) {
    if (e.name === 'AbortError') return
    afficherToast('Impossible de réafficher ce concert.', 'erreur')
  }
}

function formaterPresale(instantIso) {
  if (!instantIso) return ''
  const d = new Date(instantIso)
  return String(d.getDate()).padStart(2, '0') + '/' + String(d.getMonth() + 1).padStart(2, '0')
}

watch(() => route.query, () => {
  syncFiltresLocaux()
  charger()
}, { deep: true })

onMounted(() => {
  syncFiltresLocaux()
  charger()
})

onUnmounted(() => {
  controleurComposant.abort()
  controleurActuel?.abort()
})
</script>
