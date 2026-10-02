<template>
  <section id="logs" class="admin-section">
    <div class="param-bloc">
      <h3 class="param-libelle">Logs applicatifs</h3>
      <div class="logs-filtres">
        <div class="logs-filtres-source">
          <label for="filtre-source" class="logs-filtre-libelle"
            >Source :</label
          >
          <select
            id="filtre-source"
            v-model="filtreSource"
            class="logs-filtre-select"
          >
            <option value="">Toutes</option>
            <option value="auth">auth</option>
            <option value="admin">admin</option>
            <option value="push">push</option>
            <option value="web">web</option>
            <option value="worker">worker</option>
          </select>
        </div>
        <div
          class="logs-filtres-niveaux"
          role="group"
          aria-label="Filtrer par niveau de log"
        >
          <span class="logs-filtre-libelle">Niveau :</span>
          <button
            v-for="niv in ['info', 'warn', 'error']"
            :key="niv"
            type="button"
            class="log-filtre-btn"
            :data-niveau="niv"
            :class="{ actif: filtresNiveaux.has(niv) }"
            :aria-pressed="filtresNiveaux.has(niv) ? 'true' : 'false'"
            @click="toggleNiveau(niv)"
          >
            {{ niv }}
          </button>
        </div>
        <span class="log-compte" aria-live="polite"
          >{{ logsVisibles }} / {{ logs.length }}</span
        >
        <button
          type="button"
          class="bouton bouton-secondaire logs-btn-action"
          @click="reinitialiserFiltres"
        >
          Réinitialiser
        </button>
        <button
          type="button"
          class="bouton bouton-secondaire logs-btn-action"
          @click="charger"
        >
          Rafraîchir
        </button>
      </div>
      <p v-if="!filtresNiveaux.has('info')" class="logs-note-info">
        Les logs de niveau info sont masqués par défaut. Activez-les via le
        filtre ci-dessus.
      </p>
      <EtatChargement v-if="chargement" />
      <div v-else-if="logs.length" class="logs-conteneur">
        <div class="table-conteneur">
          <table id="table-logs">
            <thead>
              <tr>
                <th>Horodatage</th>
                <th>Niveau</th>
                <th>Source</th>
                <th>Message</th>
              </tr>
            </thead>
            <tbody>
              <template
                v-for="entree in logsAffiches"
                :key="entree.ts + entree.source + entree.message"
              >
                <tr
                  v-show="logVisible(entree)"
                  :class="
                    entree.niveau === 'error'
                      ? 'log-erreur'
                      : entree.niveau === 'warn'
                        ? 'log-avertissement'
                        : ''
                  "
                >
                  <td class="log-ts" data-label="Horodatage">
                    {{ formaterTs(entree.ts) }}
                  </td>
                  <td data-label="Niveau">{{ entree.niveau }}</td>
                  <td data-label="Source">{{ entree.source }}</td>
                  <td class="log-message" data-label="Message">
                    {{ entree.message }}
                    <div v-if="entree.metaParsee" class="log-meta">
                      <span
                        v-for="(val, cle) in entree.metaParsee"
                        :key="cle"
                        class="log-meta-item"
                        >{{ cle }}: {{ val }}</span
                      >
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </div>
      <p v-else class="etat-vide-texte">Aucun log pour l'instant.</p>
    </div>
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "AdminLogs" });

import { ref, shallowRef, computed, onMounted } from "vue";
import { useToasts } from "../../composables/useToasts.js";
import { formaterTs } from "../../composables/useDates.js";
import EtatChargement from "../EtatChargement.vue";

const { afficherToast } = useToasts();

const chargement = ref(true);
const logs = shallowRef([]);
const filtreSource = ref("");
const filtresNiveaux = ref(new Set(["warn", "error"]));

// Pré-calcule metaParsee une seule fois par entrée (au lieu de deux parseMeta par rendu).
const logsAffiches = computed(() =>
  logs.value.map((entree) => ({
    ...entree,
    metaParsee: parseMeta(entree.meta),
  })),
);

const logsVisibles = computed(() => logs.value.filter(logVisible).length);

