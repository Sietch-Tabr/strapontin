<template>
  <dialog
    ref="dialogRef"
    aria-labelledby="titre-repartition-salle"
    aria-modal="true"
    class="dialogue-repartition"
  >
    <div class="dialog-entete">
      <h2 id="titre-repartition-salle" class="dialog-titre">
        Répartir les concerts de « {{ salle?.nom }} »
      </h2>
    </div>
    <form @submit.prevent="confirmer">
      <p class="param-aide">
        Cochez les noms de lieu, tels que publiés par les billetteries, dont les
        concerts appartiennent à une autre salle.
      </p>
      <EtatChargement v-if="chargement" />
      <fieldset v-else class="repartition-libelles">
        <legend class="champ-label">Noms publiés par les billetteries</legend>
        <label
          v-for="l in libelles"
          :key="l.libelle"
          class="repartition-libelle"
        >
          <input v-model="choisis" type="checkbox" :value="l.libelle" />
          {{ l.libelle }}
          <span class="param-aide"
            >({{ l.nbEvenements }} concert{{
              l.nbEvenements > 1 ? "s" : ""
            }})</span
          >
        </label>
        <p v-if="!libelles.length" class="etat-vide-texte">
          Aucun concert rattaché à cette salle.
        </p>
      </fieldset>

      <fieldset v-if="!chargement" class="repartition-libelles">
        <legend class="champ-label">Lieux des billetteries à déplacer</legend>
        <p class="param-aide">
          Un lieu laissé sur cette salle y ramène ses concerts à chaque
          récupération : cochez tous ceux qui désignent l'autre salle.
        </p>
        <label
          v-for="l in lieux"
          :key="l.identifiant"
          class="repartition-libelle"
        >
          <input v-model="lieuxChoisis" type="checkbox" :value="l.identifiant" />
          {{ l.nom || "Lieu sans nom connu" }}
          <span class="param-aide"
            >({{ libelleSource(l.source) }}{{ l.ville ? ", " + l.ville : "" }})</span
          >
        </label>
        <p v-if="!lieux.length" class="etat-vide-texte">
          Aucun lieu de billetterie rattaché à cette salle.
        </p>
        <p
          v-if="choisis.length && lieuxRestants.length"
          class="alerte alerte-avertissement"
        >
          Resteront sur « {{ salle?.nom }} » :
          {{ lieuxRestants.map((l) => l.nom || l.identifiant).join(", ") }}.
        </p>
      </fieldset>

      <fieldset class="repartition-destination">
        <legend class="champ-label">Destination</legend>
        <label class="repartition-libelle">
          <input v-model="mode" type="radio" value="nouvelle" />
          Nouvelle salle
        </label>
        <label class="repartition-libelle">
          <input v-model="mode" type="radio" value="existante" />
          Salle existante
        </label>

        <div v-if="mode === 'nouvelle'" class="champ">
          <label for="repartition-nouveau-nom" class="champ-label"
            >Nom de la nouvelle salle</label
          >
          <input
            id="repartition-nouveau-nom"
            v-model="nouveauNom"
            type="text"
            maxlength="200"
            autocomplete="off"
            required
          />
        </div>
        <div v-else class="champ">
          <label for="repartition-recherche" class="champ-label"
            >Rechercher la salle</label
          >
          <input
            id="repartition-recherche"
            v-model="recherche"
            type="search"
            autocomplete="off"
            @input="rechercherSalles"
          />
          <label
            v-for="s in candidates"
            :key="s.id"
            class="repartition-libelle"
          >
            <input v-model="cibleId" type="radio" :value="s.id" />
            {{ s.nomAffiche || s.nom }}
            <span class="param-aide"
              >{{ s.ville || "" }} {{ s.dept ? "(" + s.dept + ")" : "" }}</span
            >
          </label>
        </div>
      </fieldset>

      <div class="dialog-pied">
        <button
          type="submit"
          class="bouton bouton-secondaire"
          :disabled="!peutConfirmer || enCours"
        >
          Répartir
        </button>
        <button type="button" class="bouton bouton-texte" @click="fermer">
          Annuler
        </button>
      </div>
    </form>
  </dialog>
</template>

<script setup>
defineOptions({ name: "DialogueRepartitionSalle" });

import { computed, ref, shallowRef, watch } from "vue";
import { lieuxCorrespondants } from "../../utils/repartition.js";
import { appelApi, lireJson } from "../../utils/api.js";
import { useDialog } from "../../composables/useDialog.js";
import { useToasts } from "../../composables/useToasts.js";
import EtatChargement from "../EtatChargement.vue";

