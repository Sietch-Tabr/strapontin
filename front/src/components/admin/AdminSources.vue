<template>
  <section id="sources" class="admin-section">
    <EtatChargement v-if="chargement" />
    <template v-else>
      <div class="param-bloc">
        <h3 class="param-libelle">Sources de données</h3>
        <div class="sources-grille">
          <div
            v-for="source in sources"
            :key="source.id"
            :class="[
              'source-carte',
              { 'source-carte-large': source.id === 'ticketmaster' },
            ]"
          >
            <p class="source-nom">{{ source.nom }}</p>
            <span class="badge-source" :class="'badge-source-' + source.type">
              {{ source.type === "concerts" ? "Concerts" : "Métadonnées" }}
            </span>

            <template v-if="source.id === 'ticketmaster'">
              <div v-if="source.quotaTM" class="quota-tm-carte">
                <div class="quota-tm-chiffres">
                  <span>
                    <strong>{{ source.quotaTM.dispo }}</strong>
                    <span class="quota-tm-sep">{{
                      " / " + source.quotaTM.total
                    }}</span>
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
                  :class="
                    source.etatQuota === 'critique'
                      ? 'badge-quota-critique'
                      : 'badge-quota-attention'
                  "
                >
                  {{
                    source.etatQuota === "critique"
                      ? "Quota critique (moins de 10 %)"
                      : "Quota faible (moins de 20 %)"
                  }}
                </p>
                <p v-if="source.quotaTM.reset > 0" class="param-aide">
                  Remise à zéro le {{ formaterTs(source.quotaTM.reset) }}
                </p>
                <p v-if="source.quotaTM.misAJour" class="param-aide">
                  Actualisé le {{ formaterInstant(source.quotaTM.misAJour) }}
                </p>
              </div>
              <p v-else class="etat-vide-texte">
                Quota non disponible (aucun fetch effectué).
              </p>
              <p class="source-carte-detail param-aide">
                {{ source.nbSallesConfigurees ?? 0 }} salle{{
                  source.nbSallesConfigurees > 1 ? "s" : ""
                }}
                et {{ source.nbArtistesConfigures ?? 0 }} artiste{{
                  source.nbArtistesConfigures > 1 ? "s" : ""
                }}
                suivis via cette source.
              </p>
            </template>

            <template v-else-if="source.id === 'eventim'">
              <p class="source-carte-detail">
                {{ source.nbSallesConfigurees ?? 0 }} salle{{
                  source.nbSallesConfigurees > 1 ? "s" : ""
                }}
                et {{ source.nbArtistesConfigures ?? 0 }} artiste{{
                  source.nbArtistesConfigures > 1 ? "s" : ""
                }}
                suivis via cette source.
              </p>
            </template>

            <template v-else-if="source.id === 'deezer'">
              <p class="source-carte-detail">
                <span v-if="source.nbArtistesConfigures > 0">
                  {{ source.nbArtistesConfigures }} artiste{{
                    source.nbArtistesConfigures > 1 ? "s" : ""
                  }}
                  avec identifiant Deezer.
                </span>
                <span v-else class="etat-vide-texte"
                  >Aucun artiste avec identifiant Deezer.</span
                >
              </p>
            </template>

            <template v-else-if="source.id === 'wikidata'">
              <p class="source-carte-detail">
                {{ source.nbSallesConfigurees ?? 0 }} salle{{
                  source.nbSallesConfigurees > 1 ? "s" : ""
                }}
                et {{ source.nbArtistesConfigures ?? 0 }} artiste{{
                  source.nbArtistesConfigures > 1 ? "s" : ""
                }}
                avec identifiant Wikidata.
              </p>
            </template>

            <p
              v-if="source.dernierFetch"
              class="source-carte-detail param-aide"
            >
              Dernier fetch : {{ formaterInstant(source.dernierFetch) }}
            </p>
            <p
              v-else-if="source.id !== 'deezer' && source.id !== 'wikidata'"
              class="source-carte-detail param-aide"
            >
              Jamais exécuté
            </p>
          </div>
        </div>
      </div>

      <div class="param-bloc">
        <div class="source-vue-entete">
          <h3 class="param-libelle">Salles</h3>
          <div
            class="source-vue-bascule"
            role="group"
            aria-label="Vue des salles"
          >
            <button
              type="button"
              class="bouton bouton-petit"
              :class="vueEventim ? 'bouton-secondaire' : 'bouton-primaire'"
              @click="basculerVue(false)"
            >
              Sans source
            </button>
            <button
              type="button"
              class="bouton bouton-petit"
              :class="vueEventim ? 'bouton-primaire' : 'bouton-secondaire'"
              @click="basculerVue(true)"
            >
              Avec lieu Eventim
            </button>
          </div>
        </div>
        <p class="param-aide">
          <template v-if="!vueEventim"
            >Salles sans identifiant Ticketmaster ni Eventim — renseignez le nom
            exact du lieu tel qu'Eventim l'affiche, puis testez-le.</template
          >
          <template v-else
            >Salles interrogées sur Eventim par le nom exact de leur lieu. Un
            nom inexact ne renvoie aucun concert : testez avant
            d'enregistrer.</template
          >
        </p>

        <EtatChargement v-if="chargementSalles" />
        <template v-else>
          <!-- Hors du v-if salles.length : un filtrage réduisant le résultat à zéro ne doit pas
             faire disparaître le champ, sinon plus aucun moyen de revenir en arrière. -->
          <input
            v-model="recherche"
            type="search"
            class="source-recherche"
            placeholder="Rechercher une salle…"
            aria-label="Rechercher une salle"
            @input="
              page = 0;
              chargerSalles();
            "
          />
          <div v-if="salles.length">
            <div class="table-conteneur">
              <table
                id="table-sources-salles"
                :aria-label="
                  vueEventim
                    ? 'Salles avec lieu Eventim'
                    : 'Salles sans Ticketmaster ni Eventim'
                "
              >
                <thead>
                  <tr>
                    <th>Salle</th>
                    <th>Ville / Dép.</th>
                    <th>Lieu Eventim</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="salle in salles" :key="salle.salleId">
                    <td class="source-salle-nom">{{ salle.nom }}</td>
                    <td class="source-salle-loc">
                      <span v-if="salle.ville || salle.dept">{{
                        [salle.ville, salle.dept].filter(Boolean).join(" ")
                      }}</span>
                      <span v-else class="etat-vide-texte">—</span>
                    </td>
                    <td>
                      <div class="source-lieu-inline">
                        <input
                          v-model="lieuxEdites[salle.salleId]"
                          type="text"
                          class="source-lieu-input"
                          maxlength="200"
                          placeholder="Nom exact chez Eventim"
                          :aria-label="'Lieu Eventim pour ' + salle.nom"
                          @input="statutTester[salle.salleId] = null"
                          @keydown.enter="sauvegarderLieu(salle)"
                        />
                        <span
                          v-if="statutTester[salle.salleId]"
                          class="source-test-statut"
                          :class="
                            statutTester[salle.salleId]?.startsWith('ok')
                              ? 'source-test-ok'
                              : 'source-test-ko'
                          "
                          aria-live="polite"
                        >
                          {{ formatStatutTest(statutTester[salle.salleId]) }}
                        </span>
                      </div>
                    </td>
                    <td class="source-actions">
                      <button
                        type="button"
                        class="bouton bouton-secondaire bouton-petit"
                        :disabled="enCours[salle.salleId + ':lieu']"
                        :aria-label="
                          'Enregistrer le lieu Eventim de ' + salle.nom
                        "
                        @click="sauvegarderLieu(salle)"
                      >
                        Enregistrer
                      </button>
                      <button
                        type="button"
                        class="bouton bouton-secondaire bouton-petit"
                        :disabled="
                          enCours[salle.salleId + ':test'] ||
                          !lieuCourant(salle)
                        "
                        :aria-label="'Tester le lieu Eventim de ' + salle.nom"
                        @click="testerLieu(salle)"
                      >
                        Tester
                      </button>
                      <button
                        v-if="vueEventim"
                        type="button"
                        class="bouton bouton-secondaire bouton-petit"
                        :disabled="enCours[salle.salleId + ':fetch']"
                        :aria-label="'Déclencher un fetch pour ' + salle.nom"
                        @click="fetchSalle(salle)"
                      >
                        Fetch
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
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
                >Page {{ page + 1 }} / {{ totalPages }} ({{
                  total
                }}
                salles)</span
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
          </div>
          <p v-else class="etat-vide-texte">
            <template v-if="recherche.trim()"
              >Aucune salle ne correspond à la recherche.</template
            >
            <template v-else-if="!vueEventim"
              >Toutes les salles ont un identifiant source (Ticketmaster ou
              Eventim).</template
            >
            <template v-else>Aucune salle avec lieu Eventim.</template>
          </p>
        </template>
      </div>
    </template>
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "AdminSources" });

