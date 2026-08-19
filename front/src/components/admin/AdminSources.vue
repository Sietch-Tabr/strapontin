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
            <p class="source-carte-detail param-aide">
              {{ source.nbSallesConfigurees ?? 0 }} salle{{ source.nbSallesConfigurees > 1 ? 's' : '' }}
              et {{ source.nbArtistesConfigures ?? 0 }} artiste{{ source.nbArtistesConfigures > 1 ? 's' : '' }} suivis via cette source.
            </p>
          </template>

          <template v-else-if="source.id === 'eventim'">
            <p class="source-carte-detail">
              {{ source.nbSallesConfigurees ?? 0 }} salle{{ source.nbSallesConfigurees > 1 ? 's' : '' }}
              et {{ source.nbArtistesConfigures ?? 0 }} artiste{{ source.nbArtistesConfigures > 1 ? 's' : '' }} suivis via cette source.
            </p>
          </template>

          <template v-else-if="source.id === 'digitick'">
            <p class="source-carte-detail">
              <span v-if="source.nbSallesConfigurees > 0">
                {{ source.nbSallesConfigurees }} salle{{ source.nbSallesConfigurees > 1 ? 's' : '' }} configurée{{ source.nbSallesConfigurees > 1 ? 's' : '' }}
              </span>
              <span v-else class="etat-vide-texte">Aucune salle configurée (voir table ci-dessous).</span>
            </p>
          </template>

          <template v-else-if="source.id === 'deezer'">
            <p class="source-carte-detail">
              <span v-if="source.nbArtistesConfigures > 0">
                {{ source.nbArtistesConfigures }} artiste{{ source.nbArtistesConfigures > 1 ? 's' : '' }} avec identifiant Deezer.
              </span>
              <span v-else class="etat-vide-texte">Aucun artiste avec identifiant Deezer.</span>
            </p>
          </template>

          <template v-else-if="source.id === 'wikidata'">
            <p class="source-carte-detail">
              {{ source.nbSallesConfigurees ?? 0 }} salle{{ source.nbSallesConfigurees > 1 ? 's' : '' }}
              et {{ source.nbArtistesConfigures ?? 0 }} artiste{{ source.nbArtistesConfigures > 1 ? 's' : '' }}
              avec identifiant Wikidata.
            </p>
          </template>

          <p v-if="source.dernierFetch" class="source-carte-detail param-aide">
            Dernier fetch : {{ formaterInstant(source.dernierFetch) }}
          </p>
          <p v-else-if="source.id !== 'deezer' && source.id !== 'digitick' && source.id !== 'wikidata'" class="source-carte-detail param-aide">
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
        <template v-else>Salles gérées manuellement avec leur slug Digitick. Elles apparaissent dans la recherche « Mes salles » même sans concerts.</template>
      </p>

      <template v-if="vueDigitick">
        <div class="source-digitick-actions">
          <button type="button" class="btn btn-secondary btn-sm" @click="ouvrirAjout">Ajouter une salle</button>
        </div>
      </template>

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
                <th v-if="vueDigitick">Ville / CP</th>
                <th>Slug Digitick</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="salle in sallesFiltrees" :key="salle.salleId">
                <td class="source-salle-nom">{{ salle.nom }}</td>
                <td v-if="vueDigitick" class="source-salle-loc">
                  <span v-if="salle.ville || salle.dept">{{ [salle.ville, salle.dept].filter(Boolean).join(' ') }}</span>
                  <span v-else class="etat-vide-texte">—</span>
                </td>
                <td>
                  <div class="source-slug-inline">
                    <input
                      :value="slugsEdites[salle.salleId]"
                      type="text"
                      class="source-slug-input"
                      :placeholder="slugDeduit(salle.nom)"
                      :aria-label="'Slug Digitick pour ' + salle.nom"
                      @input="mettreAJourSlug(salle, $event.target.value)"
                      @keydown.enter="sauvegarderSlug(salle)"
                    />
                    <span
                      v-if="statutTester[salle.salleId]"
                      class="source-test-statut"
                      :class="statutTester[salle.salleId]?.startsWith('ok') ? 'source-test-ok' : 'source-test-ko'"
                      aria-live="polite"
                    >
                      {{ formatStatutTest(statutTester[salle.salleId]) }}
                    </span>
                  </div>
                </td>
                <td class="source-actions">
                  <button
                    type="button"
                    class="btn btn-secondary btn-sm"
                    :disabled="enCours[salle.salleId + ':slug']"
                    @click="sauvegarderSlug(salle)"
                    :aria-label="'Enregistrer le slug pour ' + salle.nom"
                  >Enregistrer</button>
                  <button
                    type="button"
                    class="btn btn-secondary btn-sm"
                    :disabled="enCours[salle.salleId + ':test'] || !slugCourant(salle)"
                    @click="testerSlug(salle)"
                    :aria-label="'Tester le slug Digitick de ' + salle.nom"
                  >Tester</button>
                  <template v-if="vueDigitick">
                    <button
                      type="button"
                      class="btn btn-secondary btn-sm"
                      :disabled="enCours[salle.salleId + ':fetch'] || !slugCourant(salle)"
                      @click="fetchSalle(salle)"
                      :aria-label="'Déclencher un fetch pour ' + salle.nom"
                    >Fetch</button>
                    <button
                      type="button"
                      class="btn btn-secondary btn-sm"
                      @click="ouvrirEdition(salle)"
                      :aria-label="'Éditer ' + salle.nom"
                    >Éditer</button>
                    <button
                      type="button"
                      class="btn btn-danger-texte btn-sm"
                      :disabled="enCours[salle.salleId + ':suppr']"
                      @click="demanderSuppression(salle)"
                      :aria-label="'Supprimer ' + salle.nom"
                    >Supprimer</button>
                  </template>
                </td>
              </tr>
              <tr v-if="sallesFiltrees.length === 0">
                <td :colspan="vueDigitick ? 4 : 3" class="etat-vide-texte">Aucune salle ne correspond à la recherche.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <p v-else class="etat-vide-texte">
        <template v-if="!vueDigitick">Toutes les salles ont un identifiant source (Ticketmaster ou Eventim).</template>
        <template v-else>Aucune salle Digitick configurée. Cliquez sur « Ajouter une salle » pour commencer.</template>
      </p>
    </div>

    </template>
  </section>

  <dialog ref="dialogAjoutRef" aria-labelledby="titre-ajout-digitick" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-ajout-digitick" class="dialog-titre">Ajouter une salle Digitick</h2>
    </div>
    <form @submit.prevent="creerSalleDigitick">
      <div class="champ">
        <label for="ajout-nom" class="champ-label">Nom <span aria-hidden="true">*</span></label>
        <input id="ajout-nom" v-model="ajout.nom" type="text" required maxlength="200" autocomplete="off" placeholder="L'Empreinte" />
      </div>
      <div class="champ">
        <label for="ajout-slug" class="champ-label">Slug Digitick <span aria-hidden="true">*</span></label>
        <div class="source-slug-inline">
          <input id="ajout-slug" :value="ajout.slug" type="text" required maxlength="100" autocomplete="off" placeholder="lempreinte" @input="ajout.slug = extraireSlugDepuisUrl($event.target.value); ajoutSlugStatut = null" />
          <span
            v-if="ajoutSlugStatut"
            class="source-test-statut"
            :class="ajoutSlugStatut?.startsWith('ok') ? 'source-test-ok' : 'source-test-ko'"
            aria-live="polite"
          >{{ formatStatutTest(ajoutSlugStatut) }}</span>
        </div>
        <p class="param-aide">Collez l'URL Digitick ou saisissez le slug directement — ex. <code>lempreinte</code> ou <code>index-css5-lempreinte-pg1.html</code></p>
      </div>
      <div class="champ">
        <label for="ajout-ville" class="champ-label">Ville</label>
        <input id="ajout-ville" v-model="ajout.ville" type="text" maxlength="100" autocomplete="off" placeholder="Savigny-le-Temple" />
      </div>
      <div class="champ">
        <label for="ajout-cp" class="champ-label">Département</label>
        <input id="ajout-cp" v-model="ajout.dept" type="text" maxlength="3" autocomplete="off" placeholder="77" />
      </div>
      <div class="dialog-pied">
        <button type="submit" class="btn btn-secondary" :disabled="enCours.ajout">Ajouter</button>
        <button type="button" class="btn btn-secondary" :disabled="enCours.ajoutTest || !ajout.slug" @click="testerSlugAjout">Tester</button>
        <button type="button" class="btn btn-texte" @click="dialogAjoutRef.close()">Annuler</button>
      </div>
    </form>
  </dialog>

  <dialog ref="dialogEditionRef" aria-labelledby="titre-edition-digitick" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-edition-digitick" class="dialog-titre">Modifier la salle</h2>
    </div>
    <form @submit.prevent="sauvegarderEdition">
      <div class="champ">
        <label for="edit-nom" class="champ-label">Nom</label>
        <input id="edit-nom" v-model="edition.nom" type="text" maxlength="200" autocomplete="off" />
      </div>
      <div class="champ">
        <label for="edit-ville" class="champ-label">Ville</label>
        <input id="edit-ville" v-model="edition.ville" type="text" maxlength="100" autocomplete="off" />
      </div>
      <div class="champ">
        <label for="edit-cp" class="champ-label">Département</label>
        <input id="edit-cp" v-model="edition.dept" type="text" maxlength="3" autocomplete="off" placeholder="77" />
      </div>
      <div class="dialog-pied">
        <button type="submit" class="btn btn-secondary" :disabled="enCours.edition">Enregistrer</button>
        <button type="button" class="btn btn-texte" @click="dialogEditionRef.close()">Annuler</button>
      </div>
    </form>
  </dialog>

  <dialog ref="dialogSupprRef" aria-labelledby="titre-suppr-digitick" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-suppr-digitick" class="dialog-titre">Supprimer la salle</h2>
    </div>
    <p>Supprimer « <strong>{{ salleASupprimer?.nom }}</strong> » et son slug Digitick ?<br>Cette action est irréversible si la salle n'a pas de concerts.</p>
    <div class="dialog-pied">
      <button type="button" class="btn btn-danger" :disabled="enCours[salleASupprimer?.salleId + ':suppr']" @click="confirmerSuppression">Supprimer</button>
      <button type="button" class="btn btn-texte" @click="dialogSupprRef.close()">Annuler</button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'AdminSources' })

