<template>
  <section class="parametres-section" id="donnees">
    <h2 class="sr-only">Données</h2>

    <div class="param-bloc">
      <h3 class="param-label">Exporter mes préférences</h3>
      <p class="param-aide">
        Télécharge un fichier JSON avec vos artistes, salles et configuration.
      </p>
      <p v-if="stats" class="param-aide">
        {{ stats.nbArtistes }}/500 artistes — {{ stats.nbSalles }}/200 salles
      </p>
      <a href="/api/preferences" class="btn btn-secondary">Télécharger (JSON)</a>
    </div>

    <div class="param-bloc">
      <h3 class="param-label">Importer des préférences</h3>
      <p class="param-aide">
        Importez un fichier JSON exporté par Strapontin. Mode Fusionner conserve vos
        données existantes ; mode Écraser les remplace.
      </p>
      <form class="form-vertical form-compact" @submit.prevent="importerPreferences">
        <div class="form-group">
          <label for="import-fichier">Fichier JSON</label>
          <input
            id="import-fichier"
            ref="importFichierRef"
            type="file"
            accept=".json,application/json"
            required
          />
          <p class="form-aide">Fichier .json exporté depuis Strapontin</p>
        </div>
        <div class="form-group">
          <label for="import-mode">Mode</label>
          <select id="import-mode" v-model="importMode">
            <option value="fusionner">Fusionner (conserver les données existantes)</option>
            <option value="ecraser">Écraser (remplacer artistes et salles)</option>
          </select>
          <p
            v-if="importMode === 'ecraser'"
            class="form-aide form-aide-avertissement"
          >
            Attention : vos artistes et salles actuels seront remplacés.
          </p>
        </div>
        <div class="form-group form-checkboxes">
          <label>
            <input v-model="importerConfig" type="checkbox" />
            Importer la configuration (départements, notifications, thème)
          </label>
        </div>
        <button type="submit" class="btn btn-secondary">Importer</button>
      </form>
    </div>

    <div class="param-bloc param-bloc-danger">
      <h3 class="param-label">Supprimer mon compte</h3>
      <p class="param-aide">
        Supprime définitivement votre compte et toutes vos données. Cette action est
        irréversible.
      </p>
      <button type="button" class="btn btn-danger" @click="ouvrirDialogSuppression">
        Supprimer mon compte
      </button>
    </div>
  </section>

  <dialog ref="dialogSuppressionRef" id="dialog-supprimer-compte">
    <p>
      Supprime définitivement votre compte et toutes vos données.
      Cette action est irréversible.
    </p>
    <div class="form-group">
      <label for="confirmation-suppression">
        Saisissez <strong>supprimer</strong> pour confirmer
      </label>
      <input
        id="confirmation-suppression"
        v-model="confirmationSuppression"
        type="text"
        autocomplete="off"
        autocorrect="off"
        spellcheck="false"
      />
    </div>
    <div class="dialog-pied">
      <button type="button" class="btn btn-secondary" @click="fermerDialogSuppression">
        Annuler
      </button>
      <button
        type="button"
        class="btn btn-danger"
        :disabled="confirmationSuppression !== 'supprimer'"
        @click="supprimerCompte"
      >
        Supprimer mon compte
      </button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'ParametresDonnees' })

import { ref, shallowRef, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useDialog } from '../../composables/useDialog.js'
import { useToasts } from '../../composables/useToasts.js'

const router = useRouter()
const { afficherToast } = useToasts()
const { dialogRef: dialogSuppressionRef, ouvrir: ouvrirDialogSuppressionModal, fermer: fermerDialogSuppressionModal } = useDialog()

const stats = shallowRef(null)
const importFichierRef = ref(null)
const importMode = ref('fusionner')
const importerConfig = ref(false)
const confirmationSuppression = ref('')

async function charger() {
  try {
    const rep = await fetch('/api/parametres/stats')
    if (rep.ok) stats.value = await rep.json()
  } catch (_) {
    afficherToast('Erreur lors du chargement des statistiques.', 'erreur')
  }
}

async function importerPreferences() {
  const fichierEl = importFichierRef.value
  if (!fichierEl?.files?.length) return

  const formData = new FormData()
  formData.append('fichier', fichierEl.files[0])
  formData.append('mode', importMode.value)
  formData.append('importerConfig', importerConfig.value ? 'true' : 'false')

  const rep = await fetch('/api/preferences/importer', {
    method: 'POST',
    body: formData,
  })
  const corps = await rep.json()
  if (rep.ok) {
    const a = corps.artistes ?? 0
    const s = corps.salles ?? 0
    const r = corps.rejetes ?? 0
    const msg =
      a + ' artiste' + (a > 1 ? 's' : '') +
      ' et ' + s + ' salle' + (s > 1 ? 's' : '') +
      ' importés' +
      (r > 0 ? ' (' + r + ' rejeté' + (r > 1 ? 's' : '') + ')' : '') + '.'
    afficherToast(msg, 'succes')
    const repStats = await fetch('/api/parametres/stats')
    if (repStats.ok) stats.value = await repStats.json()
    fichierEl.value = ''
  } else {
    afficherToast(corps.erreur || "Erreur lors de l'import.", 'erreur')
  }
}

function ouvrirDialogSuppression() {
  confirmationSuppression.value = ''
  ouvrirDialogSuppressionModal()
}

function fermerDialogSuppression() {
  fermerDialogSuppressionModal()
}

async function supprimerCompte() {
  if (confirmationSuppression.value !== 'supprimer') return
  const rep = await fetch('/api/user', { method: 'DELETE' })
  if (rep.ok || rep.status === 204) {
    router.push('/login')
  } else {
    afficherToast('Erreur lors de la suppression du compte.', 'erreur')
  }
}

onMounted(charger)
</script>