import { ref, shallowRef, reactive, onMounted, onUnmounted } from "vue";
import EtatChargement from "../EtatChargement.vue";
import { useToasts } from "../../composables/useToasts.js";
import { formaterInstant, formaterTs } from "../../composables/useDates.js";

const { afficherToast } = useToasts();

const chargement = ref(true);
const chargementSalles = ref(true);
const sources = shallowRef([]);
const salles = shallowRef([]);
const page = ref(0);
const totalPages = ref(1);
const total = ref(0);
const vueEventim = ref(false);
const recherche = ref("");
const lieuxEdites = reactive({});
const statutTester = reactive({});
const enCours = reactive({});

const controleur = new AbortController();
// controleurSalles : recréé à chaque appel de chargerSalles() — un changement rapide de vue/page
// (basculerVue/allerPage) annule sinon seulement la requête précédente au démontage, laissant
// deux réponses de pages différentes s'appliquer dans le désordre.
let controleurSallesActuel = null;

function pctQuota(source) {
  if (!source.quotaTM || source.quotaTM.total === 0) return 0;
  return Math.round((source.quotaTM.dispo * 100) / source.quotaTM.total);
}

function classeBarre(source) {
  const p = pctQuota(source);
  if (p > 30) return "barre-ok";
  if (p > 10) return "barre-avertissement";
  return "barre-danger";
}

