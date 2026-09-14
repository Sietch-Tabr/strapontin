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

            <template v-else-if="source.id === 'digitick'">
              <p class="source-carte-detail">
                <span v-if="source.nbSallesConfigurees > 0">
                  {{ source.nbSallesConfigurees }} salle{{
                    source.nbSallesConfigurees > 1 ? "s" : ""
                  }}
                  configurée{{ source.nbSallesConfigurees > 1 ? "s" : "" }}
                </span>
                <span v-else class="etat-vide-texte"
                  >Aucune salle configurée (voir table ci-dessous).</span
                >
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
              v-else-if="
                source.id !== 'deezer' &&
                source.id !== 'digitick' &&
                source.id !== 'wikidata'
              "
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
              :class="vueDigitick ? 'bouton-secondaire' : 'bouton-primaire'"
              @click="basculerVue(false)"
            >
              Sans source
            </button>
            <button
              type="button"
              class="bouton bouton-petit"
              :class="vueDigitick ? 'bouton-primaire' : 'bouton-secondaire'"
              @click="basculerVue(true)"
            >
              Avec Digitick
            </button>
          </div>
        </div>
        <p class="param-aide">
          <template v-if="!vueDigitick"
            >Salles sans identifiant Ticketmaster ni Eventim — configurez leur
            slug Digitick et vérifiez l'URL.</template
          >
          <template v-else
            >Salles gérées manuellement avec leur slug Digitick. Elles
            apparaissent dans la recherche « Mes salles » même sans
            concerts.</template
          >
        </p>

        <template v-if="vueDigitick">
          <div class="source-digitick-actions">
            <button
              type="button"
              class="bouton bouton-secondaire bouton-petit"
              @click="ouvrirAjout"
            >
              Ajouter une salle
            </button>
          </div>
        </template>

        <EtatChargement v-if="chargementSalles" />
        <template v-else>
          <!-- Hors du v-if salles.length : un filtrage réduisant le résultat à zéro ne doit pas
             faire disparaître le champ, sinon plus aucun moyen de revenir en arrière (#1172). -->
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
                  vueDigitick
                    ? 'Salles avec Digitick'
                    : 'Salles sans Ticketmaster'
                "
              >
                <thead>
                  <tr>
                    <th>Salle</th>
                    <th v-if="vueDigitick">Ville / CP</th>
                    <th>Slug Digitick</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="salle in salles" :key="salle.salleId">
                    <td class="source-salle-nom">{{ salle.nom }}</td>
                    <td v-if="vueDigitick" class="source-salle-loc">
                      <span v-if="salle.ville || salle.dept">{{
                        [salle.ville, salle.dept].filter(Boolean).join(" ")
                      }}</span>
                      <span v-else class="etat-vide-texte">—</span>
                    </td>
                    <td>
                      <div class="source-slug-inline">
                        <input
                          :value="slugsEdites[salle.salleId]"
                          type="text"
                          class="source-slug-input"
                          :placeholder="slugDeduit(salle.nom)"
                          :aria-label="'Slug Digitick pour ' + salle.nom"
                          @input="mettreAJourSlug(salle, $event.target.value)"
                          @keydown.enter="sauvegarderSlug(salle)"
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
                        :disabled="enCours[salle.salleId + ':slug']"
                        :aria-label="'Enregistrer le slug pour ' + salle.nom"
                        @click="sauvegarderSlug(salle)"
                      >
                        Enregistrer
                      </button>
                      <button
                        type="button"
                        class="bouton bouton-secondaire bouton-petit"
                        :disabled="
                          enCours[salle.salleId + ':test'] ||
                          !slugCourant(salle)
                        "
                        :aria-label="'Tester le slug Digitick de ' + salle.nom"
                        @click="testerSlug(salle)"
                      >
                        Tester
                      </button>
                      <template v-if="vueDigitick">
                        <button
                          type="button"
                          class="bouton bouton-secondaire bouton-petit"
                          :disabled="
                            enCours[salle.salleId + ':fetch'] ||
                            !slugCourant(salle)
                          "
                          :aria-label="'Déclencher un fetch pour ' + salle.nom"
                          @click="fetchSalle(salle)"
                        >
                          Fetch
                        </button>
                        <button
                          type="button"
                          class="bouton bouton-secondaire bouton-petit"
                          :aria-label="'Éditer ' + salle.nom"
                          @click="ouvrirEdition(salle)"
                        >
                          Éditer
                        </button>
                        <button
                          type="button"
                          class="bouton bouton-danger-texte bouton-petit"
                          :disabled="enCours[salle.salleId + ':suppr']"
                          :aria-label="'Supprimer ' + salle.nom"
                          @click="demanderSuppression(salle)"
                        >
                          Supprimer
                        </button>
                      </template>
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
            <template v-else-if="!vueDigitick"
              >Toutes les salles ont un identifiant source (Ticketmaster ou
              Eventim).</template
            >
            <template v-else
              >Aucune salle Digitick configurée. Cliquez sur « Ajouter une salle
              » pour commencer.</template
            >
          </p>
        </template>
      </div>
    </template>
  </section>

  <dialog
    ref="dialogAjoutRef"
    aria-labelledby="titre-ajout-digitick"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-ajout-digitick" class="dialog-titre">
        Ajouter une salle Digitick
      </h2>
    </div>
    <form @submit.prevent="creerSalleDigitick">
      <div class="champ">
        <label for="ajout-nom" class="champ-label"
          >Nom <span aria-hidden="true">*</span></label
        >
        <input
          id="ajout-nom"
          v-model="ajout.nom"
          type="text"
          required
          maxlength="200"
          autocomplete="off"
          placeholder="L'Empreinte"
        />
      </div>
      <div class="champ">
        <label for="ajout-slug" class="champ-label"
          >Slug Digitick <span aria-hidden="true">*</span></label
        >
        <div class="source-slug-inline">
          <input
            id="ajout-slug"
            :value="ajout.slug"
            type="text"
            required
            maxlength="100"
            autocomplete="off"
            placeholder="lempreinte"
            @input="
              ajout.slug = extraireSlugDepuisUrl($event.target.value);
              ajoutSlugStatut = null;
            "
          />
          <span
            v-if="ajoutSlugStatut"
            class="source-test-statut"
            :class="
              ajoutSlugStatut?.startsWith('ok')
                ? 'source-test-ok'
                : 'source-test-ko'
            "
            aria-live="polite"
            >{{ formatStatutTest(ajoutSlugStatut) }}</span
          >
        </div>
        <p class="param-aide">
          Collez l'URL Digitick ou saisissez le slug directement — ex.
          <code>lempreinte</code> ou <code>index-css5-lempreinte-pg1.html</code>
        </p>
      </div>
      <div class="champ">
        <label for="ajout-ville" class="champ-label">Ville</label>
        <input
          id="ajout-ville"
          v-model="ajout.ville"
          type="text"
          maxlength="100"
          autocomplete="off"
          placeholder="Savigny-le-Temple"
        />
      </div>
      <div class="champ">
        <label for="ajout-cp" class="champ-label">Département</label>
        <input
          id="ajout-cp"
          v-model="ajout.dept"
          type="text"
          maxlength="3"
          autocomplete="off"
          placeholder="77"
        />
      </div>
      <div class="dialog-pied">
        <button
          type="submit"
          class="bouton bouton-secondaire"
          :disabled="enCours.ajout"
        >
          Ajouter
        </button>
        <button
          type="button"
          class="bouton bouton-secondaire"
          :disabled="enCours.ajoutTest || !ajout.slug"
          @click="testerSlugAjout"
        >
          Tester
        </button>
        <button
          type="button"
          class="bouton bouton-texte"
          @click="dialogAjoutRef.close()"
        >
          Annuler
        </button>
      </div>
    </form>
  </dialog>

  <dialog
    ref="dialogEditionRef"
    aria-labelledby="titre-edition-digitick"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-edition-digitick" class="dialog-titre">
        Modifier la salle
      </h2>
    </div>
    <form @submit.prevent="sauvegarderEdition">
      <div class="champ">
        <label for="edit-nom" class="champ-label">Nom</label>
        <input
          id="edit-nom"
          v-model="edition.nom"
          type="text"
          maxlength="200"
          autocomplete="off"
        />
      </div>
      <div class="champ">
        <label for="edit-ville" class="champ-label">Ville</label>
        <input
          id="edit-ville"
          v-model="edition.ville"
          type="text"
          maxlength="100"
          autocomplete="off"
        />
      </div>
      <div class="champ">
        <label for="edit-cp" class="champ-label">Département</label>
        <input
          id="edit-cp"
          v-model="edition.dept"
          type="text"
          maxlength="3"
          autocomplete="off"
          placeholder="77"
        />
      </div>
      <div class="dialog-pied">
        <button
          type="submit"
          class="bouton bouton-secondaire"
          :disabled="enCours.edition"
        >
          Enregistrer
        </button>
        <button
          type="button"
          class="bouton bouton-texte"
          @click="dialogEditionRef.close()"
        >
          Annuler
        </button>
      </div>
    </form>
  </dialog>

  <dialog
    ref="dialogSupprRef"
    aria-labelledby="titre-suppr-digitick"
    aria-modal="true"
  >
    <div class="dialog-entete">
      <h2 id="titre-suppr-digitick" class="dialog-titre">Supprimer la salle</h2>
    </div>
    <p>
      Supprimer « <strong>{{ salleASupprimer?.nom }}</strong> » et son slug
      Digitick ?<br />Cette action est irréversible si la salle n'a pas de
      concerts.
    </p>
    <div class="dialog-pied">
      <button
        type="button"
        class="bouton bouton-danger"
        :disabled="enCours[salleASupprimer?.salleId + ':suppr']"
        @click="confirmerSuppression"
      >
        Supprimer
      </button>
      <button
        type="button"
        class="bouton bouton-texte"
        @click="dialogSupprRef.close()"
      >
        Annuler
      </button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: "AdminSources" });

