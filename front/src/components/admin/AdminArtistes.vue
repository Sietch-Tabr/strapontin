<template>
  <section id="artistes" class="admin-section">
    <div class="param-bloc">
      <h3 class="param-libelle">Référentiel artistes</h3>
      <p class="param-aide">
        Noms canoniques et statuts des sources externes. Le nom affiché écrase
        l'enrichissement automatique Wikidata/Deezer — laissez vide pour le
        réactiver.
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
        <select
          v-model="filtreStatut"
          aria-label="Filtrer par statut"
          @change="rechargerPage0"
        >
          <option value="">Tous statuts</option>
          <option value="a_jour">À jour</option>
          <option value="invalide">Invalide</option>
        </select>
        <label class="source-filtre-checkbox">
          <input
            v-model="filtreWikidataManquant"
            type="checkbox"
            @change="rechargerPage0"
          />
          Sans Wikidata
        </label>
        <button
          type="button"
          class="bouton bouton-secondaire bouton-petit"
          :disabled="selection.length !== 2"
          @click="ouvrirFusion"
        >
          Fusionner ({{ selection.length }}/2)
        </button>
      </div>

      <EtatChargement v-if="chargement" />
      <template v-else>
        <div class="artistes-conteneur">
          <div class="table-conteneur table-cartes">
            <table aria-label="Référentiel artistes">
              <thead>
                <tr>
                  <th scope="col">
                    <span class="lecteur-ecran">Sélection</span>
                  </th>
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
                      :aria-label="
                        'Sélectionner ' + (artiste.nomAffiche || artiste.nom)
                      "
                      @change="basculerSelection(artiste.id)"
                    />
                  </td>
                  <td class="source-salle-nom" data-label="Artiste">
                    {{ artiste.nomAffiche || artiste.nom }}
                    <span
                      v-if="
                        artiste.nomAffiche && artiste.nomAffiche !== artiste.nom
                      "
                      class="param-aide"
                    >
                      ({{ artiste.nom }})</span
                    >
                  </td>
                  <td data-label="Concerts">{{ artiste.nbConcerts }}</td>
                  <td data-label="Sources">
                    <span
                      v-for="src in artiste.sources"
                      :key="src"
                      class="badge-source badge-source-metadonnees"
                      >{{ src }}</span
                    >
                    <span v-if="!artiste.sources.length" class="etat-vide-texte"
                      >—</span
                    >
                  </td>
                  <td data-label="Statut">
                    <span
                      class="badge-quota"
                      :class="classeStatut(artiste.statut)"
                      >{{ libelleStatut(artiste.statut) }}</span
                    >
                  </td>
                  <td data-label="Wikidata">
                    <a
                      v-if="artiste.wikidataId"
                      :href="
                        'https://www.wikidata.org/wiki/' + artiste.wikidataId
                      "
                      target="_blank"
                      rel="noopener noreferrer"
                      >{{ artiste.wikidataId }}</a
                    >
                    <span v-else class="etat-vide-texte">—</span>
                  </td>
                  <td data-label="Deezer">
                    <a
                      v-if="artiste.deezerId"
                      :href="
                        'https://www.deezer.com/artist/' + artiste.deezerId
                      "
                      target="_blank"
                      rel="noopener noreferrer"
                      >{{ artiste.deezerId }}</a
                    >
                    <span v-else class="etat-vide-texte">—</span>
                  </td>
                  <td class="source-actions" data-label="">
                    <button
                      type="button"
                      class="bouton bouton-secondaire bouton-petit"
                      @click="ouvrirEdition(artiste)"
                    >
                      Éditer
                    </button>
                    <button
                      v-if="artiste.wikidataId || artiste.deezerId"
                      type="button"
                      class="bouton bouton-danger bouton-petit"
                      title="Effacer wikidata_id/deezer_id/nom affiché pour redécouvrir au prochain cycle"
                      @click="reinitialiserEnrichissement(artiste)"
                    >
                      Réinitialiser
                    </button>
                    <template v-if="artiste.statut === 'invalide'">
                      <button
                        type="button"
                        class="bouton bouton-secondaire bouton-petit"
                        title="Effacer le signal d'intervention sans fusion (divergence de nom jugée acceptable)"
                        @click="ignorerIntervention(artiste)"
                      >
                        Ignorer
                      </button>
                      <button
                        v-if="artiste.identifiantTmExterne"
                        type="button"
                        class="bouton bouton-secondaire bouton-petit"
                        title="Corriger directement l'identifiant Ticketmaster"
                        @click="ouvrirCorrectionTm(artiste)"
                      >
                        Identifiant TM
                      </button>
                    </template>
                  </td>
                </tr>
                <tr v-if="items.length === 0">
                  <td colspan="8" class="etat-vide-texte" data-label="">
                    Aucun artiste ne correspond aux filtres.
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="totalPages > 1" class="pagination">
          <button
            type="button"
            class="bouton bouton-secondaire bouton-petit"
            :disabled="page === 0"
            @click="allerPage(page - 1)"
          >
            ← Précédent
          </button>
          <span
            >Page {{ page + 1 }} / {{ totalPages }} ({{ total }} artistes)</span
          >
          <button
            type="button"
            class="bouton bouton-secondaire bouton-petit"
            :disabled="page >= totalPages - 1"
            @click="allerPage(page + 1)"
          >
            Suivant →
          </button>
        </div>
      </template>
    </div>
  </section>

  <dialog
    ref="dialogEditionRef"
    aria-labelledby="titre-edition-artiste"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-edition-artiste" class="dialog-titre">
        Modifier le nom affiché
      </h2>
    </div>
    <form @submit.prevent="sauvegarderEdition">
      <div class="champ">
        <label for="edit-nom-affiche-artiste" class="champ-label"
          >Nom affiché</label
        >
        <input
          id="edit-nom-affiche-artiste"
          v-model="edition.nomAffiche"
          type="text"
          maxlength="200"
          autocomplete="off"
          :placeholder="edition.nomOriginal"
        />
        <p class="param-aide">
          Laisser vide pour revenir à l'enrichissement automatique.
        </p>
      </div>
      <div class="dialog-pied">
        <button
          type="submit"
          class="bouton bouton-secondaire"
          :disabled="enCoursEdition"
        >
          Enregistrer
        </button>
        <button
          type="button"
          class="bouton bouton-texte"
          @click="fermerEdition"
        >
          Annuler
        </button>
      </div>
    </form>
  </dialog>

  <dialog
    ref="dialogFusionRef"
    aria-labelledby="titre-fusion-artiste"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-fusion-artiste" class="dialog-titre">
        Fusionner deux artistes
      </h2>
    </div>
    <p v-if="itemA && itemB">
      Choisissez l'artiste à conserver — l'autre sera absorbé (concerts,
      abonnements et sources externes réassignés) puis supprimé. Action
      irréversible.
    </p>
    <div v-if="itemA && itemB" class="champ">
      <label class="champ-label">
        <input
          v-model="cibleChoisie"
          type="radio"
          name="cible"
          :value="itemA.id"
        />
        Conserver « {{ itemA.nomAffiche || itemA.nom }} »
      </label>
      <label class="champ-label">
        <input
          v-model="cibleChoisie"
          type="radio"
          name="cible"
          :value="itemB.id"
        />
        Conserver « {{ itemB.nomAffiche || itemB.nom }} »
      </label>
    </div>
    <div class="dialog-pied">
      <button
        type="button"
        class="bouton bouton-danger"
        :disabled="enCoursFusion || !cibleChoisie"
        @click="confirmerFusion"
      >
        Fusionner
      </button>
      <button type="button" class="bouton bouton-texte" @click="fermerFusion">
        Annuler
      </button>
    </div>
  </dialog>

  <dialog
    ref="dialogIdentifiantTmRef"
    aria-labelledby="titre-identifiant-tm"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-identifiant-tm" class="dialog-titre">
        Corriger l'identifiant Ticketmaster
      </h2>
    </div>
    <form @submit.prevent="sauvegarderIdentifiantTm">
      <div class="champ">
        <label for="edit-identifiant-tm" class="champ-label"
          >Identifiant Ticketmaster (attractionId)</label
        >
        <input
          id="edit-identifiant-tm"
          v-model="identifiantTm.valeur"
          type="text"
          maxlength="200"
          autocomplete="off"
          required
        />
        <p class="param-aide">
          Remplace le lien vers l'artiste Ticketmaster suivi et efface le signal
          d'intervention.
        </p>
      </div>
      <div class="dialog-pied">
        <button
          type="submit"
          class="bouton bouton-secondaire"
          :disabled="enCoursIdentifiantTm"
        >
          Enregistrer
        </button>
        <button
          type="button"
          class="bouton bouton-texte"
          @click="fermerCorrectionTm"
        >
          Annuler
        </button>
      </div>
    </form>
  </dialog>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "AdminArtistes" });

