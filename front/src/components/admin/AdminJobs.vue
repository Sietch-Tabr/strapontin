<template>
  <section id="jobs" class="admin-section">
    <EtatChargement v-if="chargement" />
    <p v-else-if="!groupes.length" class="etat-vide-texte">
      Aucun job exécuté pour l'instant.
    </p>
    <div v-for="groupe in groupes" v-else :key="groupe.code" class="param-bloc">
      <h3 class="param-libelle">{{ groupe.libelle }}</h3>
      <p
        v-if="groupe.code === THEME_A_CLASSER"
        class="alerte alerte-avertissement"
        role="alert"
      >
        Ces jobs n'ont pas encore de nom ni de thème dans le catalogue des jobs.
      </p>
      <div class="jobs-grille">
        <article
          v-for="stat in groupe.jobs"
          :key="stat.typeJob"
          class="job-carte"
        >
          <div class="job-carte-entete">
            <span
              class="job-pastille"
              :class="classePastille(stat)"
              aria-hidden="true"
            ></span>
            <h4 class="job-carte-libelle">{{ stat.libelle }}</h4>
            <span
              v-if="jobsEnCours.includes(stat.typeJob)"
              class="job-en-cours"
              aria-busy="true"
              aria-label="En cours"
            >
              <svg
                class="roue-chargement"
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.5"
                aria-hidden="true"
              >
                <path d="M21 12a9 9 0 1 1-6.219-8.56" />
              </svg>
            </span>
          </div>
          <p v-if="stat.description" class="job-carte-description">
            {{ stat.description }}
          </p>
          <p class="job-carte-detail">
            <span v-if="stat.derniereExec">{{
              formaterInstant(stat.derniereExec)
            }}</span>
            <span v-if="stat.derniereExec && stat.dureeMs != null">{{
              " · " + formaterDuree(stat.dureeMs)
            }}</span>
            <span v-if="stat.nbInseres != null && stat.nbInseres > 0">{{
              " · +" + stat.nbInseres + " insérés"
            }}</span>
            <span v-if="stat.nbMisAJour != null && stat.nbMisAJour > 0">{{
              " · " + stat.nbMisAJour + " màj"
            }}</span>
            <span v-if="!stat.derniereExec">Pas encore exécuté</span>
          </p>
          <p v-if="stat.erreur" class="job-carte-detail">
            <span class="badge badge-erreur">{{
              stat.erreur.length > 80
                ? stat.erreur.substring(0, 80) + "…"
                : stat.erreur
            }}</span>
          </p>
          <p v-if="stat.prochaineExec" class="job-carte-detail">
            Prochaine exécution : {{ formaterInstant(stat.prochaineExec) }}
          </p>
          <button
            v-if="groupe.code !== 'RECUPERATION'"
            type="button"
            class="bouton bouton-secondaire bouton-petit job-carte-action"
            :disabled="
              jobsEnCours.includes(stat.typeJob) ||
              relanceJobEnCours[stat.typeJob]
            "
            @click="relancerJob(stat.typeJob)"
          >
            Relancer
          </button>
        </article>
      </div>
      <div v-if="groupe.code === 'RECUPERATION'" class="relancer-actions">
        <button
          id="btn-relancer"
          type="button"
          class="bouton bouton-secondaire"
          :disabled="relancerEnCours"
          @click="relancerFetches"
        >
          Relancer les fetches maintenant
        </button>
      </div>
    </div>

    <div class="param-bloc">
      <h3 class="param-libelle">Worker</h3>
      <div class="worker-carte" :class="{ 'worker-alerte': workerEnAlerte }">
        <div v-if="workerDepuisTexte">
          <p class="worker-carte-libelle">Dernier job complété</p>
          <p class="worker-carte-detail">{{ workerDepuisTexte }}</p>
          <p v-if="workerEnAlerte" class="worker-alerte-texte" role="alert">
            Inactif depuis plus de 3 h
          </p>
        </div>
        <div v-else>
          <p class="worker-carte-detail">
            Aucun job complété — worker non démarré.
          </p>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
import { grouperJobsParTheme, THEME_A_CLASSER } from "../../utils/jobsAdmin.js";
defineOptions({ name: "AdminJobs" });

import {
  ref,
  shallowRef,
  reactive,
  computed,
  onMounted,
  onUnmounted,
} from "vue";
import { useToasts } from "../../composables/useToasts.js";
import { formaterInstant } from "../../composables/useDates.js";
import EtatChargement from "../EtatChargement.vue";

const { afficherToast } = useToasts();

const chargement = ref(true);
const statsJobs = shallowRef([]);
const jobsEnCours = shallowRef([]);
const workerLastSuccess = ref(null);
const relancerEnCours = ref(false);
const relanceJobEnCours = reactive({});
let pollingInterval = null;
// annule les fetch en vol à la destruction du composant (pas de maj de refs mortes).
const controleur = new AbortController();

const catalogue = shallowRef([]);
const themes = shallowRef([]);
const groupes = computed(() =>
  grouperJobsParTheme(statsJobs.value, catalogue.value, themes.value),
);

const workerEnAlerte = computed(() => {
  if (!workerLastSuccess.value) return false;
  return (
    (Date.now() - new Date(workerLastSuccess.value).getTime()) / 60000 > 180
  );
});