import { ref, shallowRef, reactive, onMounted, onUnmounted } from "vue";
import EtatChargement from "../EtatChargement.vue";
import { useToasts } from "../../composables/useToasts.js";
import { formaterInstant, formaterTs } from "../../composables/useDates.js";
import { corpsJsonOuVide } from "../../utils/reponseJson.js";
import { extraireSlugDepuisUrl, slugDeduit } from "../../utils/digitickSlug.js";

const { afficherToast } = useToasts();

const chargement = ref(true);
const chargementSalles = ref(true);
const sources = shallowRef([]);
const salles = shallowRef([]);
const page = ref(0);
const totalPages = ref(1);
const total = ref(0);
const vueDigitick = ref(false);
const recherche = ref("");
const slugsEdites = reactive({});
const statutTester = reactive({});
const enCours = reactive({});

const ajout = reactive({ nom: "", slug: "", ville: "", dept: "" });
const ajoutSlugStatut = ref(null);
const edition = reactive({ salleId: null, nom: "", ville: "", dept: "" });
const salleASupprimer = ref(null);

const dialogAjoutRef = ref(null);
const dialogEditionRef = ref(null);
const dialogSupprRef = ref(null);

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

function slugCourant(salle) {
  const edite = slugsEdites[salle.salleId];
  return edite !== undefined ? edite : (salle.digitickSlug ?? "");
}

