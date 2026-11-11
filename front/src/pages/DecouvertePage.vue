<template>
  <div>
    <div class="page-entete">
      <h1>Découverte</h1>
      <p class="page-sous-titre">
        Explorez tous les concerts disponibles par département.
      </p>
    </div>

    <div v-if="donnees" class="decouverte-entete">
      <span
        v-if="donnees.derniereSyncRegion"
        class="synchro-info texte-discret"
      >
        Dernière synchro :
        <time :datetime="donnees.derniereSyncRegion">{{
          formaterInstant(donnees.derniereSyncRegion)
        }}</time>
      </span>
      <span v-else class="synchro-info texte-discret">Jamais synchronisé</span>
      <button
        v-if="estAdmin"
        type="button"
        class="bouton bouton-petit bouton-secondaire"
        @click="actualiser"
      >
        <svg class="icone" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-refresh-cw" />
        </svg>
        Actualiser
      </button>
    </div>

    <div v-if="donnees" class="decouverte-depts">
      <span class="section-libelle">Départements :</span>
      <div class="chips">
        <a
          v-for="dept in donnees.deptsUtilisateur"
          :key="dept"
          href="#"
          :class="{ actif: deptsActifs.includes(dept) }"
          :aria-current="deptsActifs.includes(dept) ? 'true' : undefined"
          :title="
            syncDepts[dept]
              ? 'Dernière synchro : ' + syncDepts[dept]
              : 'Jamais synchronisé'
          "
          @click.prevent="toggleDept(dept)"
          >{{ dept }}</a
        >
      </div>
      <p v-if="donnees.deptsUtilisateur.length === 0" class="form-aide">
        Configurez vos départements dans
        <RouterLink to="/parametres#preferences">Paramètres</RouterLink> pour
        les pré-sélectionner.
      </p>
    </div>

    <div class="filtres-bascule">
      <button
        type="button"
        class="bouton bouton-secondaire bouton-petit"
        :aria-expanded="filtresVisibles ? 'true' : 'false'"
        aria-controls="filtres-decouverte"
        @click="filtresVisibles = !filtresVisibles"
      >
        <svg class="icone" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-sliders-horizontal" />
        </svg>
        <span
          >Filtres{{ nbFiltresActifs > 0 ? " · " + nbFiltresActifs : "" }}</span
        >
      </button>
    </div>

    <form
      id="filtres-decouverte"
      class="filtres-form"
      :class="{ visible: filtresVisibles }"
      @submit.prevent="appliquerFiltres"
    >
      <div class="filtres-ligne">
        <div class="filtres-recherche" role="search">
          <input
            v-model="qLocal"
            type="search"
            placeholder="Rechercher un artiste ou une salle…"
            aria-label="Rechercher un artiste ou une salle"
            autocomplete="off"
          />
          <button type="submit" class="bouton bouton-secondaire">
            <svg class="icone" aria-hidden="true">
              <use href="/icons/lucide-sprite.svg#icon-search" />
            </svg>
            <span>Rechercher</span>
          </button>
        </div>

        <label for="filtre-mois-dec" class="lecteur-ecran"
          >Filtrer par mois</label
        >
        <select
          id="filtre-mois-dec"
          v-model="moisLocal"
          @change="appliquerFiltres"
        >
          <option value="">Tous les mois</option>
          <option
            v-for="m in donnees?.moisDisponibles ?? []"
            :key="m"
            :value="m"
          >
            {{ m }}
          </option>
        </select>

        <label for="filtre-genre-dec" class="lecteur-ecran"
          >Filtrer par genre</label
        >
        <select
          id="filtre-genre-dec"
          v-model="genreLocal"
          @change="appliquerFiltres"
        >
          <option value="">Tous les genres</option>
          <option
            v-for="g in donnees?.genresDisponibles ?? []"
            :key="g"
            :value="g"
          >
            {{ g }}
          </option>
        </select>

        <label for="filtre-classification-dec" class="lecteur-ecran"
          >Filtrer par classification</label
        >
        <select
          id="filtre-classification-dec"
          v-model="classificationLocal"
          @change="appliquerFiltres"
        >
          <option value="">Toutes classifications</option>
          <option
            v-for="c in donnees?.classificationsDisponibles ?? []"
            :key="c"
            :value="c"
          >
            {{ c }}
          </option>
        </select>

        <label for="filtre-tri-dec" class="lecteur-ecran"
          >Trier les résultats</label
        >
        <select
          id="filtre-tri-dec"
          v-model="triLocal"
          @change="appliquerFiltres"
        >
          <option value="date">Par date</option>
          <option value="nouveaute">Par nouveauté</option>
        </select>

        <RouterLink to="/decouverte" class="bouton bouton-texte"
          >Réinitialiser</RouterLink
        >
      </div>
    </form>

    <p
      v-if="donnees"
      class="page-sous-titre"
      aria-live="polite"
      aria-atomic="true"
    >
      {{ donnees.totalConcerts }} concert{{
        donnees.totalConcerts !== 1 ? "s" : ""
      }}
      trouvé{{ donnees.totalConcerts !== 1 ? "s" : "" }}
    </p>

    <p
      v-for="message in messagesCouverture(departementsSansConcert)"
      :key="message.cle"
      class="alerte alerte-info"
      role="status"
    >
      {{ message.texte }}
    </p>

    <EtatChargement v-if="chargement && !donnees" />

    <div
      v-if="!chargement && (!donnees || donnees.concerts.length === 0)"
      class="etat-vide"
    >
      <p class="etat-vide-titre">Aucun concert avec ces filtres.</p>
      <p v-if="deptsActifs.length === 0" class="etat-vide-texte">
        Sélectionnez un département ci-dessus ou configurez vos départements
        dans
        <RouterLink to="/parametres#preferences">Paramètres</RouterLink>.
      </p>
      <p v-else class="etat-vide-texte">
        <RouterLink to="/decouverte">Réinitialiser les filtres</RouterLink>
      </p>
    </div>

    <div
      v-if="donnees && donnees.concerts.length > 0"
      class="decouverte-conteneur"
    >
      <div class="table-conteneur table-cartes">
        <table>
          <thead>
            <tr>
              <th scope="col">Artiste</th>
              <th scope="col">Date</th>
              <th scope="col">Où</th>
              <th scope="col">Genre</th>
              <th scope="col"><span class="lecteur-ecran">Lien</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="concert in donnees.concerts" :key="concert.id">
              <td data-label="Artiste">
                <span class="concert-artiste">{{ concert.artiste }}</span>
                <BadgesStatutConcert :statut="concert.statut" />
              </td>
              <CelluleDateConcert :date="concert.date" :heure="concert.heure" />
              <CelluleOuConcert :salle="concert.salle" :ville="concert.ville" />
              <td data-label="Genre" :class="{ 'cel-vide': !concert.genre }">
                {{ concert.genre }}
              </td>
              <td data-label="" :class="{ 'cel-vide': !concert.url }">
                <a
                  v-if="concert.url"
                  :href="concert.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="bouton bouton-secondaire bouton-petit"
                  aria-label="Acheter des billets (ouvre dans un nouvel onglet)"
                >
                  <svg class="icone" aria-hidden="true">
                    <use href="/icons/lucide-sprite.svg#icon-ticket" />
                  </svg>
                </a>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <PaginationListe
      v-if="donnees"
      :page="donnees.page"
      :total-pages="donnees.totalPages"
      @changer="changerPage"
    />
  </div>