function logVisible(entree) {
  const okSource = !filtreSource.value || entree.source === filtreSource.value;
  const okNiveau =
    filtresNiveaux.value.size === 0 || filtresNiveaux.value.has(entree.niveau);
  return okSource && okNiveau;
}

function toggleNiveau(niv) {
  const s = new Set(filtresNiveaux.value);
  if (s.has(niv)) s.delete(niv);
  else s.add(niv);
  filtresNiveaux.value = s;
}

function reinitialiserFiltres() {
  filtreSource.value = "";
  filtresNiveaux.value = new Set(["warn", "error"]);
}

function parseMeta(meta) {
  if (!meta) return null;
  try {
    const obj = JSON.parse(meta);
    if (
      typeof obj !== "object" ||
      Array.isArray(obj) ||
      !Object.keys(obj).length
    )
      return null;
    return obj;
  } catch {
    return null;
  }
}

async function charger() {
  try {
    const rep = await appelApi("/api/admin/logs");
    if (rep.ok) logs.value = await lireJson(rep);
  } catch (_) {
    afficherToast("Erreur lors du chargement des logs.", "erreur");
  } finally {
    chargement.value = false;
  }
}

onMounted(charger);
</script>

<style scoped>
.logs-conteneur {
  container-type: inline-size;
  container-name: logs;
}

@container logs (max-width: 560px) {
  .logs-conteneur table,
  .logs-conteneur tbody {
    display: block;
  }

  .logs-conteneur thead {
    display: none;
  }

  .logs-conteneur tr {
    display: block;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--bg-card);
    margin-bottom: 0.375rem;
    overflow: hidden;
  }

  .logs-conteneur tr.log-erreur {
    border-left: 3px solid var(--danger);
  }

  .logs-conteneur tr.log-avertissement {
    border-left: 3px solid var(--warning);
  }

  .logs-conteneur td {
    display: block;
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
  }

  .logs-conteneur td[data-label="Niveau"] {
    display: none;
  }

  .logs-conteneur td[data-label="Source"] {
    font-size: 0.75rem;
    color: var(--fg-muted);
    padding-top: 0;
  }
}

.log-erreur td {
  background: var(--danger-bg);
}

.log-avertissement td {
  background: var(--avertissement-bg);
}

.logs-filtres {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}

.logs-filtres-source {
  display: flex;
  align-items: center;
  gap: 0.375rem;
}

.logs-filtres-niveaux {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

.logs-btn-action {
  font-size: 0.75rem;
  padding: 0.125rem 0.625rem;
}

.logs-note-info {
  font-size: 0.8125rem;
  color: var(--fg-muted);
  margin-bottom: 0.5rem;
  font-style: italic;
}

.logs-filtre-libelle {
  font-size: 0.8125rem;
  color: var(--fg-muted);
}

.logs-filtre-select {
  width: auto;
  font-size: 0.8125rem;
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--border);
  border-radius: 0.25rem;
  background: var(--bg);
  color: var(--fg);
}

.log-filtre-btn {
  border: none;
  background: none;
  border-radius: 0.25rem;
  padding: 0.125rem 0.5rem;
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--fg-muted);
  cursor: pointer;
  transition:
    background 0.15s,
    color 0.15s;
}

.log-filtre-btn:hover {
  background: var(--accent);
  color: var(--fg);
}

.log-filtre-btn[data-niveau="info"].actif {
  background: var(--info-bg);
  color: var(--info-text);
}

.log-filtre-btn[data-niveau="warn"].actif {
  background: var(--avertissement-bg);
  color: var(--avertissement-text);
}

.log-filtre-btn[data-niveau="error"].actif {
  background: var(--danger-bg);
  color: var(--danger-text);
}

.log-compte {
  margin-left: auto;
  font-size: 0.75rem;
  color: var(--fg-muted);
  font-variant-numeric: tabular-nums;
}

.log-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  margin-top: 0.25rem;
}

.log-meta-item {
  font-size: 0.6875rem;
  color: var(--fg-muted);
  background: var(--bg-muted);
  padding: 0.0625rem 0.375rem;
  border-radius: 0.1875rem;
  font-family: monospace;
}
</style>