const workerDepuisTexte = computed(() => {
  if (!workerLastSuccess.value) return null;
  const min = Math.floor(
    (Date.now() - new Date(workerLastSuccess.value).getTime()) / 60000,
  );
  if (min < 1) return "à l'instant";
  if (min < 60) return "il y a " + min + " min";
  const h = Math.floor(min / 60);
  const m = min % 60;
  return m > 0 ? "il y a " + h + " h " + m + " min" : "il y a " + h + " h";
});

function classePastille(stat) {
  if (jobsEnCours.value.includes(stat.typeJob)) return "pastille-en-cours";
  if (stat.erreur) return "pastille-erreur";
  if (stat.derniereExec) return "pastille-ok";
  return "pastille-vide";
}

function formaterDuree(ms) {
  if (ms == null) return "";
  return ms < 1000 ? ms + " ms" : Math.floor(ms / 1000) + " s";
}

async function charger() {
  try {
    const rep = await appelApi("/api/admin/jobs", {
      signal: controleur.signal,
    });
    if (!rep.ok) return;
    const d = await lireJson(rep);
    statsJobs.value = d.statsJobs ?? [];
    catalogue.value = d.catalogue ?? [];
    themes.value = d.themes ?? [];
    jobsEnCours.value = d.jobsEnCours ?? [];
    workerLastSuccess.value = d.workerLastSuccess ?? null;
    if (jobsEnCours.value.length > 0) demarrerPolling();
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Erreur lors du chargement des jobs.", "erreur");
  } finally {
    chargement.value = false;
  }
}

async function relancerFetches() {
  relancerEnCours.value = true;
  try {
    const rep = await appelApi("/api/admin/relancer", {
      method: "POST",
      signal: controleur.signal,
    });
    if (rep.ok) {
      afficherToast("Fetches relancés.", "succes");
      demarrerPolling();
    } else {
      relancerEnCours.value = false;
      afficherToast("Erreur — worker inaccessible.", "erreur");
    }
  } catch (e) {
    if (e.name === "AbortError") return;
    relancerEnCours.value = false;
    afficherToast("Erreur réseau.", "erreur");
  }
}

async function relancerJob(typeJob) {
  relanceJobEnCours[typeJob] = true;
  try {
    const rep = await appelApi(
      `/api/admin/jobs/relancer?typeJob=${encodeURIComponent(typeJob)}`,
      { method: "POST", signal: controleur.signal },
    );
    if (rep.ok) {
      afficherToast("Job relancé.", "succes");
      demarrerPolling();
    } else {
      afficherToast("Erreur — worker inaccessible.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  } finally {
    relanceJobEnCours[typeJob] = false;
  }
}

function demarrerPolling() {
  arreterPolling();
  pollingInterval = setInterval(async () => {
    try {
      const rep = await appelApi("/api/admin/jobs", {
        signal: controleur.signal,
      });
      if (!rep.ok) return;
      const d = await lireJson(rep);
      statsJobs.value = d.statsJobs ?? statsJobs.value;
      jobsEnCours.value = d.jobsEnCours ?? [];
      if (jobsEnCours.value.length === 0) {
        arreterPolling();
        relancerEnCours.value = false;
      }
    } catch (e) {
      if (e.name === "AbortError") return;
      arreterPolling();
    }
  }, 3000);
}

function arreterPolling() {
  if (pollingInterval) {
    clearInterval(pollingInterval);
    pollingInterval = null;
  }
}

onMounted(charger);
onUnmounted(() => {
  arreterPolling();
  controleur.abort();
});
</script>

<style scoped>
.jobs-grille {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0.75rem;
  margin-top: 0.5rem;
}

@media (width >= 641px) {
  .jobs-grille {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (width >= 1024px) {
  .jobs-grille {
    grid-template-columns: repeat(3, 1fr);
  }
}

.job-carte {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 0.75rem 1rem;
}

.job-carte-description {
  font-size: 0.75rem;
  margin-top: 0.25rem;
}

.job-carte-action {
  align-self: flex-start;
  margin-top: auto;
  padding-top: 0.5rem;
}

.job-carte-entete {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.job-carte-libelle {
  font-size: 0.875rem;
  font-weight: 500;
  margin: 0;
}

.job-carte-detail {
  font-size: 0.75rem;
  color: var(--fg-muted);
  margin-top: 0.25rem;
}

.relancer-actions {
  margin-top: 0.75rem;
}

.job-pastille {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 50%;
  flex-shrink: 0;
}

.job-en-cours {
  display: inline-flex;
  align-items: center;
  margin-left: auto;
  color: var(--info-text);
}

.worker-carte {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 1rem;
}

.worker-carte.worker-alerte {
  border-color: var(--warning);
  background: var(--avertissement-bg);
}

.worker-carte-libelle {
  font-size: 0.875rem;
  font-weight: 500;
}

.worker-carte-detail {
  font-size: 0.875rem;
  color: var(--fg-muted);
  margin-top: 0.25rem;
}

.worker-alerte-texte {
  font-size: 0.75rem;
  color: var(--avertissement-text);
  margin-top: 0.375rem;
}
</style>
