<template>
  <section :id="config.idSection" class="admin-section">
    <div class="param-bloc">
      <h3 class="param-libelle">{{ config.titre }}</h3>
      <slot name="aide" />
      <slot name="avant-filtres" />

      <BarreFiltresReferentiel
        v-model:nom="r.filtreNom"
        v-model:statut="r.filtreStatut"
        v-model:wikidata-manquant="r.filtreWikidataManquant"
        :libelle-recherche="config.libelleRecherche"
        :placeholder-recherche="config.libelleRecherche + '…'"
        :nombre-selection="r.selection.length"
        @recharger="r.rechargerPage0"
        @fusionner="r.ouvrirFusion"
      >
        <template #apres-recherche>
          <slot name="apres-recherche" :recharger="r.rechargerPage0" />
        </template>
        <template #statuts>
          <slot name="statuts" />
        </template>
        <template #apres-wikidata>
          <slot name="apres-wikidata" :recharger="r.rechargerPage0" />
        </template>
      </BarreFiltresReferentiel>

      <EtatChargement v-if="r.chargement" />
      <template v-else>
        <div class="table-conteneur table-cartes">
          <table :aria-label="config.titre">
            <thead>
              <tr>
                <th scope="col">
                  <span class="lecteur-ecran">Sélection</span>
                </th>
                <th scope="col">{{ config.colonneNom }}</th>
                <slot name="entetes-apres-nom" />
                <th scope="col">Concerts</th>
                <th scope="col">Sources</th>
                <th scope="col">Statut</th>
                <th scope="col">Wikidata</th>
                <slot name="entetes-apres-wikidata" />
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in r.items" :key="item.id">
                <td data-label="">
                  <input
                    type="checkbox"
                    :checked="r.selection.includes(item.id)"
                    :aria-label="
                      'Sélectionner ' + (item.nomAffiche || item.nom)
                    "
                    @change="r.basculerSelection(item.id)"
                  />
                </td>
                <td class="source-salle-nom" data-label="Nom">
                  {{ item.nomAffiche || item.nom }}
                  <span
                    v-if="item.nomAffiche && item.nomAffiche !== item.nom"
                    class="param-aide"
                  >
                    ({{ item.nom }})</span
                  >
                  <slot name="apres-nom" :item="item" />
                </td>
                <slot name="cellules-apres-nom" :item="item" />
                <td data-label="Concerts">{{ item.nbConcerts }}</td>
                <td data-label="Sources">
                  <span
                    v-for="src in item.sources"
                    :key="src"
                    class="badge-source badge-source-metadonnees"
                    >{{ src }}</span
                  >
                  <span v-if="!item.sources.length" class="etat-vide-texte"
                    >—</span
                  >
                  <slot name="apres-sources" :item="item" />
                </td>
                <td data-label="Statut">
                  <span
                    class="badge-quota"
                    :class="classeStatut(item.statut)"
                    >{{ libelleStatut(item.statut) }}</span
                  >
                  <slot name="apres-statut" :item="item" />
                </td>
                <td data-label="Wikidata">
                  <a
                    v-if="item.wikidataId"
                    :href="'https://www.wikidata.org/wiki/' + item.wikidataId"
                    target="_blank"
                    rel="noopener noreferrer"
                    >{{ item.wikidataId }}</a
                  >
                  <span v-else class="etat-vide-texte">—</span>
                </td>
                <slot name="cellules-apres-wikidata" :item="item" />
                <td class="source-actions" data-label="">
                  <button
                    type="button"
                    class="bouton bouton-secondaire bouton-petit"
                    @click="r.ouvrirEdition(item)"
                  >
                    Éditer
                  </button>
                  <slot name="actions" :item="item" :charger="r.charger" />
                  <button
                    v-if="item.wikidataId || item.deezerId"
                    type="button"
                    class="bouton bouton-danger bouton-petit"
                    :title="config.aideReinitialiser"
                    @click="r.reinitialiserEnrichissement(item)"
                  >
                    Réinitialiser
                  </button>
                  <slot
                    name="actions-apres"
                    :item="item"
                    :charger="r.charger"
                  />
                </td>
              </tr>
              <tr v-if="r.items.length === 0">
                <td colspan="8" class="etat-vide-texte" data-label="">
                  {{ config.messageVide }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <PaginationListe
          admin
          :page="r.page"
          :total-pages="r.totalPages"
          :total="r.total"
          :libelle-total="config.libellePluriel"
          @changer="r.allerPage"
        />
      </template>
    </div>
  </section>

  <DialogueEditionReferentiel
    :ref="(el) => (r.dialogEditionRef = el)"
    v-model:nom-affiche="r.edition.nomAffiche"
    id-champ="edit-nom-affiche"
    :nom-original="r.edition.nomOriginal"
    :en-cours="r.enCoursEdition"
    @sauvegarder="r.sauvegarderEdition"
    @fermer="r.fermerEdition"
  />

  <DialogueFusionReferentiel
    :ref="(el) => (r.dialogFusionRef = el)"
    v-model="r.cibleChoisie"
    :entite="entite"
    :item-a="r.itemA"
    :item-b="r.itemB"
    :en-cours="r.enCoursFusion"
    @confirmer="r.confirmerFusion"
    @fermer="r.fermerFusion"
  />

  <slot name="dialogues" :charger="r.charger" />
</template>

<script setup>
import { computed, reactive, watch } from "vue";
import BarreFiltresReferentiel from "./BarreFiltresReferentiel.vue";
import DialogueEditionReferentiel from "./DialogueEditionReferentiel.vue";
import DialogueFusionReferentiel from "./DialogueFusionReferentiel.vue";
import EtatChargement from "../EtatChargement.vue";
import PaginationListe from "../PaginationListe.vue";
import { useReferentielAdmin } from "../../composables/useReferentielAdmin.js";
import { classeStatut, libelleStatut } from "../../utils/statutReferentiel.js";
defineOptions({ name: "ListeReferentielAdmin" });

// Page générique des référentiels de l'administration : tout ce que les salles et les
// artistes partagent (filtres, tableau, pagination, édition du nom, fusion). Chaque entité
// n'apporte, par emplacements, que ses colonnes, ses filtres et ses dialogues propres.
const CONFIGURATIONS = {
  salle: {
    idSection: "salles",
    titre: "Référentiel salles",
    colonneNom: "Salle",
    libelleRecherche: "Rechercher une salle",
    libellePluriel: "salles",
    messageFusionne: "Salles fusionnées.",
    messageVide: "Aucune salle ne correspond aux filtres.",
    aideReinitialiser:
      "Effacer wikidata_id/nom affiché/ville pour redécouvrir au prochain cycle",
    endpoint: "salles",
    cleTotal: "totalSalles",
  },
  artiste: {
    idSection: "artistes",
    titre: "Référentiel artistes",
    colonneNom: "Artiste",
    libelleRecherche: "Rechercher un artiste",
    libellePluriel: "artistes",
    messageFusionne: "Artistes fusionnés.",
    messageVide: "Aucun artiste ne correspond aux filtres.",
    aideReinitialiser:
      "Effacer wikidata_id/deezer_id/nom affiché pour redécouvrir au prochain cycle",
    endpoint: "artistes",
    cleTotal: "totalArtistes",
  },
};

const props = defineProps({
  entite: { type: String, required: true },
  rechercheInitiale: { type: String, default: "" },
  // Salles seulement : filtres propres à l'entité, lus à chaque chargement.
  filtresExtra: { type: Function, default: () => ({}) },
  // Salles seulement : deux salles signalées, déjà sélectionnées pour la fusion.
  idsInitiaux: { type: Array, default: () => [] },
});

const config = computed(() => CONFIGURATIONS[props.entite]);

// Objet réactif : le gabarit lit `r.items`, `r.selection`… sans énumérer chaque nom.
const r = reactive(
  useReferentielAdmin({
    rechercheInitiale: props.rechercheInitiale,
    endpoint: config.value.endpoint,
    cleListe: config.value.endpoint,
    cleTotal: config.value.cleTotal,
    messageFusionne: config.value.messageFusionne,
    libellePluriel: config.value.libellePluriel,
    filtresExtra: () => ({
      ...props.filtresExtra(),
      ids: props.idsInitiaux.join(","),
    }),
  }),
);

// Les deux éléments comparés arrivent sélectionnés : la fusion est à un clic.
watch(
  () => r.items,
  (liste) => {
    if (
      props.idsInitiaux.length === 2 &&
      liste.length === 2 &&
      r.selection.length === 0
    ) {
      liste.forEach((element) => r.selection.push(element.id));
    }
  },
);
</script>
