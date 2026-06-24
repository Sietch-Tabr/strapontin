<template>
  <section class="admin-section" id="sources">

    <EtatChargement v-if="chargement" />
    <template v-else>

    <div class="param-bloc">
      <h3 class="param-label">Sources de données</h3>
      <div class="sources-grille">

        <div v-for="source in sources" :key="source.id" :class="['source-carte', { 'source-carte-large': source.id === 'ticketmaster' }]">
          <p class="source-nom">{{ source.nom }}</p>
          <span class="badge-source" :class="'badge-source-' + source.type">
            {{ source.type === 'concerts' ? 'Concerts' : 'Métadonnées' }}
          </span>

          <template v-if="source.id === 'ticketmaster'">
            <div v-if="source.quotaTM" class="quota-tm-carte">
              <div class="quota-tm-chiffres">
                <span>
                  <strong>{{ source.quotaTM.dispo }}</strong>
                  <span class="quota-tm-sep">{{ ' / ' + source.quotaTM.total }}</span>
                  <span class="quota-tm-unite">appels restants</span>
                </span>
                <span class="quota-tm-pct">{{ pctQuota(source) }}%</span>
              </div>
              <div
                class="quota-tm-barre-fond"
                role="progressbar"
                aria-valuemin="0"
                :aria-valuenow="source.quotaTM.dispo"
                :aria-valuemax="source.quotaTM.total"
                aria-label="Quota Ticketmaster"
              >
                <div
                  class="quota-tm-barre-remplissage"
                  :class="classeBarre(source)"
                  :style="{ width: pctQuota(source) + '%' }"
                ></div>
              </div>
              <p
                v-if="source.etatQuota !== 'ok'"
                class="badge-quota"
                :class="source.etatQuota === 'critique' ? 'badge-quota-critique' : 'badge-quota-attention'"
              >
                {{ source.etatQuota === 'critique' ? 'Quota critique (moins de 10 %)' : 'Quota faible (moins de 20 %)' }}
              </p>
              <p v-if="source.quotaTM.reset > 0" class="param-aide">
                Remise à zéro le {{ formaterTs(source.quotaTM.reset) }}
              </p>
              <p v-if="source.quotaTM.misAJour" class="param-aide">
                Actualisé le {{ formaterInstant(source.quotaTM.misAJour) }}
              </p>
            </div>
            <p v-else class="etat-vide-texte">Quota non disponible (aucun fetch effectué).</p>
          </template>

          <template v-else-if="source.id === 'digitick'">
            <p class="source-carte-detail">
              <span v-if="source.nbSallesConfigurees > 0">
                {{ source.nbSallesConfigurees }} salle{{ source.nbSallesConfigurees > 1 ? 's' : '' }} configurée{{ source.nbSallesConfigurees > 1 ? 's' : '' }}
              </span>
              <span v-else class="etat-vide-texte">Aucune salle configurée (voir table ci-dessous).</span>
            </p>
          </template>

          <p v-if="source.dernierFetch" class="source-carte-detail param-aide">
            Dernier fetch : {{ formaterInstant(source.dernierFetch) }}
          </p>
          <p v-else-if="source.id !== 'deezer' && source.id !== 'digitick'" class="source-carte-detail param-aide">
            Jamais exécuté
          </p>
        </div>

      </div>
    </div>

    <div class="param-bloc">
      <div class="source-vue-entete">
        <h3 class="param-label">Salles</h3>
        <div class="source-vue-toggle" role="group" aria-label="Vue des salles">
          <button
            type="button"
            class="btn btn-sm"
            :class="vueDigitick ? 'btn-secondary' : 'btn-primary'"
            @click="vueDigitick = false"
          >Sans source</button>
          <button
            type="button"
            class="btn btn-sm"
            :class="vueDigitick ? 'btn-primary' : 'btn-secondary'"
            @click="vueDigitick = true"
          >Avec Digitick <span v-if="sallesDigitick.length" class="badge-compteur">{{ sallesDigitick.length }}</span></button>
        </div>
      </div>
      <p class="param-aide">
        <template v-if="!vueDigitick">Salles sans identifiant Ticketmaster ni Eventim — configurez leur slug Digitick et vérifiez l'URL.</template>
        <template v-else>Salles avec un slug Digitick configuré. Collez une URL Digitick pour extraire le slug automatiquement.</template>
      </p>
      <div v-if="listeCourante.length">
        <input
          v-model="recherche"
          type="search"
          class="source-recherche"
          placeholder="Rechercher une salle…"
          aria-label="Rechercher une salle"
        />
        <div class="table-wrapper">
          <table id="table-sources-salles" :aria-label="vueDigitick ? 'Salles avec Digitick' : 'Salles sans Ticketmaster'">
            <thead>
              <tr>
                <th>Salle</th>
                <th>Slug Digitick</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="salle in sallesFiltrees" :key="salle.salleUuid">
                <td class="source-salle-nom">{{ salle.nom }}</td>
                <td>
                  <div class="source-slug-inline">
                    <input
                      :value="slugsEdites[salle.salleUuid]"
                      type="text"
                      class="source-slug-input"
                      :placeholder="slugDeduit(salle.nom)"
                      :aria-label="'Slug Digitick pour ' + salle.nom"
                      @input="mettreAJourSlug(salle, $event.target.value)"
                      @keydown.enter="sauvegarderSlug(salle)"
                    />
                    <span
                      v-if="statutTester[salle.salleUuid]"
                      class="source-test-statut"
                      :class="statutTester[salle.salleUuid] === 'ok' ? 'source-test-ok' : 'source-test-ko'"
                      aria-live="polite"
                    >
                      {{ statutTester[salle.salleUuid] === 'ok' ? '200 ✓' : '404 ✗' }}
                    </span>
                  </div>
                </td>
                <td class="source-actions">
                  <button
                    type="button"
                    class="btn btn-secondary btn-sm"
                    :disabled="enCours[salle.salleUuid + ':slug']"
                    @click="sauvegarderSlug(salle)"
                    :aria-label="'Enregistrer le slug pour ' + salle.nom"
                  >Enregistrer</button>
                  <button
                    type="button"
                    class="btn btn-secondary btn-sm"
                    :disabled="enCours[salle.salleUuid + ':test'] || !slugCourant(salle)"
                    @click="testerSlug(salle)"
                    :aria-label="'Tester le slug Digitick de ' + salle.nom"
                  >Tester</button>
                </td>
              </tr>
              <tr v-if="sallesFiltrees.length === 0">
                <td colspan="3" class="etat-vide-texte">Aucune salle ne correspond à la recherche.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <p v-else class="etat-vide-texte">
        <template v-if="!vueDigitick">Toutes les salles ont un identifiant source (Ticketmaster ou Eventim).</template>
        <template v-else>Aucune salle n'a de slug Digitick configuré.</template>
      </p>
    </div>

    </template>
  </section>