function lieuCourant(salle) {
  return (lieuxEdites[salle.salleId] ?? "").trim();
}

async function charger() {
  try {
    const rep = await appelApi("/api/admin/sources", {
      signal: controleur.signal,
    });
    if (!rep.ok) return;
    const d = await lireJson(rep);
    sources.value = d.sources ?? [];
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Erreur lors du chargement des sources.", "erreur");
  } finally {
    chargement.value = false;
  }
  await chargerSalles();
}

async function chargerSalles() {
  controleurSallesActuel?.abort();
  const controleurCourant = new AbortController();
  controleurSallesActuel = controleurCourant;

  chargementSalles.value = true;
  try {
    const params = new URLSearchParams();
    params.set("eventim", vueEventim.value);
    params.set("page", page.value);
    if (recherche.value.trim()) params.set("nom", recherche.value.trim());

    const rep = await appelApi(`/api/admin/sources/salles?${params}`, {
      signal: controleurCourant.signal,
    });
    if (!rep.ok) return;
    const d = await lireJson(rep);
    salles.value = d.salles ?? [];
    page.value = d.page ?? 0;
    totalPages.value = d.totalPages ?? 1;
    total.value = d.total ?? 0;
    for (const s of salles.value) {
      if (lieuxEdites[s.salleId] === undefined) {
        lieuxEdites[s.salleId] = s.lieuEventim ?? "";
      }
    }
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Erreur lors du chargement des salles.", "erreur");
  } finally {
    if (controleurSallesActuel === controleurCourant)
      chargementSalles.value = false;
  }
}

function basculerVue(eventim) {
  vueEventim.value = eventim;
  page.value = 0;
  recherche.value = "";
  chargerSalles();
}

function allerPage(n) {
  page.value = Math.max(0, Math.min(n, totalPages.value - 1));
  chargerSalles();
}

async function sauvegarderLieu(salle) {
  const cle = salle.salleId + ":lieu";
  enCours[cle] = true;
  try {
    const rep = await appelApi(
      `/api/admin/salles/${salle.salleId}/lieu-eventim`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ libelle: lieuCourant(salle) }),
        signal: controleur.signal,
      },
    );
    if (rep.ok) {
      afficherToast(
        lieuCourant(salle)
          ? "Lieu Eventim enregistré."
          : "Lieu Eventim retiré.",
        "succes",
      );
      await chargerSalles();
    } else {
      const data = await lireJson(rep);
      afficherToast(
        data.erreur || "Erreur lors de l'enregistrement du lieu.",
        "erreur",
      );
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours[cle] = false;
  }
}

