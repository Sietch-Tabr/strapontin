<template>
  <section id="signalements" class="admin-section">
    <div class="param-bloc">
      <h3 class="param-libelle">Anomalies détectées</h3>
      <p class="param-aide">
        Incohérences relevées par les contrôles automatiques. Chacune se traite
        ici ou depuis la fiche de la salle ou de l'artiste concerné.
      </p>
      <p v-if="!chargement && !signalements.length" class="etat-vide-texte">
        Aucune anomalie en attente.
      </p>
      <EtatChargement v-if="chargement" />
      <ul v-else-if="signalements.length" class="liste-signalements">
        <li
          v-for="s in signalements"
          :key="s.categorie + s.id"
          class="signalement cadre cadre-serre"
        >
          <div class="signalement-entete">
            <strong class="signalement-titre">{{
              decrireType(catalogue, s.type).libelle
            }}</strong>
            <span class="signalement-entite">{{
              libelleEntite(s.entite)
            }}</span>
            <span v-if="s.detecteLe" class="signalement-date texte-note">
              {{ formaterInstant(s.detecteLe) }}
            </span>
          </div>
          <p class="signalement-explication texte-note">
            {{ decrireType(catalogue, s.type).explication }}
          </p>
          <dl
            v-if="formaterDetails(s.details).length"
            class="signalement-details texte-note"
          >
            <template v-for="(d, i) in formaterDetails(s.details)" :key="i">
              <dt v-if="d.cle">{{ d.cle }}</dt>
              <dd>
                <a
                  v-if="d.lien"
                  :href="d.lien"
                  target="_blank"
                  rel="noopener noreferrer"
                  >{{ d.valeur }}</a
                >
                <template v-else>{{ d.valeur }}</template>
              </dd>
            </template>
          </dl>
          <div class="source-actions">
            <template v-for="action in actionsSignalement(s)" :key="action">
              <button
                v-if="action === 'repartir'"
                type="button"
                class="bouton bouton-secondaire bouton-petit"
                @click="
                  repartition.ouvrir({ id: s.entite.id, nom: s.entite.nom })
                "
              >
                Répartir les concerts
              </button>
              <button
                v-else-if="action === 'fauxPositif'"
                type="button"
                class="bouton bouton-secondaire bouton-petit"
                :aria-label="
                  'Déclarer faux positif : ' + libelleEntite(s.entite)
                "
                @click="decider(s, 'contamination', 'accepter')"
              >
                Faux positif
              </button>
              <button
                v-else-if="action === 'sallesDistinctes'"
                type="button"
                class="bouton bouton-secondaire bouton-petit"
                :aria-label="'Déclarer distinctes : ' + libelleEntite(s.entite)"
                @click="decider(s, 'doublon', 'distinctes')"
              >
                Salles distinctes
              </button>
              <button
                v-else-if="action === 'ignorerEcart'"
                type="button"
                class="bouton bouton-secondaire bouton-petit"
                title="Le nom publié par Ticketmaster est accepté : il ne sera plus signalé tant qu'il ne change pas"
                @click="ignorerEcart(s)"
              >
                Accepter le nom publié
              </button>
              <button
                v-else-if="action === 'supprimerConcert'"
                type="button"
                class="bouton bouton-danger bouton-petit"
                :aria-label="
                  'Supprimer le concert : ' + libelleEntite(s.entite)
                "
                @click="supprimerConcert(s)"
              >
                Supprimer le concert
              </button>
              <button
                v-else-if="action === 'resoudre'"
                type="button"
                class="bouton bouton-secondaire bouton-petit"
                @click="resoudre(s)"
              >
                Marquer comme traité
              </button>
              <button
                v-else-if="action === 'voirFiche'"
                type="button"
                class="bouton bouton-texte bouton-petit"
                @click="emit('voir-fiche', s.entite)"
              >
                Voir la fiche
              </button>
            </template>
          </div>
        </li>
      </ul>

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
          >Page {{ page + 1 }} / {{ totalPages }} ({{ total }} anomalies)</span
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

    <DialogueRepartitionSalle ref="repartition" @reparti="charger" />
    <DialogueConfirmation ref="dialogueConfirmation" />
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "AdminSignalements" });