const props = defineProps({
  rechercheInitiale: { type: String, default: "" },
});

import { reactive, ref } from "vue";
import EtatChargement from "../EtatChargement.vue";
import { useReferentielAdmin } from "../../composables/useReferentielAdmin.js";
import { useDialog } from "../../composables/useDialog.js";
import { useToasts } from "../../composables/useToasts.js";
import { classeStatut, libelleStatut } from "../../utils/statutReferentiel.js";

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
  charger,
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
  rechercheInitiale: props.rechercheInitiale,
  endpoint: "artistes",
  cleListe: "artistes",
  cleTotal: "totalArtistes",
  messageFusionne: "Artistes fusionnés.",
  libellePluriel: "artistes",
});

const { afficherToast } = useToasts();

// actions propres aux artistes (necessite_intervention_le n'existe pas sur salle_sources)
// — implémentées localement plutôt que dans useReferentielAdmin.js, partagé avec AdminSalles.vue.
async function ignorerIntervention(artiste) {
  try {
    const rep = await appelApi(
      `/api/admin/artistes/${artiste.id}/ignorer-intervention`,
      {
        method: "POST",
      },
    );
    if (rep.ok) {
      afficherToast("Intervention ignorée.", "succes");
      await charger();
    } else {
      afficherToast("Erreur lors de la mise à jour.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

const {
  dialogRef: dialogIdentifiantTmRef,
  ouvrir: ouvrirDialogIdentifiantTm,
  fermer: fermerCorrectionTm,
} = useDialog();
const identifiantTm = reactive({ artisteId: null, valeur: "" });
const enCoursIdentifiantTm = ref(false);

function ouvrirCorrectionTm(artiste) {
  identifiantTm.artisteId = artiste.id;
  identifiantTm.valeur = artiste.identifiantTmExterne ?? "";
  ouvrirDialogIdentifiantTm();
}

async function sauvegarderIdentifiantTm() {
  if (enCoursIdentifiantTm.value || !identifiantTm.valeur.trim()) return;
  enCoursIdentifiantTm.value = true;
  try {
    const rep = await appelApi(
      `/api/admin/artistes/${identifiantTm.artisteId}/identifiant-tm`,
      {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ identifiantExterne: identifiantTm.valeur }),
      },
    );
    if (rep.ok) {
      fermerCorrectionTm();
      afficherToast("Identifiant Ticketmaster corrigé.", "succes");
      await charger();
    } else {
      const data = await lireJson(rep);
      afficherToast(data.erreur || "Erreur lors de la correction.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCoursIdentifiantTm.value = false;
  }
}
</script>

