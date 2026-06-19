<template>
  <section class="admin-section" id="sources">

    <div class="param-bloc">
      <h3 class="param-label">Sources de données</h3>
      <div class="sources-grille">

        <div v-for="source in sources" :key="source.id" class="source-carte">
          <div class="source-carte-entete">
            <span class="source-nom">{{ source.nom }}</span>
            <span class="badge-source" :class="'badge-source-' + source.type">{{ source.type === 'concerts' ? 'Concerts' : 'Métadonnées' }}</span>
          </div>

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
      <h3 class="param-label">Configuration par salle</h3>
      <p class="param-aide">
        Le slug Digitick est dérivé automatiquement du nom de la salle (minuscules, sans accents). Corrigez-le manuellement si nécessaire.
      </p>
      <div v-if="salles.length" class="table-wrapper">
        <table id="table-sources-salles" aria-label="Sources par salle">
          <thead>
            <tr>
              <th>Salle</th>
              <th>VenueId TM</th>
              <th>Slug Digitick</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="salle in salles" :key="salle.salleUuid">
              <td class="source-salle-nom">{{ salle.nom }}</td>
              <td class="source-id-lecture">
                <code v-if="salle.tmVenueId">{{ salle.tmVenueId }}</code>
                <span v-else class="etat-vide-texte">—</span>
              </td>
              <td>
                <div class="source-slug-inline">
                  <input
                    v-model="slugsEdites[salle.salleUuid]"
                    type="text"
                    class="source-slug-input"
                    :placeholder="slugDeduit(salle.nom)"
                    :aria-label="'Slug Digitick pour ' + salle.nom"
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
                <button
                  type="button"
                  class="btn btn-secondary btn-sm"
                  :disabled="enCours[salle.salleUuid + ':fetch'] || !salle.tmVenueId"
                  @click="fetchSalle(salle)"
                  :aria-label="'Déclencher un fetch pour ' + salle.nom"
                >Fetch</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="etat-vide-texte">Aucune salle enregistrée dans le référentiel.</p>
    </div>

  </section>
</template>

<script setup>
defineOptions({ name: 'AdminSources' })

import { ref, shallowRef, reactive, onMounted, onUnmounted } from 'vue'
import { useToasts } from '../../composables/useToasts.js'
import { formaterInstant, formaterTs } from '../../composables/useDates.js'

const { afficherToast } = useToasts()

const sources = shallowRef([])
const salles  = shallowRef([])
const slugsEdites   = reactive({})
const statutTester  = reactive({})
const enCours       = reactive({})

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
    sources.value = d.sources ?? []
    salles.value  = d.salles  ?? []
    for (const s of salles.value) {
      if (slugsEdites[s.salleUuid] === undefined) {
        slugsEdites[s.salleUuid] = s.digitickSlug ?? ''
      }
    }
  } catch (e) {
    if (e.name === 'AbortError') return
    afficherToast('Erreur lors du chargement des sources.', 'erreur')
  }
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

async function fetchSalle(salle) {
  const cle = salle.salleUuid + ':fetch'
  enCours[cle] = true
  try {
    const rep = await fetch(`/api/admin/salles/${salle.salleUuid}/fetch`, {
      method: 'POST',
      signal: controleur.signal,
    })
    if (rep.ok) {
      afficherToast('Fetch déclenché pour ' + salle.nom + '.', 'succes')
    } else {
      const d = await rep.json().catch(() => ({}))
      afficherToast(d.erreur ?? 'Erreur lors du déclenchement.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  } finally {
    enCours[cle] = false
  }
}

onMounted(charger)
onUnmounted(() => controleur.abort())
</script>
