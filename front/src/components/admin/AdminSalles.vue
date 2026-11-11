<template>
  <section id="salles" class="admin-section">
    <div class="param-bloc">
      <h3 class="param-libelle">Référentiel salles</h3>
      <p class="param-aide">
        Noms canoniques et statuts des sources externes. Le nom affiché écrase
        l'enrichissement automatique Wikidata/OSM — laissez vide pour le
        réactiver.
      </p>
      <p class="param-aide">{{ AIDE_STATUTS_SALLES }}</p>

      <div v-if="idsInitiaux.length" class="alerte alerte-info">
        <p>
          Comparaison des deux salles signalées. Elles sont déjà sélectionnées :
          « Fusionner » ouvre le choix de la salle à conserver.
        </p>
        <button
          type="button"
          class="bouton bouton-texte bouton-petit"
          @click="emit('quitter-comparaison')"
        >
          Afficher toutes les salles
        </button>
      </div>

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
        <select
          v-model="filtreStatut"
          aria-label="Filtrer par statut"
          @change="rechargerPage0"
        >
          <option value="">Tous statuts</option>
          <option value="a_jour">À jour</option>
          <option value="a_verifier">À vérifier</option>
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
        <label
          class="source-filtre-checkbox"
          title="Salles qu'aucune source n'alimente : un état normal (salle ajoutée à la main), pas une anomalie"
        >
          <input
            v-model="filtreSansSource"
            type="checkbox"
            @change="rechargerPage0"
          />
          Sans source
        </label>
        <button
          type="button"
          class="bouton bouton-danger bouton-petit"
          :disabled="selection.length !== 2"
          @click="ouvrirFusion"
        >
          Fusionner ({{ selection.length }}/2)
        </button>
      </div>

      <EtatChargement v-if="chargement" />
      <template v-else>
        <div class="salles-conteneur">
          <div class="table-conteneur table-cartes">
            <table aria-label="Référentiel salles">
              <thead>
                <tr>
                  <th scope="col">
                    <span class="lecteur-ecran">Sélection</span>
                  </th>
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
                      :aria-label="
                        'Sélectionner ' + (salle.nomAffiche || salle.nom)
                      "
                      @change="basculerSelection(salle.id)"
                    />
                  </td>
                  <td class="source-salle-nom" data-label="Salle">
                    {{ salle.nomAffiche || salle.nom }}
                    <span
                      v-if="salle.nomAffiche && salle.nomAffiche !== salle.nom"
                      class="param-aide"
                    >
                      ({{ salle.nom }})</span
                    >
                    <span v-if="salle.nomParent" class="param-aide">
                      — sous-salle de {{ salle.nomParent }}</span
                    >
                  </td>
                  <td data-label="Ville / Dept">
                    {{
                      [salle.ville, salle.dept].filter(Boolean).join(" ") || "—"
                    }}
                  </td>
                  <td data-label="Concerts">{{ salle.nbConcerts }}</td>
                  <td data-label="Sources">
                    <span
                      v-for="src in salle.sources"
                      :key="src"
                      class="badge-source badge-source-metadonnees"
                      >{{ src }}</span
                    >
                    <span v-if="!salle.sources.length" class="etat-vide-texte"
                      >—</span
                    >
                    <p v-if="salle.lieuEventim" class="texte-note">
                      Lieu Eventim : {{ salle.lieuEventim }}
                    </p>
                  </td>
                  <td data-label="Statut">
                    <span
                      class="badge-quota"
                      :class="classeStatut(salle.statut)"
                      >{{ libelleStatut(salle.statut) }}</span
                    >
                    <template v-if="salle.suspicions?.length">
                      <p
                        v-for="suspicion in salle.suspicions"
                        :key="suspicion.source"
                        class="texte-note"
                      >
                        {{ decrireSuspicion(suspicion, formaterInstant) }}
                      </p>
                      <p class="texte-note">
                        {{ interpreterSuspicion(salle.nbConcerts) }}
                      </p>
                    </template>
                  </td>
                  <td data-label="Wikidata">
                    <a
                      v-if="salle.wikidataId"
                      :href="
                        'https://www.wikidata.org/wiki/' + salle.wikidataId
                      "
                      target="_blank"
                      rel="noopener noreferrer"
                      >{{ salle.wikidataId }}</a
                    >
                    <span v-else class="etat-vide-texte">—</span>
                  </td>
                  <td class="source-actions" data-label="">
                    <button
                      type="button"
                      class="bouton bouton-secondaire bouton-petit"
                      @click="ouvrirEdition(salle)"
                    >
                      Éditer
                    </button>
                    <button
                      type="button"
                      class="bouton bouton-secondaire bouton-petit"
                      title="Nom exact du lieu chez Eventim : voir, modifier, tester, lancer un fetch"
                      @click="
                        lieuEventim.ouvrir({
                          id: salle.id,
                          nom: salle.nomAffiche || salle.nom,
                          lieuEventim: salle.lieuEventim,
                        })
                      "
                    >
                      Lieu Eventim
                    </button>
                    <button
                      v-if="salle.nbConcerts > 0"
                      type="button"
                      class="bouton bouton-secondaire bouton-petit"
                      title="Déplacer vers une autre salle les concerts publiés sous certains noms de lieu"
                      @click="
                        repartition.ouvrir({
                          id: salle.id,
                          nom: salle.nomAffiche || salle.nom,
                        })
                      "
                    >
                      Répartir
                    </button>
                    <button
                      v-if="salle.wikidataId"
                      type="button"
                      class="bouton bouton-danger bouton-petit"
                      title="Effacer wikidata_id/nom affiché/ville pour redécouvrir au prochain cycle"
                      @click="reinitialiserEnrichissement(salle)"
                    >
                      Réinitialiser
                    </button>
                  </td>
                </tr>
                <tr v-if="items.length === 0">
                  <td colspan="8" class="etat-vide-texte" data-label="">
                    Aucune salle ne correspond aux filtres.
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
          libelle-total="salles"
          @changer="allerPage"
        />
      </template>
    </div>
  </section>

  <DialogueEditionReferentiel
    ref="dialogEditionRef"
    v-model:nom-affiche="edition.nomAffiche"
    id-champ="edit-nom-affiche"
    :nom-original="edition.nomOriginal"
    :en-cours="enCoursEdition"
    @sauvegarder="sauvegarderEdition"
    @fermer="fermerEdition"
  />

  <DialogueFusionReferentiel
    ref="dialogFusionRef"
    v-model="cibleChoisie"
    entite="salle"
    :item-a="itemA"
    :item-b="itemB"
    :en-cours="enCoursFusion"
    @confirmer="confirmerFusion"
    @fermer="fermerFusion"
  />
  <DialogueRepartitionSalle ref="repartition" @reparti="charger" />
  <DialogueLieuEventim ref="lieuEventim" @enregistre="charger" />
