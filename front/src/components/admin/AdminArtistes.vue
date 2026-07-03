<template>
  <section class="admin-section" id="artistes">

    <div class="param-bloc">
      <h3 class="param-label">Référentiel artistes</h3>
      <p class="param-aide">
        Noms canoniques et statuts des sources externes. Le nom affiché écrase
        l'enrichissement automatique Wikidata/Deezer — laissez vide pour le réactiver.
      </p>

      <div class="source-vue-entete">
        <input
          v-model="filtreNom"
          type="search"
          class="source-recherche"
          placeholder="Rechercher un artiste…"
          aria-label="Rechercher un artiste"
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
        <div class="artistes-conteneur">
          <div class="table-wrapper">
            <table aria-label="Référentiel artistes">
              <thead>
                <tr>
                  <th scope="col"><span class="sr-only">Sélection</span></th>
                  <th scope="col">Artiste</th>
                  <th scope="col">Concerts</th>
                  <th scope="col">Sources</th>
                  <th scope="col">Statut</th>
                  <th scope="col">Wikidata</th>
                  <th scope="col">Deezer</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="artiste in artistes" :key="artiste.id">
                  <td data-label="">
                    <input
                      type="checkbox"
                      :checked="selection.includes(artiste.id)"
                      :aria-label="'Sélectionner ' + (artiste.nomAffiche || artiste.nom)"
                      @change="basculerSelection(artiste.id)"
                    />
                  </td>
                  <td class="source-salle-nom" data-label="Artiste">
                    {{ artiste.nomAffiche || artiste.nom }}
                    <span v-if="artiste.nomAffiche && artiste.nomAffiche !== artiste.nom" class="param-aide"> ({{ artiste.nom }})</span>
                  </td>
                  <td data-label="Concerts">{{ artiste.nbConcerts }}</td>
                  <td data-label="Sources">
                    <span v-for="src in artiste.sources" :key="src" class="badge-source badge-source-metadonnees">{{ src }}</span>
                    <span v-if="!artiste.sources.length" class="etat-vide-texte">—</span>
                  </td>
                  <td data-label="Statut">
                    <span class="badge-quota" :class="classeStatut(artiste.statut)">{{ libelleStatut(artiste.statut) }}</span>
                  </td>
                  <td data-label="Wikidata">
                    <a v-if="artiste.wikidataId" :href="'https://www.wikidata.org/wiki/' + artiste.wikidataId" target="_blank" rel="noopener noreferrer">{{ artiste.wikidataId }}</a>
                    <span v-else class="etat-vide-texte">—</span>
                  </td>
                  <td data-label="Deezer">
                    <a v-if="artiste.deezerId" :href="'https://www.deezer.com/artist/' + artiste.deezerId" target="_blank" rel="noopener noreferrer">{{ artiste.deezerId }}</a>
                    <span v-else class="etat-vide-texte">—</span>
                  </td>
                  <td class="source-actions" data-label="">
                    <button type="button" class="btn btn-secondary btn-sm" @click="ouvrirEdition(artiste)">Éditer</button>
                    <button
                      v-if="artiste.wikidataId || artiste.deezerId"
                      type="button"
                      class="btn btn-danger btn-sm"
                      title="Effacer wikidata_id/deezer_id/nom affiché pour redécouvrir au prochain cycle"
                      @click="reinitialiserEnrichissement(artiste)"
                    >Réinitialiser</button>
                  </td>
                </tr>
                <tr v-if="artistes.length === 0">
                  <td colspan="8" class="etat-vide-texte">Aucun artiste ne correspond aux filtres.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="pagination" v-if="totalPages > 1">
          <button type="button" class="btn btn-secondary btn-sm" :disabled="page === 0" @click="allerPage(page - 1)">← Précédent</button>
          <span>Page {{ page + 1 }} / {{ totalPages }} ({{ totalArtistes }} artistes)</span>
          <button type="button" class="btn btn-secondary btn-sm" :disabled="page >= totalPages - 1" @click="allerPage(page + 1)">Suivant →</button>
        </div>
      </template>
    </div>

  </section>

  <dialog ref="dialogEditionRef" aria-labelledby="titre-edition-artiste" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-edition-artiste" class="dialog-titre">Modifier le nom affiché</h2>
    </div>
    <form @submit.prevent="sauvegarderEdition">
      <div class="champ">
        <label for="edit-nom-affiche-artiste" class="champ-label">Nom affiché</label>
        <input id="edit-nom-affiche-artiste" v-model="edition.nomAffiche" type="text" maxlength="200" autocomplete="off" :placeholder="edition.nomOriginal" />
        <p class="param-aide">Laisser vide pour revenir à l'enrichissement automatique.</p>
      </div>
      <div class="dialog-pied">
        <button type="submit" class="btn btn-secondary" :disabled="enCoursEdition">Enregistrer</button>
        <button type="button" class="btn btn-texte" @click="dialogEditionRef.close()">Annuler</button>
      </div>
    </form>
  </dialog>

  <dialog ref="dialogFusionRef" aria-labelledby="titre-fusion-artiste" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-fusion-artiste" class="dialog-titre">Fusionner deux artistes</h2>
    </div>
    <p v-if="artisteA && artisteB">
      Choisissez l'artiste à conserver — l'autre sera absorbé (concerts, abonnements et sources
      externes réassignés) puis supprimé. Action irréversible.
    </p>
    <div class="champ" v-if="artisteA && artisteB">
      <label class="champ-label">
        <input type="radio" name="cible" :value="artisteA.id" v-model="cibleChoisie" /> Conserver « {{ artisteA.nomAffiche || artisteA.nom }} »
      </label>
      <label class="champ-label">
        <input type="radio" name="cible" :value="artisteB.id" v-model="cibleChoisie" /> Conserver « {{ artisteB.nomAffiche || artisteB.nom }} »
      </label>
    </div>
    <div class="dialog-pied">
      <button type="button" class="btn btn-danger" :disabled="enCoursFusion || !cibleChoisie" @click="confirmerFusion">Fusionner</button>
      <button type="button" class="btn btn-texte" @click="dialogFusionRef.close()">Annuler</button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'AdminArtistes' })

