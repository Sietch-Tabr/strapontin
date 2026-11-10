<template>
  <div>
    <div class="page-entete">
      <h1>Mes spectacles</h1>
      <p class="page-sous-titre">
        Tous les concerts à venir de vos artistes et de vos salles, à filtrer
        par source, période ou état.
      </p>
    </div>
    <p v-if="donnees" class="texte-secondaire" role="status">
      {{ donnees.totalConcerts }} concert{{
        donnees.totalConcerts !== 1 ? "s" : ""
      }}
      à venir
    </p>

    <div class="chips">
      <a
        href="#"
        :class="{ actif: sourceArtistesActive }"
        :aria-current="sourceArtistesActive ? 'true' : undefined"
        @click.prevent="toggleSource('artistes')"
        >Mes artistes</a
      >
      <a
        href="#"
        :class="{ actif: sourceSallesActive }"
        :aria-current="sourceSallesActive ? 'true' : undefined"
        @click.prevent="toggleSource('salles')"
        >Mes salles</a
      >
      <a
        href="#"
        :class="{ actif: quinzaineActive }"
        :aria-current="quinzaineActive ? 'true' : undefined"
        @click.prevent="toggleQuinzaine"
        >15 prochains jours</a
      >
      <a
        href="#"
        :class="{ actif: masquesActive }"
        :aria-current="masquesActive ? 'true' : undefined"
        @click.prevent="toggleMasques"
        >Masqués</a
      >
      <a
        href="#"
        :class="{ actif: indisponiblesActive }"
        :aria-current="indisponiblesActive ? 'true' : undefined"
        @click.prevent="toggleIndisponibles"
        >Indisponibles</a
      >
    </div>

    <div
      v-if="quinzaineActive && !!route.query.mois"
      class="alerte alerte-info"
      role="status"
    >
      Le filtre « 15 prochains jours » et le filtre par mois sont actifs
      simultanément — les résultats affichés correspondent à leur intersection.
    </div>

    <div class="filtres-bascule">
      <button
        id="btn-filtres-vue"
        type="button"
        class="bouton bouton-secondaire bouton-petit"
        :aria-expanded="filtresVisibles ? 'true' : 'false'"
        aria-controls="filtres-spectacles"
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
      id="filtres-spectacles"
      class="filtres-form"
      :class="{ visible: filtresVisibles }"
      @submit.prevent="appliquerFiltres"
    >
      <div class="filtres-ligne">
        <div class="filtres-recherche" role="search">
          <input
            v-model="qLocal"
            type="search"
            placeholder="Rechercher un artiste…"
            aria-label="Rechercher un artiste"
            autocomplete="off"
          />
          <button type="submit" class="bouton bouton-secondaire">
            <svg class="icone" aria-hidden="true">
              <use href="/icons/lucide-sprite.svg#icon-search" />
            </svg>
            <span>Rechercher</span>
          </button>
        </div>

        <label for="filtre-mois" class="lecteur-ecran">Filtrer par mois</label>
        <select id="filtre-mois" v-model="moisLocal" @change="appliquerFiltres">
          <option value="">Tous les mois</option>
          <option
            v-for="m in donnees?.moisDisponibles ?? []"
            :key="m"
            :value="m"
          >
            {{ m }}
          </option>
        </select>

        <label for="filtre-genre" class="lecteur-ecran"
          >Filtrer par genre</label
        >
        <select
          id="filtre-genre"
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

        <label for="filtre-classification" class="lecteur-ecran"
          >Filtrer par classification</label
        >
        <select
          id="filtre-classification"
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

        <label for="filtre-tri" class="lecteur-ecran"
          >Trier les résultats</label
        >
        <select id="filtre-tri" v-model="triLocal" @change="appliquerFiltres">
          <option value="date">Par date</option>
          <option value="nouveaute">Par nouveauté</option>
        </select>

        <RouterLink to="/spectacles" class="bouton bouton-texte"
          >Réinitialiser</RouterLink
        >
      </div>
    </form>

    <EtatChargement v-if="chargement && !donnees" />

    <div v-if="donnees && donnees.concerts.length === 0" class="etat-vide">
      <p v-if="!donnees.aSuivi" class="etat-vide-titre">
        Aucun artiste ni salle suivi.
      </p>
      <p v-else class="etat-vide-titre">Aucun concert avec ces filtres.</p>
      <p v-if="!donnees.aSuivi" class="etat-vide-texte">
        <RouterLink to="/artistes">Ajouter des artistes</RouterLink> ou
        <RouterLink to="/salles">des salles</RouterLink> pour commencer.
      </p>
      <p v-else class="etat-vide-texte">
        <RouterLink to="/spectacles">Réinitialiser les filtres</RouterLink>
      </p>
    </div>

    <div
      v-if="donnees && donnees.concerts.length > 0"
      class="spectacles-conteneur"
    >
      <div class="table-conteneur table-cartes">
        <table>
          <thead>
            <tr>
              <th scope="col">Artiste</th>
              <th scope="col">Date</th>
              <th scope="col">Où</th>
              <th scope="col"><span class="lecteur-ecran">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="concert in donnees.concerts" :key="concert.id">
              <td data-label="Artiste">
                <span class="concert-artiste">{{ concert.artiste }}</span>
                <span v-if="concert.estNew" class="badge badge-nouveau"
                  >Nouveau</span
                >
                <span
                  v-if="concert.statut === 'cancelled'"
                  class="badge badge-annule"
                  >Annulé</span
                >
                <span
                  v-if="
                    concert.statut === 'rescheduled' ||
                    concert.statut === 'postponed'
                  "
                  class="badge badge-reporte"
                  >Reporté</span
                >
                <span
                  v-if="concert.statut === 'offsale'"
                  class="badge badge-complet"
                  >Complet</span
                >
                <span
                  v-if="concert.statut === 'presale'"
                  class="badge badge-prevente"
                  >{{
                    concert.presaleDebut
                      ? "Prévente le " + formaterPresale(concert.presaleDebut)
                      : "Prévente"
                  }}</span
                >
                <span
                  v-if="concert.informationNonConfirmee"
                  class="badge badge-a-verifier"
                  title="Ce concert n'a plus été retrouvé lors des dernières vérifications automatiques — à confirmer avant de se déplacer."
                  >À vérifier</span
                >
              </td>
              <td data-label="Date">
                <time class="concert-date" :datetime="concert.date">{{
                  formaterAvecHeure(concert.date, concert.heure)
                }}</time>
              </td>
              <td
                data-label="Où"
                :class="{ 'cel-vide': !concert.salle && !concert.ville }"
              >
                <span v-if="concert.salle">{{ concert.salle }}</span>
                <span
                  v-if="concert.salle && concert.ville"
                  class="concert-separateur"
                >
                  ·
                </span>
                <span v-if="concert.ville" class="concert-ville">{{
                  concert.ville
                }}</span>
              </td>
              <td data-label="" class="actions-cellule">
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
                <button
                  v-if="masquesActive"
                  type="button"
                  class="bouton bouton-petit bouton-texte"
                  @click="demasquerConcert(concert)"
                >
                  Réafficher
                </button>
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
import { appelApi } from "../utils/api.js";
defineOptions({ name: "SpectaclesPage" });