import { ref, shallowRef, reactive, computed, onMounted, onUnmounted } from 'vue'
import EtatChargement from '../EtatChargement.vue'
import { useToasts } from '../../composables/useToasts.js'
import { useDialog } from '../../composables/useDialog.js'
import { formaterInstant, formaterTs } from '../../composables/useDates.js'
import { corpsJsonOuVide } from '../../utils/reponseJson.js'

const { afficherToast } = useToasts()

const chargement      = ref(true)
const sources         = shallowRef([])
const salles          = shallowRef([])
const sallesDigitick  = shallowRef([])
const vueDigitick     = ref(false)
const recherche       = ref('')
const slugsEdites     = reactive({})
const statutTester    = reactive({})
const enCours         = reactive({})

const ajout           = reactive({ nom: '', slug: '', ville: '', dept: '' })
const ajoutSlugStatut = ref(null)
const edition         = reactive({ salleId: null, nom: '', ville: '', dept: '' })
const salleASupprimer = ref(null)

const dialogAjoutRef  = ref(null)
const dialogEditionRef = ref(null)
const dialogSupprRef  = ref(null)

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
  const edite = slugsEdites[salle.salleId]
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
      if (slugsEdites[s.salleId] === undefined) {
        slugsEdites[s.salleId] = s.digitickSlug ?? ''
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
    const m = url.pathname.match(/\/index-css\d+-(.+?)-pg\d+\.html$/)
    if (m) return m[1].trim()
  } catch (_) {}
  return valeur.trim()
}

