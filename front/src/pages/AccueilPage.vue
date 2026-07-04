<template>
  <div>
    <div class="page-header">
      <h1>Mes prochains concerts</h1>
    </div>

    <EtatChargement v-if="chargement" />

    <template v-else-if="donnees">
      <div class="chips">
        <a
          href="#"
          :class="{ actif: filtreNouveau }"
          :aria-current="filtreNouveau ? 'true' : undefined"
          @click.prevent="basculerFiltre('nouveau')"
        >Nouveau</a>
        <a
          href="#"
          :class="{ actif: filtreIndisponibles }"
          :aria-current="filtreIndisponibles ? 'true' : undefined"
          @click.prevent="basculerFiltre('indisponibles')"
        >Indisponibles</a>
        <a
          href="#"
          :class="{ actif: filtreMasques }"
          :aria-current="filtreMasques ? 'true' : undefined"
          @click.prevent="basculerFiltre('masques')"
        >Masqués</a>
      </div>

      <div v-if="donnees.artistesGroupes.length === 0" class="etat-vide">
        <template v-if="donnees.nbArtistes === 0 && donnees.nbSalles === 0">
          <p class="etat-vide-titre">Bienvenue sur Strapontin !</p>
          <p class="etat-vide-texte">
            Ajoutez des artistes ou des salles pour découvrir vos prochains concerts.
          </p>
          <div class="etat-vide-actions">
            <RouterLink to="/artistes" class="btn btn-primary">Ajouter des artistes</RouterLink>
            <RouterLink to="/salles" class="btn btn-secondary">Ajouter des salles</RouterLink>
          </div>
        </template>
        <template v-else>
          <template v-if="!filtreIndisponibles">
            <p class="etat-vide-titre">Aucun concert avec ces filtres.</p>
            <p class="etat-vide-texte">Essayez d'inclure les concerts indisponibles ci-dessus.</p>
          </template>
          <template v-else>
            <p class="etat-vide-titre">Aucun concert pour l'instant.</p>
            <p class="etat-vide-texte">
              Vos artistes et salles sont suivis — les concerts apparaîtront au prochain rafraîchissement.
            </p>
          </template>
        </template>
      </div>

      <div v-else class="concert-groupes">
        <div
          v-for="groupe in donnees.artistesGroupes"
          :key="groupe.artisteNorm"
          :id="'artiste-' + groupe.artisteNorm"
          class="concert-groupe"
        >
          <div class="groupe-header">
            <span class="groupe-artiste">{{ groupe.artiste }}</span>
            <span v-if="groupe.estTournee" class="badge badge-tournee">
              Tournée · {{ groupe.concerts.length }} dates
            </span>
            <span v-if="groupe.estNew" class="badge badge-nouveau">Nouveau</span>
            <button
              v-if="!filtreMasques"
              type="button"
              class="btn-texte"
              :aria-label="'Masquer tous les concerts de ' + groupe.artiste"
              @click="masquerGroupe(groupe)"
            >Masquer</button>
            <button
              v-else
              type="button"
              class="btn-texte"
              :aria-label="'Réafficher tous les concerts de ' + groupe.artiste"
              @click="demasquerGroupe(groupe)"
            >Réafficher</button>
          </div>

          <div class="groupe-dates">
            <div
              v-for="concert in groupe.concerts"
              :key="concert.id"
              class="concert-ligne"
            >
              <div class="concert-info">
                <p :class="groupe.estTournee ? 'concert-date-principale' : 'concert-date-secondaire'">
                  <time :datetime="concert.date">{{ formaterAvecHeure(concert.date, concert.heure) }}</time>
                  <span v-if="concert.estNew" class="badge badge-nouveau">Nouveau</span>
                  <span v-if="concert.statut === 'cancelled'" class="badge badge-annule">Annulé</span>
                  <span v-if="concert.statut === 'rescheduled' || concert.statut === 'postponed'" class="badge badge-reporte">Reporté</span>
                  <span v-if="concert.statut === 'offsale'" class="badge badge-complet">Complet</span>
                  <span v-if="concert.statut === 'presale'" class="badge badge-prevente">Prévente</span>
                </p>
                <p class="concert-lieu">
                  {{ concert.salle }}{{ concert.ville ? ' · ' + concert.ville : '' }}
                </p>
              </div>
              <div class="concert-actions">
                <a
                  v-if="concert.url"
                  :href="concert.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="btn btn-xs btn-secondary"
                >Billets</a>
                <button
                  v-if="filtreMasques"
                  type="button"
                  class="btn-texte btn-sm"
                  aria-label="Réafficher ce concert"
                  @click="demasquerConcert(concert, groupe)"
                >Réafficher</button>
                <button
                  v-else
                  type="button"
                  class="btn-texte btn-sm"
                  aria-label="Masquer ce concert"
                  @click="masquerConcert(concert, groupe)"
                >Masquer</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