import { ref, shallowRef, onMounted, onUnmounted } from "vue";
import { useToasts } from "../../composables/useToasts.js";
import { formaterInstant } from "../../composables/useDates.js";
import {
  actionsSignalement,
  decrireType,
  formaterDetails,
  libelleEntite,
} from "../../utils/signalements.js";
import EtatChargement from "../EtatChargement.vue";
import DialogueRepartitionSalle from "./DialogueRepartitionSalle.vue";
import DialogueConfirmation from "../DialogueConfirmation.vue";

const emit = defineEmits(["voir-fiche", "total"]);

const { afficherToast } = useToasts();

const chargement = ref(true);
const signalements = shallowRef([]);
const catalogue = shallowRef([]);
const page = ref(0);
const totalPages = ref(1);
const total = ref(0);
const repartition = ref(null);
const dialogueConfirmation = ref(null);
const controleur = new AbortController();

async function charger() {
  chargement.value = true;
  try {
    const rep = await appelApi(`/api/admin/signalements?page=${page.value}`, {
      signal: controleur.signal,
    });
    if (rep.ok) {
      const data = await lireJson(rep);
      signalements.value = data.signalements ?? [];
      catalogue.value = data.catalogue ?? [];
      page.value = data.page ?? 0;
      totalPages.value = data.totalPages ?? 1;
      total.value = data.total ?? 0;
      emit("total", total.value);
    } else {
      afficherToast("Erreur lors du chargement des anomalies.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") {
      afficherToast(
        "Erreur réseau lors du chargement des anomalies.",
        "erreur",
      );
    }
  } finally {
    chargement.value = false;
  }
}

function allerPage(n) {
  page.value = Math.max(0, Math.min(n, totalPages.value - 1));
  charger();
}

async function poster(url, messageSucces) {
  try {
    const rep = await appelApi(url, {
      method: "POST",
      signal: controleur.signal,
    });
    if (rep.ok) {
      afficherToast(messageSucces, "succes");
      await charger();
    } else {
      afficherToast(
        "Erreur lors de l'enregistrement de la décision.",
        "erreur",
      );
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  }
}

// Décision mémorisée côté serveur : le signalement ne réapparaîtra plus au
// prochain contrôle nocturne.
function decider(signalement, categorie, action) {
  return poster(
    `/api/admin/signalements/${categorie}/${signalement.entite.id}/${action}`,
    "Décision enregistrée.",
  );
}

function ignorerEcart(signalement) {
  return poster(
    `/api/admin/artistes/${signalement.entite.id}/ignorer-intervention`,
    "Nom publié accepté.",
  );
}

async function supprimerConcert(signalement) {
  const confirme = await dialogueConfirmation.value?.demander({
    titre: "Supprimer ce concert ?",
    message: `${libelleEntite(signalement.entite)} sera supprimé définitivement, et l'anomalie marquée comme traitée.`,
    action: "Supprimer",
  });
  if (!confirme) return;
  return poster(
    `/api/admin/signalements/${signalement.id}/supprimer-concert`,
    "Concert supprimé.",
  );
}

function resoudre(signalement) {
  return poster(
    `/api/admin/signalements/${signalement.id}/resoudre`,
    "Anomalie marquée comme traitée.",
  );
}

onMounted(charger);
onUnmounted(() => controleur.abort());
</script>

<style scoped>
.liste-signalements {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--espace-6);
}

.signalement {
  min-width: 0;
}

.signalement-entete {
  display: flex;
  flex-wrap: wrap;
  gap: var(--espace-2) var(--espace-6);
  align-items: baseline;
}

.signalement-entite {
  overflow-wrap: anywhere;
}

.signalement-explication {
  margin: var(--espace-2) 0;
}

.signalement-details {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--espace-1) var(--espace-6);
  margin: var(--espace-2) 0 var(--espace-4);
  overflow-wrap: anywhere;
}

.signalement-details dd {
  margin: 0;
}
</style>