function mettreAJourSlug(salle, valeur) {
  slugsEdites[salle.salleId] = extraireSlugDepuisUrl(valeur)
}

async function sauvegarderSlug(salle) {
  const cle = salle.salleId + ':slug'
  enCours[cle] = true
  try {
    const rep = await fetch(`/api/admin/salles/${salle.salleId}/digitick-slug`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug: slugsEdites[salle.salleId] ?? '' }),
      signal: controleur.signal,
    })
    if (rep.ok) {
      salle.digitickSlug = slugsEdites[salle.salleId] || null
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
  const cle = salle.salleId + ':test'
  enCours[cle] = true
  statutTester[salle.salleId] = null
  try {
    const rep = await fetch(`/api/admin/salles/${salle.salleId}/tester-digitick`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
      signal: controleur.signal,
    })
    if (rep.ok) {
      const data = await corpsJsonOuVide(rep)
      statutTester[salle.salleId] = data.nbConcerts > 0 ? `ok:${data.nbConcerts}` : 'ok:0'
    } else {
      statutTester[salle.salleId] = 'ko'
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      statutTester[salle.salleId] = 'ko'
    }
  } finally {
    enCours[cle] = false
  }
}

function formatStatutTest(statut) {
  if (!statut) return ''
  if (statut === 'ko') return '404 ✗'
  const nb = parseInt(statut.split(':')[1] ?? '0', 10)
  return nb > 0 ? `✓ ${nb} concert${nb > 1 ? 's' : ''}` : '✓ aucun concert'
}

