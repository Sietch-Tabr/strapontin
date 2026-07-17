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
                <tr v-for="artiste in items" :key="artiste.id">
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
                <tr v-if="items.length === 0">
                  <td colspan="8" class="etat-vide-texte">Aucun artiste ne correspond aux filtres.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="pagination" v-if="totalPages > 1">
          <button type="button" class="btn btn-secondary btn-sm" :disabled="page === 0" @click="allerPage(page - 1)">← Précédent</button>
          <span>Page {{ page + 1 }} / {{ totalPages }} ({{ total }} artistes)</span>
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
        <button type="button" class="btn btn-texte" @click="fermerEdition">Annuler</button>
      </div>
    </form>
  </dialog>

  <dialog ref="dialogFusionRef" aria-labelledby="titre-fusion-artiste" aria-modal="true">
    <div class="dialog-entete">
      <h2 id="titre-fusion-artiste" class="dialog-titre">Fusionner deux artistes</h2>
    </div>
    <p v-if="itemA && itemB">
      Choisissez l'artiste à conserver — l'autre sera absorbé (concerts, abonnements et sources
      externes réassignés) puis supprimé. Action irréversible.
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
defineOptions({ name: 'AdminArtistes' })

import EtatChargement from '../EtatChargement.vue'
import { useReferentielAdmin } from '../../composables/useReferentielAdmin.js'
import { classeStatut, libelleStatut } from '../../utils/statutReferentiel.js'

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
  endpoint: 'artistes',
  cleListe: 'artistes',
  cleTotal: 'totalArtistes',
  messageFusionne: 'Artistes fusionnés.',
  libellePluriel: 'artistes',
})
</script>