</template>

<script setup>
defineOptions({ name: 'AdminSources' })

import { ref, shallowRef, reactive, computed, onMounted, onUnmounted } from 'vue'
import EtatChargement from '../EtatChargement.vue'
import { useToasts } from '../../composables/useToasts.js'
import { formaterInstant, formaterTs } from '../../composables/useDates.js'

const { afficherToast } = useToasts()

const chargement    = ref(true)
const sources       = shallowRef([])
const salles        = shallowRef([])
const sallesDigitick = shallowRef([])
const vueDigitick   = ref(false)
const recherche     = ref('')
const slugsEdites   = reactive({})
const statutTester  = reactive({})
const enCours       = reactive({})

const listeCourante = computed(() => vueDigitick.value ? sallesDigitick.value : salles.value)

const sallesFiltrees = computed(() => {
  const q = recherche.value.trim().toLowerCase()
  if (!q) return listeCourante.value
  return listeCourante.value.filter((s) => s.nom.toLowerCase().includes(q))
})

const controleur = new AbortController()

function pctQuota(source) {
  if (!source.quotaTM || source.quotaTM.total === 0) return 0
  return Math.round((source.quotaTM.dispo * 100) / source.quotaTM.total)
}

function classeBarre(source) {
  const p = pctQuota(source)
  if (p > 30) return 'barre-ok'
  if (p > 10) return 'barre-warn'
  return 'barre-danger'
}

function slugDeduit(nom) {
  return nom
    .toLowerCase()
    .normalize('NFD').replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]/g, '')
}

function slugCourant(salle) {
  const edite = slugsEdites[salle.salleUuid]
  return edite !== undefined ? edite : (salle.digitickSlug ?? '')
}

async function charger() {
  try {
    const rep = await fetch('/api/admin/sources', { signal: controleur.signal })
    if (!rep.ok) return
    const d = await rep.json()
    sources.value        = d.sources        ?? []
    salles.value         = d.salles         ?? []
    sallesDigitick.value = d.sallesDigitick ?? []
    for (const s of [...salles.value, ...sallesDigitick.value]) {
      if (slugsEdites[s.salleUuid] === undefined) {
        slugsEdites[s.salleUuid] = s.digitickSlug ?? ''
      }
    }
  } catch (e) {
    if (e.name === 'AbortError') return
    afficherToast('Erreur lors du chargement des sources.', 'erreur')
  } finally {
    chargement.value = false
  }
}

function extraireSlugDepuisUrl(valeur) {
  try {
    const url = new URL(valeur)
    const site = url.searchParams.get('site')
    if (site) return site.trim()
  } catch (_) {}
  return valeur.trim()
}

function mettreAJourSlug(salle, valeur) {
  slugsEdites[salle.salleUuid] = extraireSlugDepuisUrl(valeur)
}

async function sauvegarderSlug(salle) {
  const cle = salle.salleUuid + ':slug'
  enCours[cle] = true
  try {
    const rep = await fetch(`/api/admin/salles/${salle.salleUuid}/digitick-slug`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug: slugsEdites[salle.salleUuid] ?? '' }),
      signal: controleur.signal,
    })
    if (rep.ok) {
      salle.digitickSlug = slugsEdites[salle.salleUuid] || null
      afficherToast('Slug mis à jour.', 'succes')
      await charger()
    } else {
      afficherToast('Erreur lors de la mise à jour du slug.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  } finally {
    enCours[cle] = false
  }
}

async function testerSlug(salle) {
  const slug = slugCourant(salle)
  if (!slug) return
  const cle = salle.salleUuid + ':test'
  enCours[cle] = true
  statutTester[salle.salleUuid] = null
  try {
    const rep = await fetch(`/api/admin/salles/${salle.salleUuid}/tester-digitick`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
      signal: controleur.signal,
    })
    statutTester[salle.salleUuid] = rep.ok ? 'ok' : 'ko'
  } catch (e) {
    if (e.name !== 'AbortError') {
      statutTester[salle.salleUuid] = 'ko'
    }
  } finally {
    enCours[cle] = false
  }
}

onMounted(charger)
onUnmounted(() => controleur.abort())
</script>