async function charger() {
  try {
    const rep = await fetch("/api/admin/sources", {
      signal: controleur.signal,
    });
    if (!rep.ok) return;
    const d = await rep.json();
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
    params.set("digitick", vueDigitick.value);
    params.set("page", page.value);
    if (recherche.value.trim()) params.set("nom", recherche.value.trim());

    const rep = await fetch(`/api/admin/sources/salles?${params}`, {
      signal: controleurCourant.signal,
    });
    if (!rep.ok) return;
    const d = await rep.json();
    salles.value = d.salles ?? [];
    page.value = d.page ?? 0;
    totalPages.value = d.totalPages ?? 1;
    total.value = d.total ?? 0;
    for (const s of salles.value) {
      if (slugsEdites[s.salleId] === undefined) {
        slugsEdites[s.salleId] = s.digitickSlug ?? "";
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

function basculerVue(digitick) {
  vueDigitick.value = digitick;
  page.value = 0;
  recherche.value = "";
  chargerSalles();
}

function allerPage(n) {
  page.value = Math.max(0, Math.min(n, totalPages.value - 1));
  chargerSalles();
}

function mettreAJourSlug(salle, valeur) {
  slugsEdites[salle.salleId] = extraireSlugDepuisUrl(valeur);
}

async function sauvegarderSlug(salle) {
  const cle = salle.salleId + ":slug";
  enCours[cle] = true;
  try {
    const rep = await fetch(
      `/api/admin/salles/${salle.salleId}/digitick-slug`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ slug: slugsEdites[salle.salleId] ?? "" }),
        signal: controleur.signal,
      },
    );
    if (rep.ok) {
      salle.digitickSlug = slugsEdites[salle.salleId] || null;
      afficherToast("Slug mis à jour.", "succes");
      await chargerSalles();
    } else {
      afficherToast("Erreur lors de la mise à jour du slug.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours[cle] = false;
  }
}

async function testerSlug(salle) {
  const slug = slugCourant(salle);
  if (!slug) return;
  const cle = salle.salleId + ":test";
  enCours[cle] = true;
  statutTester[salle.salleId] = null;
  try {
    const rep = await fetch(
      `/api/admin/salles/${salle.salleId}/tester-digitick`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ slug }),
        signal: controleur.signal,
      },
    );
    if (rep.ok) {
      const data = await corpsJsonOuVide(rep);
      statutTester[salle.salleId] =
        data.nbConcerts > 0 ? `ok:${data.nbConcerts}` : "ok:0";
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
  if (statut === "ko") return "404 ✗";
  const nb = parseInt(statut.split(":")[1] ?? "0", 10);
  return nb > 0 ? `✓ ${nb} concert${nb > 1 ? "s" : ""}` : "✓ aucun concert";
}

async function fetchSalle(salle) {
  const cle = salle.salleId + ":fetch";
  enCours[cle] = true;
  try {
    const rep = await fetch(`/api/admin/salles/${salle.salleId}/fetch`, {
      method: "POST",
      signal: controleur.signal,
    });
    if (rep.ok) {
      afficherToast("Fetch déclenché.", "succes");
    } else {
      const data = await corpsJsonOuVide(rep);
      afficherToast(data.erreur || "Erreur lors du déclenchement.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours[cle] = false;
  }
}

function ouvrirAjout() {
  ajout.nom = "";
  ajout.slug = "";
  ajout.ville = "";
  ajout.dept = "";
  ajoutSlugStatut.value = null;
  dialogAjoutRef.value?.showModal();
}

async function testerSlugAjout() {
  if (!ajout.slug || enCours.ajoutTest) return;
  enCours.ajoutTest = true;
  ajoutSlugStatut.value = null;
  try {
    const slug = extraireSlugDepuisUrl(ajout.slug);
    const rep = await fetch(
      `/api/admin/salles/00000000-0000-0000-0000-000000000001/tester-digitick`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ slug }),
        signal: controleur.signal,
      },
    );
    if (rep.ok) {
      const data = await corpsJsonOuVide(rep);
      ajoutSlugStatut.value =
        data.nbConcerts > 0 ? `ok:${data.nbConcerts}` : "ok:0";
    } else {
      ajoutSlugStatut.value = "ko";
    }
  } catch (e) {
    if (e.name !== "AbortError") ajoutSlugStatut.value = "ko";
  } finally {
    enCours.ajoutTest = false;
  }
}

async function creerSalleDigitick() {
  if (enCours.ajout) return;
  enCours.ajout = true;
  try {
    const rep = await fetch("/api/admin/digitick-salles", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        nom: ajout.nom,
        slug: ajout.slug,
        ville: ajout.ville || null,
        dept: ajout.dept || null,
      }),
      signal: controleur.signal,
    });
    if (rep.ok) {
      dialogAjoutRef.value?.close();
      afficherToast("Salle Digitick ajoutée — fetch en cours.", "succes");
      await chargerSalles();
    } else {
      const data = await corpsJsonOuVide(rep);
      afficherToast(data.erreur || "Erreur lors de la création.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours.ajout = false;
  }
}

