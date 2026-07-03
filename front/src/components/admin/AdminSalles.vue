<template>
  <section class="admin-section" id="salles">

    <div class="param-bloc">
      <h3 class="param-label">Référentiel salles</h3>
      <p class="param-aide">
        Noms canoniques et statuts des sources externes. Le nom affiché écrase
        l'enrichissement automatique Wikidata/OSM — laissez vide pour le réactiver.
      </p>

      <div class="source-vue-entete">
        <input
          v-model="filtreNom"
          type="search"
          class="source-recherche"
          placeholder="Rechercher une salle…"
          aria-label="Rechercher une salle"
          @input="rechargerPage0"
        />
        <input
          v-model="filtreVille"
          type="search"
          class="source-recherche"
          placeholder="Ville…"
          aria-label="Filtrer par ville"
          @input="rechargerPage0"
        />
        <input
          v-model="filtreDept"
          type="search"
          class="source-recherche"
          placeholder="Dept…"
          aria-label="Filtrer par département"
          maxlength="3"
          @input="rechargerPage0"
        />
        <select v-model="filtreStatut" aria-label="Filtrer par statut" @change="rechargerPage0">
          <option value="">Tous statuts</option>
          <option value="a_jour">À jour</option>
          <option value="a_verifier">À vérifier</option>
          <option value="invalide">Invalide</option>
        </select>
        <label class="source-filtre-checkbox">
          <input
            type="checkbox"
            v-model="filtreWikidataManquant"
            @change="rechargerPage0"
          /> Sans Wikidata
        </label>
        <button
          type="button"
          class="btn btn-secondary btn-sm"
          :disabled="selection.length !== 2"
          @click="ouvrirFusion"
        >Fusionner ({{ selection.length }}/2)</button>
      </div>

      <EtatChargement v-if="chargement" />
      <template v-else>
        <div class="salles-conteneur">
          <div class="table-wrapper">
            <table aria-label="Référentiel salles">
              <thead>
                <tr>
                  <th scope="col"><span class="sr-only">Sélection</span></th>
                  <th scope="col">Salle</th>
                  <th scope="col">Ville / Dept</th>
                  <th scope="col">Concerts</th>
                  <th scope="col">Sources</th>
                  <th scope="col">Statut</th>
                  <th scope="col">Wikidata</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="salle in salles" :key="salle.id">
                  <td data-label="">
                    <input
                      type="checkbox"
                      :checked="selection.includes(salle.id)"
                      :aria-label="'Sélectionner ' + (salle.nomAffiche || salle.nom)"
                      @change="basculerSelection(salle.id)"
                    />
                  </td>
                  <td class="source-salle-nom" data-label="Salle">
                    {{ salle.nomAffiche || salle.nom }}
                    <span v-if="salle.nomAffiche && salle.nomAffiche !== salle.nom" class="param-aide"> ({{ salle.nom }})</span>
                  </td>
                  <td data-label="Ville / Dept">{{ [salle.ville, salle.dept].filter(Boolean).join(' ') || '—' }}</td>
                  <td data-label="Concerts">{{ salle.nbConcerts }}</td>
                  <td data-label="Sources">
                    <span v-for="src in salle.sources" :key="src" class="badge-source badge-source-metadonnees">{{ src }}</span>
                    <span v-if="!salle.sources.length" class="etat-vide-texte">—</span>
                  </td>
                  <td data-label="Statut">
                    <span class="badge-quota" :class="classeStatut(salle.statut)">{{ libelleStatut(salle.statut) }}</span>
                  </td>
                  <td data-label="Wikidata">
                    <a v-if="salle.wikidataId" :href="'https://www.wikidata.org/wiki/' + salle.wikidataId" target="_blank" rel="noopener noreferrer">{{ salle.wikidataId }}</a>
                    <span v-else class="etat-vide-texte">—</span>
                  </td>
                  <td class="source-actions" data-label="">
                    <button type="button" class="btn btn-secondary btn-sm" @click="ouvrirEdition(salle)">Éditer</button>
                    <button
                      v-if="salle.wikidataId"
                      type="button"
                      class="btn btn-danger btn-sm"
                      title="Effacer wikidata_id/nom affiché/ville pour redécouvrir au prochain cycle"
                      @click="reinitialiserEnrichissement(salle)"
                    >Réinitialiser</button>
                  </td>
                </tr>
                <tr v-if="salles.length === 0">
                  <td colspan="8" class="etat-vide-texte">Aucune salle ne correspond aux filtres.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="pagination" v-if="totalPages > 1">
          <button type="button" class="btn btn-secondary btn-sm" :disabled="page === 0" @click="allerPage(page - 1)">← Précédent</button>
          <span>Page {{ page + 1 }} / {{ totalPages }} ({{ totalSalles }} salles)</span>
          <button type="button" class="btn btn-secondary btn-sm" :disabled="page >= totalPages - 1" @click="allerPage(page + 1)">Suivant →</button>
        </div>
      </template>
    </div>

  </section>

  <dialog ref="dialogEditionRef" aria-labelledby="titre-edition-salle" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-edition-salle" class="dialog-titre">Modifier le nom affiché</h2>
    </div>
    <form @submit.prevent="sauvegarderEdition">
      <div class="champ">
        <label for="edit-nom-affiche" class="champ-label">Nom affiché</label>
        <input id="edit-nom-affiche" v-model="edition.nomAffiche" type="text" maxlength="200" autocomplete="off" :placeholder="edition.nomOriginal" />
        <p class="param-aide">Laisser vide pour revenir à l'enrichissement automatique.</p>
      </div>
      <div class="dialog-pied">
        <button type="submit" class="btn btn-secondary" :disabled="enCoursEdition">Enregistrer</button>
        <button type="button" class="btn btn-texte" @click="dialogEditionRef.close()">Annuler</button>
      </div>
    </form>
  </dialog>

  <dialog ref="dialogFusionRef" aria-labelledby="titre-fusion-salle" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-fusion-salle" class="dialog-titre">Fusionner deux salles</h2>
    </div>
    <p v-if="salleA && salleB">
      Choisissez la salle à conserver — l'autre sera absorbée (concerts, abonnements et sources
      externes réassignés) puis supprimée. Action irréversible.
    </p>
    <div class="champ" v-if="salleA && salleB">
      <label class="champ-label">
        <input type="radio" name="cible" :value="salleA.id" v-model="cibleChoisie" /> Conserver « {{ salleA.nomAffiche || salleA.nom }} »
      </label>
      <label class="champ-label">
        <input type="radio" name="cible" :value="salleB.id" v-model="cibleChoisie" /> Conserver « {{ salleB.nomAffiche || salleB.nom }} »
      </label>
    </div>
    <div class="dialog-pied">
      <button type="button" class="btn btn-danger" :disabled="enCoursFusion || !cibleChoisie" @click="confirmerFusion">Fusionner</button>
      <button type="button" class="btn btn-texte" @click="dialogFusionRef.close()">Annuler</button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'AdminSalles' })