import { ref, shallowRef, reactive, onMounted, onUnmounted } from 'vue'
import EtatChargement from '../EtatChargement.vue'
import { useToasts } from '../../composables/useToasts.js'
import { classeStatut, libelleStatut } from '../../utils/statutReferentiel.js'

const { afficherToast } = useToasts()

const chargement     = ref(true)
const artistes        = shallowRef([])
const page           = ref(0)
const totalPages     = ref(1)
const totalArtistes  = ref(0)
const filtreNom      = ref('')
const filtreStatut   = ref('')
const filtreWikidataManquant = ref(false)
const selection      = reactive([])

const dialogEditionRef = ref(null)
const edition = reactive({ id: null, nomAffiche: '', nomOriginal: '' })
const enCoursEdition = ref(false)

const dialogFusionRef = ref(null)
const artisteA = ref(null)
const artisteB = ref(null)
const cibleChoisie = ref(null)
const enCoursFusion = ref(false)

const controleur = new AbortController()

async function charger() {
  chargement.value = true
  try {
    const params = new URLSearchParams()
    params.set('page', page.value)
    if (filtreNom.value) params.set('nom', filtreNom.value)
    if (filtreStatut.value) params.set('statut', filtreStatut.value)
    if (filtreWikidataManquant.value) params.set('wikidataManquant', 'true')

    const rep = await fetch(`/api/admin/artistes?${params}`, { signal: controleur.signal })
    if (!rep.ok) return
    const data = await rep.json()
    artistes.value = data.artistes ?? []
    page.value = data.page ?? 0
    totalPages.value = data.totalPages ?? 1
    totalArtistes.value = data.totalArtistes ?? 0
  } catch (e) {
    if (e.name === 'AbortError') return
    afficherToast('Erreur lors du chargement du référentiel artistes.', 'erreur')
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
    afficherToast('Sélectionnez au maximum 2 artistes pour une fusion.', 'avertissement')
  }
}

function ouvrirEdition(artiste) {
  edition.id = artiste.id
  edition.nomAffiche = artiste.nomAffiche ?? ''
  edition.nomOriginal = artiste.nom
  dialogEditionRef.value?.showModal()
}

async function sauvegarderEdition() {
  if (enCoursEdition.value) return
  enCoursEdition.value = true
  try {
    const rep = await fetch(`/api/admin/artistes/${edition.id}`, {
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

async function reinitialiserEnrichissement(artiste) {
  try {
    const rep = await fetch(`/api/admin/artistes/${artiste.id}/reinitialiser-enrichissement`, {
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
  artisteA.value = artistes.value.find((a) => a.id === selection[0])
  artisteB.value = artistes.value.find((a) => a.id === selection[1])
  cibleChoisie.value = null
  dialogFusionRef.value?.showModal()
}

async function confirmerFusion() {
  if (!cibleChoisie.value || enCoursFusion.value) return
  const cible = cibleChoisie.value
  const source = cible === artisteA.value.id ? artisteB.value.id : artisteA.value.id
  enCoursFusion.value = true
  try {
    const rep = await fetch(`/api/admin/artistes/${source}/fusionner/${cible}`, {
      method: 'POST',
      signal: controleur.signal,
    })
    if (rep.ok) {
      dialogFusionRef.value?.close()
      afficherToast('Artistes fusionnés.', 'succes')
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