defineOptions({ name: 'AccueilPage' })

import { ref, shallowRef, computed, watch, onMounted, nextTick } from 'vue'
import EtatChargement from '../components/EtatChargement.vue'
import { useRoute, useRouter } from 'vue-router'
import { formaterAvecHeure } from '../composables/useDates.js'
import { useToasts } from '../composables/useToasts.js'
import { useSession } from '../composables/useSession.js'

const route = useRoute()
const router = useRouter()
const { afficherToast } = useToasts()
const { chargerSession } = useSession()

const donnees = shallowRef(null)
const chargement = ref(false)

const filtreNouveau = computed(() => route.query.nouveau === 'true')
const filtreIndisponibles = computed(() => route.query.indisponibles === '1')
const filtreMasques = computed(() => route.query.masques === 'true')

async function charger() {
  chargement.value = true
  try {
    const params = new URLSearchParams()
    if (filtreNouveau.value) params.set('nouveau', 'true')
    if (filtreIndisponibles.value) params.set('indisponibles', '1')
    if (filtreMasques.value) params.set('masques', 'true')
    const rep = await fetch('/api/accueil?' + params)
    if (!rep.ok) throw new Error()
    donnees.value = await rep.json()
  } catch {
    afficherToast('Impossible de charger les concerts.', 'erreur')
  } finally {
    chargement.value = false
  }
}

function basculerFiltre(filtre) {
  const q = { ...route.query }
  if (filtre === 'nouveau') {
    q.nouveau === 'true' ? delete q.nouveau : (q.nouveau = 'true')
  } else if (filtre === 'indisponibles') {
    q.indisponibles === '1' ? delete q.indisponibles : (q.indisponibles = '1')
  } else if (filtre === 'masques') {
    q.masques === 'true' ? delete q.masques : (q.masques = 'true')
  }
  router.push({ query: q })
}

async function masquerConcert(concert, groupe) {
  try {
    const rep = await fetch(`/api/spectacles/matches/${concert.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ masquer: true }),
    })
    if (!rep.ok) throw new Error()
    groupe.concerts.splice(groupe.concerts.indexOf(concert), 1)
    if (groupe.concerts.length === 0) {
      donnees.value.artistesGroupes.splice(donnees.value.artistesGroupes.indexOf(groupe), 1)
    }
  } catch {
    afficherToast('Impossible de masquer ce concert.', 'erreur')
  }
}

async function demasquerConcert(concert, groupe) {
  try {
    const rep = await fetch(`/api/spectacles/matches/${concert.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ masquer: false }),
    })
    if (!rep.ok) throw new Error()
    groupe.concerts.splice(groupe.concerts.indexOf(concert), 1)
    if (groupe.concerts.length === 0) {
      donnees.value.artistesGroupes.splice(donnees.value.artistesGroupes.indexOf(groupe), 1)
    }
  } catch {
    afficherToast('Impossible de réafficher ce concert.', 'erreur')
  }
}

async function masquerGroupe(groupe) {
  try {
    const rep = await fetch('/api/spectacles/masquer-artiste', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ artisteNorm: groupe.artisteNorm }),
    })
    if (!rep.ok) throw new Error()
    donnees.value.artistesGroupes.splice(donnees.value.artistesGroupes.indexOf(groupe), 1)
  } catch {
    afficherToast('Impossible de masquer cet artiste.', 'erreur')
  }
}

async function demasquerGroupe(groupe) {
  try {
    const rep = await fetch('/api/spectacles/demasquer-artiste', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ artisteNorm: groupe.artisteNorm }),
    })
    if (!rep.ok) throw new Error()
    donnees.value.artistesGroupes.splice(donnees.value.artistesGroupes.indexOf(groupe), 1)
  } catch {
    afficherToast('Impossible de réafficher cet artiste.', 'erreur')
  }
}

async function scrollVersAncre() {
  const ancre = route.query.ancre
  if (!ancre) return
  await nextTick()
  document.getElementById(String(ancre))?.scrollIntoView({ behavior: 'smooth' })
  // #801 : retire l'ancre de l'URL une fois le scroll effectué — sinon basculerFiltre() la
  // conserve par spread et chaque changement de filtre suivant re-déclenche le scroll forcé.
  const q = { ...route.query }
  delete q.ancre
  router.replace({ query: q })
}

watch(() => route.query, async () => {
  await charger()
  await scrollVersAncre()
}, { deep: true })

onMounted(async () => {
  await fetch('/api/accueil/marquer-vus', { method: 'POST' })
  chargerSession()
  await charger()
  await scrollVersAncre()
})
</script>