import { ref, shallowRef, reactive, onMounted, onUnmounted } from 'vue'
import EtatChargement from '../EtatChargement.vue'
import { useToasts } from '../../composables/useToasts.js'
import { classeStatut, libelleStatut } from '../../utils/statutReferentiel.js'

const { afficherToast } = useToasts()

const chargement   = ref(true)
const salles       = shallowRef([])
const page         = ref(0)
const totalPages   = ref(1)
const totalSalles  = ref(0)
const filtreNom    = ref('')
const filtreVille  = ref('')
const filtreDept   = ref('')
const filtreStatut = ref('')
const filtreWikidataManquant = ref(false)
const selection    = reactive([])

const dialogEditionRef = ref(null)
const edition = reactive({ id: null, nomAffiche: '', nomOriginal: '' })
const enCoursEdition = ref(false)

const dialogFusionRef = ref(null)
const salleA = ref(null)
const salleB = ref(null)
const cibleChoisie = ref(null)
const enCoursFusion = ref(false)

const controleur = new AbortController()

async function charger() {
  chargement.value = true
  try {
    const params = new URLSearchParams()
    params.set('page', page.value)
    if (filtreNom.value) params.set('nom', filtreNom.value)
    if (filtreVille.value) params.set('ville', filtreVille.value)
    if (filtreDept.value) params.set('dept', filtreDept.value)
    if (filtreStatut.value) params.set('statut', filtreStatut.value)
    if (filtreWikidataManquant.value) params.set('wikidataManquant', 'true')

    const rep = await fetch(`/api/admin/salles?${params}`, { signal: controleur.signal })
    if (!rep.ok) return
    const data = await rep.json()
    salles.value = data.salles ?? []
    page.value = data.page ?? 0
    totalPages.value = data.totalPages ?? 1
    totalSalles.value = data.totalSalles ?? 0
  } catch (e) {
    if (e.name === 'AbortError') return
    afficherToast('Erreur lors du chargement du référentiel salles.', 'erreur')
  } finally {
    chargement.value = false
  }
}