import { computed, onUnmounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { formaterAvecHeure } from "../composables/useDates.js";
import { useToasts } from "../composables/useToasts.js";
import {
  choixExplicite,
  indisponiblesAffiches,
  messageSansAjout,
  valeurApresBascule,
} from "../utils/filtreIndisponibles.js";
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
  url: "/api/spectacles",
  messageErreur: "Impossible de charger les spectacles.",
  ajouterParametres(params) {
    if (quinzaineActive.value) params.set("quinzaine", "true");
    if (masquesActive.value) params.set("masques", "true");
    sources.value.forEach((s) => params.append("source", s));
    const indisponibles = choixExplicite(route.query.indisponibles);
    if (indisponibles) params.set("indisponibles", indisponibles);
  },
});

const sources = computed(() => {
  const s = route.query.source;
  if (!s) return [];
  return Array.isArray(s) ? s : [s];
});

const sourceArtistesActive = computed(
  () => sources.value.includes("artistes") && !sources.value.includes("salles"),
);
const sourceSallesActive = computed(
  () => !sources.value.includes("artistes") && sources.value.includes("salles"),
);
const quinzaineActive = computed(() => route.query.quinzaine === "true");
const masquesActive = computed(() => route.query.masques === "true");
const indisponiblesActive = computed(() =>
  indisponiblesAffiches(route.query.indisponibles, donnees.value),
);

const controleurComposant = new AbortController();

function toggleSource(source) {
  const q = { ...route.query };
  delete q.page;
  const active =
    source === "artistes"
      ? sourceArtistesActive.value
      : sourceSallesActive.value;
  if (active) {
    delete q.source;
  } else {
    q.source = source;
  }
  router.push({ query: q });
}

function toggleQuinzaine() {
  const q = { ...route.query };
  delete q.page;
  if (quinzaineActive.value) {
    delete q.quinzaine;
  } else {
    q.quinzaine = "true";
  }
  router.push({ query: q });
}

function toggleMasques() {
  const q = { ...route.query };
  delete q.page;
  if (masquesActive.value) {
    delete q.masques;
  } else {
    q.masques = "true";
  }
  router.push({ query: q });
}

function toggleIndisponibles() {
  const q = { ...route.query };
  delete q.page;
  const message = messageSansAjout(indisponiblesActive.value, donnees.value);
  if (message) afficherToast(message, "info");
  q.indisponibles = valeurApresBascule(indisponiblesActive.value);
  router.push({ query: q });
}

async function demasquerConcert(concert) {
  try {
    const rep = await appelApi(`/api/spectacles/matches/${concert.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ masquer: false }),
      signal: controleurComposant.signal,
    });
    if (!rep.ok) throw new Error();
    donnees.value.concerts.splice(donnees.value.concerts.indexOf(concert), 1);
    donnees.value.totalConcerts--;
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Impossible de réafficher ce concert.", "erreur");
  }
}

function formaterPresale(instantIso) {
  if (!instantIso) return "";
  const d = new Date(instantIso);
  return (
    String(d.getDate()).padStart(2, "0") +
    "/" +
    String(d.getMonth() + 1).padStart(2, "0")
  );
}

onUnmounted(() => controleurComposant.abort());
</script>

<style scoped>
.actions-cellule {
  vertical-align: middle;
  text-align: right;
  white-space: nowrap;
}

.actions-cellule > * {
  display: inline-flex;
  align-items: center;
  vertical-align: middle;
}

.actions-cellule > * + * {
  margin-left: var(--espace-3);
}
</style>