async function testerLieu(salle) {
  const libelle = lieuCourant(salle);
  if (!libelle) return;
  const cle = salle.salleId + ":test";
  enCours[cle] = true;
  statutTester[salle.salleId] = null;
  try {
    const rep = await appelApi(
      `/api/admin/salles/${salle.salleId}/tester-lieu-eventim`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ libelle }),
        signal: controleur.signal,
      },
    );
    if (rep.ok) {
      const data = await lireJson(rep);
      statutTester[salle.salleId] = `ok:${data.nbConcerts ?? 0}`;
    } else {
      statutTester[salle.salleId] = "ko";
    }
  } catch (e) {
    if (e.name !== "AbortError") {
      statutTester[salle.salleId] = "ko";
    }
  } finally {
    enCours[cle] = false;
  }
}

function formatStatutTest(statut) {
  if (!statut) return "";
  if (statut === "ko") return "Eventim injoignable ✗";
  const nb = parseInt(statut.split(":")[1] ?? "0", 10);
  return nb > 0 ? `✓ ${nb} concert${nb > 1 ? "s" : ""}` : "✓ aucun concert";
}

async function fetchSalle(salle) {
  const cle = salle.salleId + ":fetch";
  enCours[cle] = true;
  try {
    const rep = await appelApi(`/api/admin/salles/${salle.salleId}/fetch`, {
      method: "POST",
      signal: controleur.signal,
    });
    if (rep.ok) {
      afficherToast("Fetch déclenché.", "succes");
    } else {
      const data = await lireJson(rep);
      afficherToast(data.erreur || "Erreur lors du déclenchement.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours[cle] = false;
  }
}

onMounted(charger);
onUnmounted(() => {
  controleur.abort();
  controleurSallesActuel?.abort();
});
</script>

<style scoped>
.quota-tm-carte {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 1rem;
}

.quota-tm-chiffres {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 0.5rem;
  font-size: 0.875rem;
}

.quota-tm-chiffres strong {
  font-size: 1.5rem;
  font-weight: 700;
}

.quota-tm-sep {
  color: var(--fg-muted);
  font-size: 0.875rem;
  margin-left: 0.25rem;
}

.quota-tm-unite {
  color: var(--fg-muted);
  font-size: 0.75rem;
  margin-left: 0.375rem;
}

.quota-tm-pct {
  font-weight: 600;
  font-size: 0.875rem;
}

.quota-tm-barre-fond {
  height: 0.5rem;
  background: var(--bg-muted);
  border-radius: 9999px;
  overflow: hidden;
  margin-bottom: 0.5rem;
}

.quota-tm-barre-remplissage {
  height: 100%;
  border-radius: 9999px;
  transition: width 0.3s;
  width: 0%;
}

.badge-quota-critique {
  color: var(--danger);
}

.badge-quota-attention {
  color: var(--warning);
}

.sources-grille {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
  margin-top: 0.5rem;
}

@media (width >= 641px) {
  .sources-grille {
    grid-template-columns: repeat(3, 1fr);
  }
}

.source-carte-large {
  grid-column: 1 / -1;
}

.source-carte {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.875rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.source-nom {
  font-weight: 600;
  font-size: 0.9rem;
  margin: 0 0 0.25rem;
}

.source-vue-bascule {
  display: flex;
  gap: 0.375rem;
}

.source-carte-detail {
  font-size: 0.8rem;
  color: var(--fg-muted);
  margin: 0;
}

.source-lieu-inline {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex-wrap: wrap;
}

.source-lieu-input {
  font-size: 0.82rem;
  padding: 0.2rem 0.45rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg);
  color: var(--fg);
  width: 9rem;
}

@media (width >= 641px) {
  .source-lieu-inline {
    flex-wrap: nowrap;
  }

  .source-lieu-input {
    width: 16rem;
  }
}

.source-test-statut {
  font-size: 0.78rem;
  font-weight: 600;
}

.source-test-ok {
  color: #166534;
}

.source-test-ko {
  color: #991b1b;
}

.source-salle-loc {
  font-size: 0.85rem;
  color: var(--texte-secondaire, #6b7280);
  white-space: nowrap;
}
</style>