function rechargerPage0() {
  page.value = 0
  charger()
}

function allerPage(n) {
  page.value = Math.max(0, Math.min(n, totalPages.value - 1))
  charger()
}

function basculerSelection(id) {
  const index = selection.indexOf(id)
  if (index !== -1) {
    selection.splice(index, 1)
  } else if (selection.length < 2) {
    selection.push(id)
  } else {
    afficherToast('Sélectionnez au maximum 2 salles pour une fusion.', 'avertissement')
  }
}

function ouvrirEdition(salle) {
  edition.id = salle.id
  edition.nomAffiche = salle.nomAffiche ?? ''
  edition.nomOriginal = salle.nom
  dialogEditionRef.value?.showModal()
}

async function sauvegarderEdition() {
  if (enCoursEdition.value) return
  enCoursEdition.value = true
  try {
    const rep = await fetch(`/api/admin/salles/${edition.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nomAffiche: edition.nomAffiche || null }),
      signal: controleur.signal,
    })
    if (rep.ok) {
      dialogEditionRef.value?.close()
      afficherToast('Nom affiché mis à jour.', 'succes')
      await charger()
    } else {
      afficherToast('Erreur lors de la mise à jour.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  } finally {
    enCoursEdition.value = false
  }
}

async function reinitialiserEnrichissement(salle) {
  try {
    const rep = await fetch(`/api/admin/salles/${salle.id}/reinitialiser-enrichissement`, {
      method: 'POST',
      signal: controleur.signal,
    })
    if (rep.ok) {
      afficherToast('Enrichissement réinitialisé — redécouverte au prochain cycle.', 'succes')
      await charger()
    } else {
      afficherToast('Erreur lors de la réinitialisation.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  }
}

function ouvrirFusion() {
  if (selection.length !== 2) return
  salleA.value = salles.value.find((s) => s.id === selection[0])
  salleB.value = salles.value.find((s) => s.id === selection[1])
  cibleChoisie.value = null
  dialogFusionRef.value?.showModal()
}

async function confirmerFusion() {
  if (!cibleChoisie.value || enCoursFusion.value) return
  const cible = cibleChoisie.value
  const source = cible === salleA.value.id ? salleB.value.id : salleA.value.id
  enCoursFusion.value = true
  try {
    const rep = await fetch(`/api/admin/salles/${source}/fusionner/${cible}`, {
      method: 'POST',
      signal: controleur.signal,
    })
    if (rep.ok) {
      dialogFusionRef.value?.close()
      afficherToast('Salles fusionnées.', 'succes')
      selection.splice(0, selection.length)
      await charger()
    } else {
      const data = await rep.json().catch(() => ({}))
      afficherToast(data.erreur || 'Erreur lors de la fusion.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  } finally {
    enCoursFusion.value = false
  }
}

onMounted(charger)
onUnmounted(() => controleur.abort())
</script>
