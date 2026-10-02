<template>
  <section id="signalements" class="admin-section">
    <div class="param-bloc">
      <h3 class="param-libelle">Signalements de matching</h3>
      <p v-if="!chargement && !signalements.length" class="etat-vide-texte">
        Aucun signalement actif pour l'instant.
      </p>
      <EtatChargement v-if="chargement" />
      <div v-else-if="signalements.length" class="table-conteneur">
        <table id="table-signalements">
          <thead>
            <tr>
              <th>Type</th>
              <th>Entité</th>
              <th>Confiance</th>
              <th>Détecté le</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in signalements" :key="s.categorie + s.id">
              <td data-label="Type">{{ s.type }}</td>
              <td data-label="Entité">
                {{ s.entiteType }} — {{ s.entiteId }}
                <span v-if="s.details" class="signalement-details">{{
                  s.details
                }}</span>
              </td>
              <td data-label="Confiance">{{ s.confiance }}</td>
              <td data-label="Détecté le">
                {{ s.detecteLe ? formaterInstant(s.detecteLe) : "—" }}
              </td>
              <td data-label="Actions" class="source-actions">
                <button
                  v-if="s.categorie === 'nouveau'"
                  type="button"
                  class="bouton bouton-secondaire bouton-petit"
                  @click="resoudre(s)"
                >
                  Résoudre
                </button>
                <button
                  v-else-if="s.categorie === 'contamination_salle'"
                  type="button"
                  class="bouton bouton-secondaire bouton-petit"
                  :aria-label="
                    'Déclarer faux positif la contamination de ' + s.entiteId
                  "
                  @click="decider(s, 'contamination', 'accepter')"
                >
                  Faux positif
                </button>
                <button
                  v-else-if="s.categorie === 'doublon_salle'"
                  type="button"
                  class="bouton bouton-secondaire bouton-petit"
                  :aria-label="
                    'Déclarer distinctes les salles de ' + s.entiteId
                  "
                  @click="decider(s, 'doublon', 'distinctes')"
                >
                  Salles distinctes
                </button>
                <span v-else class="etat-vide-texte">
                  Depuis l'onglet Artistes
                </span>
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
          signalements)</span
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
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "AdminSignalements" });

import { ref, shallowRef, onMounted, onUnmounted } from "vue";
import { useToasts } from "../../composables/useToasts.js";
import { formaterInstant } from "../../composables/useDates.js";
import EtatChargement from "../EtatChargement.vue";

const { afficherToast } = useToasts();

const chargement = ref(true);
const signalements = shallowRef([]);
const page = ref(0);
const totalPages = ref(1);
const total = ref(0);
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
      page.value = data.page ?? 0;
      totalPages.value = data.totalPages ?? 1;
      total.value = data.total ?? 0;
    } else {
      afficherToast("Erreur lors du chargement des signalements.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") {
      afficherToast(
        "Erreur réseau lors du chargement des signalements.",
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

// Décision mémorisée côté serveur : le signalement ne réapparaîtra plus au
// prochain contrôle nocturne.
async function decider(signalement, categorie, action) {
  try {
    const rep = await appelApi(
      `/api/admin/signalements/${categorie}/${signalement.entiteId}/${action}`,
      { method: "POST", signal: controleur.signal },
    );
    if (rep.ok) {
      afficherToast("Décision enregistrée.", "succes");
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

async function resoudre(signalement) {
  try {
    const rep = await appelApi(
      `/api/admin/signalements/${signalement.id}/resoudre`,
      {
        method: "POST",
        signal: controleur.signal,
      },
    );
    if (rep.ok) {
      afficherToast("Signalement résolu.", "succes");
      await charger();
    } else {
      afficherToast("Erreur lors de la résolution.", "erreur");
    }
  } catch (e) {
    if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
  }
}

onMounted(charger);
onUnmounted(() => controleur.abort());
</script>

<style scoped>
.signalement-details {
  display: block;
  font-size: 0.78rem;
  color: var(--fg-muted);
  overflow-wrap: anywhere;
}
</style>
