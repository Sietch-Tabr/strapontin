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
                <tr v-for="salle in items" :key="salle.id">
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
                <tr v-if="items.length === 0">
                  <td colspan="8" class="etat-vide-texte">Aucune salle ne correspond aux filtres.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="pagination" v-if="totalPages > 1">
          <button type="button" class="btn btn-secondary btn-sm" :disabled="page === 0" @click="allerPage(page - 1)">← Précédent</button>
          <span>Page {{ page + 1 }} / {{ totalPages }} ({{ total }} salles)</span>
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
        <button type="button" class="btn btn-texte" @click="fermerEdition">Annuler</button>
      </div>
    </form>
  </dialog>

  <dialog ref="dialogFusionRef" aria-labelledby="titre-fusion-salle" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-fusion-salle" class="dialog-titre">Fusionner deux salles</h2>
    </div>
    <p v-if="itemA && itemB">
      Choisissez la salle à conserver — l'autre sera absorbée (concerts, abonnements et sources
      externes réassignés) puis supprimée. Action irréversible.
    </p>
    <div class="champ" v-if="itemA && itemB">
      <label class="champ-label">
        <input type="radio" name="cible" :value="itemA.id" v-model="cibleChoisie" /> Conserver « {{ itemA.nomAffiche || itemA.nom }} »
      </label>
      <label class="champ-label">
        <input type="radio" name="cible" :value="itemB.id" v-model="cibleChoisie" /> Conserver « {{ itemB.nomAffiche || itemB.nom }} »
      </label>
    </div>
    <div class="dialog-pied">
      <button type="button" class="btn btn-danger" :disabled="enCoursFusion || !cibleChoisie" @click="confirmerFusion">Fusionner</button>
      <button type="button" class="btn btn-texte" @click="fermerFusion">Annuler</button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'AdminSalles' })

import { ref } from 'vue'
import EtatChargement from '../EtatChargement.vue'
import { useReferentielAdmin } from '../../composables/useReferentielAdmin.js'
import { classeStatut, libelleStatut } from '../../utils/statutReferentiel.js'

const filtreVille = ref('')
const filtreDept = ref('')

const {
  chargement,
  items,
  page,
  totalPages,
  total,
  filtreNom,
  filtreStatut,
  filtreWikidataManquant,
  selection,
  dialogEditionRef,
  edition,
  enCoursEdition,
  dialogFusionRef,
  itemA,
  itemB,
  cibleChoisie,
  enCoursFusion,
  rechargerPage0,
  allerPage,
  basculerSelection,
  ouvrirEdition,
  sauvegarderEdition,
  reinitialiserEnrichissement,
  ouvrirFusion,
  confirmerFusion,
  fermerEdition,
  fermerFusion,
} = useReferentielAdmin({
  endpoint: 'salles',
  cleListe: 'salles',
  cleTotal: 'totalSalles',
  messageFusionne: 'Salles fusionnées.',
  libellePluriel: 'salles',
  filtresExtra: () => ({ ville: filtreVille.value, dept: filtreDept.value }),
})
</script>