const emit = defineEmits(["reparti"]);

const { afficherToast } = useToasts();
const { dialogRef, ouvrir: ouvrirDialog, fermer } = useDialog();

const salle = ref(null);
const libelles = shallowRef([]);
const choisis = ref([]);
const lieux = shallowRef([]);
const lieuxChoisis = ref([]);
const lieuxRestants = computed(() =>
  lieux.value.filter((l) => !lieuxChoisis.value.includes(l.identifiant)),
);
const LIBELLES_SOURCES = { ticketmaster: "Ticketmaster", eventim: "Eventim" };
function libelleSource(source) {
  return LIBELLES_SOURCES[source] ?? source;
}

// Présélection à chaque changement de libellés ; l'admin peut ensuite ajuster.
watch(choisis, (libellesChoisis) => {
  lieuxChoisis.value = lieuxCorrespondants(lieux.value, libellesChoisis);
});
const chargement = ref(false);
const mode = ref("nouvelle");
const nouveauNom = ref("");
const recherche = ref("");
const candidates = shallowRef([]);
const cibleId = ref(null);
const enCours = ref(false);

const peutConfirmer = computed(
  () =>
    choisis.value.length > 0 &&
    (mode.value === "nouvelle"
      ? nouveauNom.value.trim().length > 0
      : cibleId.value !== null),
);

/// @param cible salle `{id, nom}` à répartir
async function ouvrir(cible) {
  salle.value = cible;
  choisis.value = [];
  lieuxChoisis.value = [];
  lieux.value = [];
  mode.value = "nouvelle";
  nouveauNom.value = "";
  recherche.value = "";
  candidates.value = [];
  cibleId.value = null;
  ouvrirDialog();
  chargement.value = true;
  try {
    const [rep, repLieux] = await Promise.all([
      appelApi(`/api/admin/salles/${cible.id}/libelles-sources`),
      appelApi(`/api/admin/salles/${cible.id}/lieux-sources`),
    ]);
    libelles.value = rep.ok ? await lireJson(rep) : [];
    lieux.value = repLieux.ok ? await lireJson(repLieux) : [];
    if (!rep.ok || !repLieux.ok) {
      afficherToast("Erreur lors du chargement des lieux.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  } finally {
    chargement.value = false;
  }
}

let rechercheEnCours = null;

async function rechercherSalles() {
  rechercheEnCours?.abort();
  const terme = recherche.value.trim();
  if (terme.length < 2) {
    candidates.value = [];
    return;
  }
  const controleur = new AbortController();
  rechercheEnCours = controleur;
  try {
    const rep = await appelApi(
      `/api/admin/salles?page=0&nom=${encodeURIComponent(terme)}`,
      { signal: controleur.signal },
    );
    if (rep.ok) {
      const data = await lireJson(rep);
      candidates.value = (data.salles ?? []).filter(
        (s) => s.id !== salle.value?.id,
      );
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  }
}

async function confirmer() {
  if (!peutConfirmer.value || enCours.value) return;
  enCours.value = true;
  try {
    const rep = await appelApi(`/api/admin/salles/${salle.value.id}/repartir`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        libelles: choisis.value,
        lieux: lieuxChoisis.value,
        cibleId: mode.value === "existante" ? cibleId.value : null,
        nouvelleSalleNom:
          mode.value === "nouvelle" ? nouveauNom.value.trim() : null,
      }),
    });
    if (rep.ok) {
      const bilan = await lireJson(rep);
      const deplaces = bilan.evenementsDeplaces + bilan.evenementsFusionnes;
      afficherToast(
        `${deplaces} concert${deplaces > 1 ? "s" : ""} déplacé${deplaces > 1 ? "s" : ""}` +
          (bilan.evenementsMixtes
            ? `, ${bilan.evenementsMixtes} laissé${bilan.evenementsMixtes > 1 ? "s" : ""} en place (publiés aussi sous un autre nom).`
            : "."),
        "succes",
      );
      fermer();
      emit("reparti", bilan);
    } else {
      const erreur = await lireJson(rep);
      afficherToast(
        erreur?.erreur ?? "Erreur lors de la répartition.",
        "erreur",
      );
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCours.value = false;
  }
}

defineExpose({ ouvrir });
</script>

<style scoped>
.repartition-libelles,
.repartition-destination {
  border: none;
  margin: 0 0 1rem;
  padding: 0;
}

.repartition-libelle {
  display: flex;
  gap: 0.5rem;
  align-items: baseline;
  padding: 0.2rem 0;
  overflow-wrap: anywhere;
}
</style>
