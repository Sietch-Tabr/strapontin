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
              'cadre',
              'cadre-serre',
              { 'source-carte-large': source.id === 'ticketmaster' },
            ]"
          >
            <p class="source-nom">{{ source.nom }}</p>
            <span class="badge-source" :class="'badge-source-' + source.type">
              {{ source.type === "concerts" ? "Concerts" : "Métadonnées" }}
            </span>

            <template v-if="source.id === 'ticketmaster'">
              <div
                v-if="source.quotaTM"
                class="quota-tm-carte cadre cadre-large"
              >
                <div class="quota-tm-chiffres">
                  <span>
                    <strong>{{ source.quotaTM.dispo }}</strong>
                    <span class="quota-tm-sep texte-secondaire">{{
                      " / " + source.quotaTM.total
                    }}</span>
                    <span class="quota-tm-unite texte-discret"
                      >appels restants</span
                    >
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
              <p class="source-carte-detail param-aide texte-note">
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
              <p class="source-carte-detail texte-note">
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
              <p class="source-carte-detail texte-note">
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
              <p class="source-carte-detail texte-note">
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
              class="source-carte-detail param-aide texte-note"
            >
              Dernier fetch : {{ formaterInstant(source.dernierFetch) }}
            </p>
            <p
              v-else-if="source.id !== 'deezer' && source.id !== 'wikidata'"
              class="source-carte-detail param-aide texte-note"
            >
              Jamais exécuté
            </p>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "AdminSources" });

import { ref, shallowRef, onMounted, onUnmounted } from "vue";
import EtatChargement from "../EtatChargement.vue";
import { useToasts } from "../../composables/useToasts.js";
import { formaterInstant, formaterTs } from "../../composables/useDates.js";

const { afficherToast } = useToasts();

const chargement = ref(true);
const sources = shallowRef([]);

const controleur = new AbortController();
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
}

onMounted(charger);
onUnmounted(() => controleur.abort());
</script>

<style scoped>
.quota-tm-chiffres {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: var(--espace-4);
  font-size: var(--texte-m);
}

.quota-tm-chiffres strong {
  font-size: var(--texte-2xl);
  font-weight: 700;
}

.quota-tm-sep {
  margin-left: var(--espace-2);
}

.quota-tm-unite {
  margin-left: var(--espace-3);
}

.quota-tm-pct {
  font-weight: 600;
  font-size: var(--texte-m);
}

.quota-tm-barre-fond {
  height: 0.5rem;
  background: var(--bg-muted);
  border-radius: var(--radius-pill);
  overflow: hidden;
  margin-bottom: var(--espace-4);
}

.quota-tm-barre-remplissage {
  height: 100%;
  border-radius: var(--radius-pill);
  transition: width 0.3s;
  width: 0%;
}

.sources-grille {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--espace-8);
  margin-top: var(--espace-4);
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
  display: flex;
  flex-direction: column;
  gap: var(--espace-4);
}

.source-nom {
  font-weight: 600;
  font-size: var(--texte-m);
  margin: 0 0 var(--espace-2);
}

.source-carte-detail {
  margin: 0;
}
</style>