</template>

<script setup>
import DialogueEditionReferentiel from "./DialogueEditionReferentiel.vue";
import DialogueFusionReferentiel from "./DialogueFusionReferentiel.vue";
import PaginationListe from "../PaginationListe.vue";
defineOptions({ name: "AdminSalles" });

const props = defineProps({
  rechercheInitiale: { type: String, default: "" },
  idsInitiaux: { type: Array, default: () => [] },
});
const emit = defineEmits(["quitter-comparaison"]);

import { ref, watch } from "vue";
import EtatChargement from "../EtatChargement.vue";
import DialogueRepartitionSalle from "./DialogueRepartitionSalle.vue";
import DialogueLieuEventim from "./DialogueLieuEventim.vue";
import { useReferentielAdmin } from "../../composables/useReferentielAdmin.js";
import { formaterInstant } from "../../composables/useDates.js";
import {
  AIDE_STATUTS_SALLES,
  classeStatut,
  decrireSuspicion,
  interpreterSuspicion,
  libelleStatut,
} from "../../utils/statutReferentiel.js";

const repartition = ref(null);
const lieuEventim = ref(null);
const filtreSansSource = ref(false);
const filtreVille = ref("");
const filtreDept = ref("");

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
  endpoint: "salles",
  cleListe: "salles",
  cleTotal: "totalSalles",
  messageFusionne: "Salles fusionnées.",
  libellePluriel: "salles",
  filtresExtra: () => ({
    ville: filtreVille.value,
    dept: filtreDept.value,
    ids: props.idsInitiaux.join(","),
    sansSource: filtreSansSource.value ? "true" : "",
  }),
});

// Les deux salles comparées arrivent sélectionnées : la fusion est à un clic.
watch(items, (liste) => {
  if (
    props.idsInitiaux.length === 2 &&
    liste.length === 2 &&
    selection.length === 0
  ) {
    liste.forEach((salle) => selection.push(salle.id));
  }
});
</script>