</template>

<script setup>
import PaginationListe from "../components/PaginationListe.vue";
import BadgesStatutConcert from "../components/concert/BadgesStatutConcert.vue";
import CelluleDateConcert from "../components/concert/CelluleDateConcert.vue";
import CelluleOuConcert from "../components/concert/CelluleOuConcert.vue";
import { appelApi } from "../utils/api.js";
import { useDepartementsSansConcert } from "../composables/useDepartementsSansConcert.js";
defineOptions({ name: "DecouvertePage" });

import { computed, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { formaterInstant } from "../composables/useDates.js";
import { useToasts } from "../composables/useToasts.js";
import { sessionRef } from "../composables/useSession.js";
import EtatChargement from "../components/EtatChargement.vue";
import { useListeConcertsFiltree } from "../composables/useListeConcertsFiltree.js";

const route = useRoute();
const router = useRouter();
const { afficherToast } = useToasts();

const {
  chargement,
  donnees,
  filtresVisibles,
  qLocal,
  moisLocal,
  genreLocal,
  classificationLocal,
  triLocal,
  nbFiltresActifs,
  appliquerFiltres,
  changerPage,
} = useListeConcertsFiltree({
  url: "/api/decouverte",
  messageErreur: "Impossible de charger les concerts.",
  ajouterParametres(params) {
    deptsActifs.value.forEach((d) => params.append("dept", d));
  },
});

import { messagesCouverture } from "../utils/couverture.js";
const { departementsSansConcert, chargerDepartementsSansConcert } =
  useDepartementsSansConcert();
onMounted(chargerDepartementsSansConcert);

const deptsActifs = computed(() => {
  const d = route.query.dept;
  if (!d) return [];
  return Array.isArray(d) ? d : [d];
});

const syncDepts = computed(() => {
  if (!donnees.value?.syncParDept) return {};
  return Object.fromEntries(
    donnees.value.syncParDept
      .filter((s) => s.derniereExec)
      .map((s) => [s.dept, formaterInstant(s.derniereExec)]),
  );
});

// Contrôleur dédié au cycle de vie du composant (démontage) — distinct de controleurActuel
// ci-dessous, propre au fetch de la liste de concerts et à sa logique d'annulation du précédent.
const controleurComposant = new AbortController();

function toggleDept(dept) {
  const q = { ...route.query };
  delete q.page;
  const actuel = deptsActifs.value;
  const nouveau = actuel.includes(dept)
    ? actuel.filter((d) => d !== dept)
    : [...actuel, dept];
  if (nouveau.length > 0) {
    q.dept = nouveau;
  } else {
    delete q.dept;
  }
  router.push({ query: q });
}

// La récupération d'un département coûte du quota Ticketmaster : réservée aux admins.
const estAdmin = computed(() => sessionRef.value?.role === "admin");

async function actualiser() {
  try {
    const rep = await appelApi("/api/decouverte/fetcher", {
      method: "POST",
      signal: controleurComposant.signal,
    });
    if (!rep.ok) throw new Error();
    afficherToast(
      "Synchronisation déclenchée — résultats mis à jour dans quelques minutes.",
      "succes",
    );
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Impossible de déclencher la synchronisation.", "erreur");
  }
}

onUnmounted(() => controleurComposant.abort());
</script>

<style scoped>
.decouverte-entete {
  display: flex;
  align-items: center;
  gap: var(--espace-8);
  margin-bottom: var(--espace-8);
  flex-wrap: wrap;
}

.decouverte-depts {
  margin-bottom: var(--espace-8);
}
</style>