function ouvrirEdition(salle) {
  edition.salleId = salle.salleId;
  edition.nom = salle.nom ?? "";
  edition.ville = salle.ville ?? "";
  edition.dept = salle.dept ?? "";
  dialogEditionRef.value?.showModal();
}

async function sauvegarderEdition() {
  if (enCours.edition) return;
  enCours.edition = true;
  try {
    const rep = await fetch(`/api/admin/digitick-salles/${edition.salleId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        nom: edition.nom || null,
        slug: null,
        ville: edition.ville || null,
        dept: edition.dept || null,
      }),
      signal: controleur.signal,
    });
    if (rep.ok) {
      dialogEditionRef.value?.close();
      afficherToast("Salle mise à jour.", "succes");
      await chargerSalles();
    } else {
      afficherToast("Erreur lors de la mise à jour.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours.edition = false;
  }
}

function demanderSuppression(salle) {
  salleASupprimer.value = salle;
  dialogSupprRef.value?.showModal();
}

async function confirmerSuppression() {
  const salle = salleASupprimer.value;
  if (!salle) return;
  const cle = salle.salleId + ":suppr";
  enCours[cle] = true;
  try {
    const rep = await fetch(`/api/admin/digitick-salles/${salle.salleId}`, {
      method: "DELETE",
      signal: controleur.signal,
    });
    dialogSupprRef.value?.close();
    if (rep.ok) {
      afficherToast("Salle supprimée.", "succes");
      await chargerSalles();
    } else if (rep.status === 409) {
      const data = await corpsJsonOuVide(rep);
      afficherToast(
        data.erreur ||
          "Impossible de supprimer : salle suivie ou avec concerts.",
        "erreur",
      );
    } else {
      afficherToast("Erreur lors de la suppression.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours[cle] = false;
    salleASupprimer.value = null;
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

.source-slug-inline {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex-wrap: wrap;
}

.source-slug-input {
  font-size: 0.82rem;
  padding: 0.2rem 0.45rem;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg);
  color: var(--fg);
  width: 5rem;
}

@media (width >= 641px) {
  .source-slug-inline {
    flex-wrap: nowrap;
  }

  .source-slug-input {
    width: 9rem;
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

.source-digitick-actions {
  margin-bottom: 0.75rem;
}

.source-salle-loc {
  font-size: 0.85rem;
  color: var(--texte-secondaire, #6b7280);
  white-space: nowrap;
}
</style>