async function fetchSalle(salle) {
  const cle = salle.salleId + ':fetch'
  enCours[cle] = true
  try {
    const rep = await fetch(`/api/admin/salles/${salle.salleId}/fetch`, {
      method: 'POST',
      signal: controleur.signal,
    })
    if (rep.ok) {
      afficherToast('Fetch déclenché.', 'succes')
    } else {
      const data = await corpsJsonOuVide(rep)
      afficherToast(data.erreur || 'Erreur lors du déclenchement.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  } finally {
    enCours[cle] = false
  }
}

function ouvrirAjout() {
  ajout.nom = ''
  ajout.slug = ''
  ajout.ville = ''
  ajout.dept = ''
  ajoutSlugStatut.value = null
  dialogAjoutRef.value?.showModal()
}

async function testerSlugAjout() {
  if (!ajout.slug || enCours.ajoutTest) return
  enCours.ajoutTest = true
  ajoutSlugStatut.value = null
  try {
    const slug = extraireSlugDepuisUrl(ajout.slug)
    const rep = await fetch(`/api/admin/salles/00000000-0000-0000-0000-000000000001/tester-digitick`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
      signal: controleur.signal,
    })
    if (rep.ok) {
      const data = await corpsJsonOuVide(rep)
      ajoutSlugStatut.value = data.nbConcerts > 0 ? `ok:${data.nbConcerts}` : 'ok:0'
    } else {
      ajoutSlugStatut.value = 'ko'
    }
  } catch (e) {
    if (e.name !== 'AbortError') ajoutSlugStatut.value = 'ko'
  } finally {
    enCours.ajoutTest = false
  }
}

async function creerSalleDigitick() {
  if (enCours.ajout) return
  enCours.ajout = true
  try {
    const rep = await fetch('/api/admin/digitick-salles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nom: ajout.nom, slug: ajout.slug, ville: ajout.ville || null, dept: ajout.dept || null }),
      signal: controleur.signal,
    })
    if (rep.ok) {
      dialogAjoutRef.value?.close()
      afficherToast('Salle Digitick ajoutée — fetch en cours.', 'succes')
      await charger()
    } else {
      const data = await corpsJsonOuVide(rep)
      afficherToast(data.erreur || 'Erreur lors de la création.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  } finally {
    enCours.ajout = false
  }
}

function ouvrirEdition(salle) {
  edition.salleId = salle.salleId
  edition.nom = salle.nom ?? ''
  edition.ville = salle.ville ?? ''
  edition.dept = salle.dept ?? ''
  dialogEditionRef.value?.showModal()
}

async function sauvegarderEdition() {
  if (enCours.edition) return
  enCours.edition = true
  try {
    const rep = await fetch(`/api/admin/digitick-salles/${edition.salleId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        nom: edition.nom || null,
        slug: null,
        ville: edition.ville || null,
        dept: edition.dept || null,
      }),
      signal: controleur.signal,
    })
    if (rep.ok) {
      dialogEditionRef.value?.close()
      afficherToast('Salle mise à jour.', 'succes')
      await charger()
    } else {
      afficherToast('Erreur lors de la mise à jour.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  } finally {
    enCours.edition = false
  }
}

function demanderSuppression(salle) {
  salleASupprimer.value = salle
  dialogSupprRef.value?.showModal()
}

async function confirmerSuppression() {
  const salle = salleASupprimer.value
  if (!salle) return
  const cle = salle.salleId + ':suppr'
  enCours[cle] = true
  try {
    const rep = await fetch(`/api/admin/digitick-salles/${salle.salleId}`, {
      method: 'DELETE',
      signal: controleur.signal,
    })
    dialogSupprRef.value?.close()
    if (rep.ok) {
      afficherToast('Salle supprimée.', 'succes')
      await charger()
    } else if (rep.status === 409) {
      const data = await corpsJsonOuVide(rep)
      afficherToast(data.erreur || 'Impossible de supprimer : salle suivie ou avec concerts.', 'erreur')
    } else {
      afficherToast('Erreur lors de la suppression.', 'erreur')
    }
  } catch (e) {
    if (e.name !== 'AbortError') afficherToast('Erreur réseau.', 'erreur')
  } finally {
    enCours[cle] = false
    salleASupprimer.value = null
  }
}

onMounted(charger)
onUnmounted(() => controleur.abort())
</script>
