<template>
  <ListeReferentielAdmin
    entite="salle"
    :recherche-initiale="rechercheInitiale"
    :ids-initiaux="idsInitiaux"
    :filtres-extra="filtresExtra"
  >
    <template #aide>
      <p class="param-aide">
        Noms canoniques et statuts des sources externes. Le nom affiché écrase
        l'enrichissement automatique Wikidata/OSM — laissez vide pour le
        réactiver.
      </p>
      <p class="param-aide">{{ AIDE_STATUTS_SALLES }}</p>
    </template>

    <template #avant-filtres>
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
    </template>

    <template #apres-recherche="{ recharger }">
      <input
        v-model="filtreVille"
        type="search"
        class="source-recherche"
        placeholder="Ville…"
        aria-label="Filtrer par ville"
        @input="recharger"
      />
      <input
        v-model="filtreDept"
        type="search"
        class="source-recherche"
        placeholder="Dept…"
        aria-label="Filtrer par département"
        maxlength="3"
        @input="recharger"
      />
    </template>

    <template #statuts>
      <option value="a_jour">À jour</option>
      <option value="a_verifier">À vérifier</option>
      <option value="invalide">Invalide</option>
    </template>

    <template #apres-wikidata="{ recharger }">
      <label
        class="source-filtre-checkbox"
        title="Salles qu'aucune source n'alimente : un état normal (salle ajoutée à la main), pas une anomalie"
      >
        <input v-model="filtreSansSource" type="checkbox" @change="recharger" />
        Sans source
      </label>
    </template>

    <template #apres-nom="{ item }">
      <span v-if="item.nomParent" class="param-aide">
        — sous-salle de {{ item.nomParent }}</span
      >
    </template>

    <template #entetes-apres-nom>
      <th scope="col">Ville / Dept</th>
    </template>

    <template #cellules-apres-nom="{ item }">
      <td data-label="Ville / Dept">
        {{ [item.ville, item.dept].filter(Boolean).join(" ") || "—" }}
      </td>
    </template>

    <template #apres-sources="{ item }">
      <p v-if="item.lieuEventim" class="texte-note">
        Lieu Eventim : {{ item.lieuEventim }}
      </p>
    </template>

    <template #apres-statut="{ item }">
      <template v-if="item.suspicions?.length">
        <p
          v-for="suspicion in item.suspicions"
          :key="suspicion.source"
          class="texte-note"
        >
          {{ decrireSuspicion(suspicion, formaterInstant) }}
        </p>
        <p class="texte-note">
          {{ interpreterSuspicion(item.nbConcerts) }}
        </p>
      </template>
    </template>

    <template #actions="{ item }">
      <button
        type="button"
        class="bouton bouton-secondaire bouton-petit"
        title="Nom exact du lieu chez Eventim : voir, modifier, tester, lancer un fetch"
        @click="
          lieuEventim.ouvrir({
            id: item.id,
            nom: item.nomAffiche || item.nom,
            lieuEventim: item.lieuEventim,
          })
        "
      >
        Lieu Eventim
      </button>
      <button
        v-if="item.nbConcerts > 0"
        type="button"
        class="bouton bouton-secondaire bouton-petit"
        title="Déplacer vers une autre salle les concerts publiés sous certains noms de lieu"
        @click="
          repartition.ouvrir({
            id: item.id,
            nom: item.nomAffiche || item.nom,
          })
        "
      >
        Répartir
      </button>
    </template>

    <template #dialogues="{ charger }">
      <DialogueRepartitionSalle ref="repartition" @reparti="charger" />
      <DialogueLieuEventim ref="lieuEventim" @enregistre="charger" />
    </template>
  </ListeReferentielAdmin>
</template>

<script setup>
import { ref } from "vue";
import ListeReferentielAdmin from "./ListeReferentielAdmin.vue";
import DialogueRepartitionSalle from "./DialogueRepartitionSalle.vue";
import DialogueLieuEventim from "./DialogueLieuEventim.vue";
import { formaterInstant } from "../../composables/useDates.js";
import {
  AIDE_STATUTS_SALLES,
  decrireSuspicion,
  interpreterSuspicion,
} from "../../utils/statutReferentiel.js";
defineOptions({ name: "AdminSalles" });

defineProps({
  rechercheInitiale: { type: String, default: "" },
  idsInitiaux: { type: Array, default: () => [] },
});
const emit = defineEmits(["quitter-comparaison"]);

const repartition = ref(null);
const lieuEventim = ref(null);
const filtreSansSource = ref(false);
const filtreVille = ref("");
const filtreDept = ref("");

function filtresExtra() {
  return {
    ville: filtreVille.value,
    dept: filtreDept.value,
    sansSource: filtreSansSource.value ? "true" : "",
  };
}
</script>
