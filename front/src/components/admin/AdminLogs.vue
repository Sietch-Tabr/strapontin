<template>
  <section class="admin-section" id="logs">
    <div class="param-bloc">
      <h3 class="param-label">Logs applicatifs</h3>
      <div class="logs-filtres">
        <div class="logs-filtres-source">
          <label for="filtre-source" class="logs-filtre-label">Source :</label>
          <select id="filtre-source" v-model="filtreSource" class="logs-filtre-select">
            <option value="">Toutes</option>
            <option value="auth">auth</option>
            <option value="admin">admin</option>
            <option value="push">push</option>
            <option value="web">web</option>
            <option value="worker">worker</option>
          </select>
        </div>
        <div class="logs-filtres-niveaux" role="group" aria-label="Filtrer par niveau de log">
          <span class="logs-filtre-label">Niveau :</span>
          <button
            v-for="niv in ['info', 'warn', 'error']"
            :key="niv"
            type="button"
            class="log-filtre-btn"
            :data-niveau="niv"
            :class="{ actif: filtresNiveaux.has(niv) }"
            :aria-pressed="filtresNiveaux.has(niv) ? 'true' : 'false'"
            @click="toggleNiveau(niv)"
          >{{ niv }}</button>
        </div>
        <span class="log-compte" aria-live="polite">{{ logsVisibles }} / {{ logs.length }}</span>
        <button type="button" class="btn btn-secondary logs-btn-action" @click="reinitialiserFiltres">Réinitialiser</button>
        <button type="button" class="btn btn-secondary logs-btn-action" @click="charger">Rafraîchir</button>
      </div>
      <p v-if="!filtresNiveaux.has('info')" class="logs-note-info">
        Les logs de niveau info sont masqués par défaut. Activez-les via le filtre ci-dessus.
      </p>
      <EtatChargement v-if="chargement" />
      <div v-else-if="logs.length" class="logs-conteneur">
        <div class="table-wrapper">
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
              <template v-for="entree in logsAffiches" :key="entree.ts + entree.source + entree.message">
                <tr
                  v-show="logVisible(entree)"
                  :class="entree.niveau === 'error' ? 'log-error' : entree.niveau === 'warn' ? 'log-warn' : ''"
                >
                  <td class="log-ts" data-label="Horodatage">{{ formaterTs(entree.ts) }}</td>
                  <td data-label="Niveau">{{ entree.niveau }}</td>
                  <td data-label="Source">{{ entree.source }}</td>
                  <td class="log-message" data-label="Message">
                    {{ entree.message }}
                    <div v-if="entree.metaParsee" class="log-meta">
                      <span
                        v-for="(val, cle) in entree.metaParsee"
                        :key="cle"
                        class="log-meta-item"
                      >{{ cle }}: {{ val }}</span>
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
defineOptions({ name: 'AdminLogs' })

import { ref, shallowRef, computed, onMounted } from 'vue'
import { useToasts } from '../../composables/useToasts.js'
import { formaterTs } from '../../composables/useDates.js'
import EtatChargement from '../EtatChargement.vue'

const { afficherToast } = useToasts()

const chargement = ref(true)
const logs = shallowRef([])
const filtreSource = ref('')
const filtresNiveaux = ref(new Set(['warn', 'error']))

// Pré-calcule metaParsee une seule fois par entrée (au lieu de deux parseMeta par rendu).
const logsAffiches = computed(() =>
  logs.value.map((entree) => ({ ...entree, metaParsee: parseMeta(entree.meta) })),
)

const logsVisibles = computed(() => logs.value.filter(logVisible).length)

function logVisible(entree) {
  const okSource = !filtreSource.value || entree.source === filtreSource.value
  const okNiveau = filtresNiveaux.value.size === 0 || filtresNiveaux.value.has(entree.niveau)
  return okSource && okNiveau
}

function toggleNiveau(niv) {
  const s = new Set(filtresNiveaux.value)
  if (s.has(niv)) s.delete(niv)
  else s.add(niv)
  filtresNiveaux.value = s
}

function reinitialiserFiltres() {
  filtreSource.value = ''
  filtresNiveaux.value = new Set(['warn', 'error'])
}

function parseMeta(meta) {
  if (!meta) return null
  try {
    const obj = JSON.parse(meta)
    if (typeof obj !== 'object' || Array.isArray(obj) || !Object.keys(obj).length) return null
    return obj
  } catch {
    return null
  }
}

async function charger() {
  try {
    const rep = await fetch('/api/admin/logs')
    if (rep.ok) logs.value = await rep.json()
  } catch (_) {
    afficherToast('Erreur lors du chargement des logs.', 'erreur')
  } finally {
    chargement.value = false
  }
}

onMounted(charger)
</script>
