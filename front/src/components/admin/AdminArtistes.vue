<template>
  <section id="artistes" class="admin-section">
    <div class="param-bloc">
      <h3 class="param-libelle">Référentiel artistes</h3>
      <p class="param-aide">
        Noms canoniques et statuts des sources externes. Le nom affiché écrase
        l'enrichissement automatique Wikidata/Deezer — laissez vide pour le
        réactiver.
      </p>
      <p class="param-aide">{{ AIDE_STATUTS_ARTISTES }}</p>

      <BarreFiltresReferentiel
        v-model:nom="filtreNom"
        v-model:statut="filtreStatut"
        v-model:wikidata-manquant="filtreWikidataManquant"
        libelle-recherche="Rechercher un artiste"
        placeholder-recherche="Rechercher un artiste…"
        :nombre-selection="selection.length"
        @recharger="rechargerPage0"
        @fusionner="ouvrirFusion"
      >
        <template #statuts>
          <option value="a_jour">À jour</option>
          <option value="invalide">Invalide</option>
        </template>
      </BarreFiltresReferentiel>

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
                    <p v-if="artiste.interventionDepuis" class="texte-note">
                      Nom Ticketmaster douteux depuis
                      {{ formaterInstant(artiste.interventionDepuis) }}
                    </p>
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

        <PaginationListe
          admin
          :page="page"
          :total-pages="totalPages"
          :total="total"
          libelle-total="artistes"
          @changer="allerPage"
        />
      </template>
    </div>
  </section>

  <DialogueEditionReferentiel
    ref="dialogEditionRef"
    v-model:nom-affiche="edition.nomAffiche"
    id-champ="edit-nom-affiche-artiste"
    :nom-original="edition.nomOriginal"
    :en-cours="enCoursEdition"
    @sauvegarder="sauvegarderEdition"
    @fermer="fermerEdition"
  />

  <DialogueFusionReferentiel
    ref="dialogFusionRef"
    v-model="cibleChoisie"
    entite="artiste"
    :item-a="itemA"
    :item-b="itemB"
    :en-cours="enCoursFusion"
    @confirmer="confirmerFusion"
    @fermer="fermerFusion"
  />

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
          class="bouton bouton-primaire"
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
import DialogueEditionReferentiel from "./DialogueEditionReferentiel.vue";
import DialogueFusionReferentiel from "./DialogueFusionReferentiel.vue";
import PaginationListe from "../PaginationListe.vue";
import BarreFiltresReferentiel from "./BarreFiltresReferentiel.vue";
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
import { formaterInstant } from "../../composables/useDates.js";
import {
  AIDE_STATUTS_ARTISTES,
  classeStatut,
  libelleStatut,
} from "../../utils/statutReferentiel.js";

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
