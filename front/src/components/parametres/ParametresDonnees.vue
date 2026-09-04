<template>
  <section class="parametres-section" id="donnees">
    <h2 class="lecteur-ecran">Données</h2>

    <div class="param-bloc">
      <h3 class="param-libelle">Exporter mes préférences</h3>
      <p class="param-aide">
        Télécharge un fichier JSON avec vos artistes, salles et configuration.
      </p>
      <EtatChargement v-if="chargement" />
      <p v-else-if="stats" class="param-aide">
        {{ stats.nbArtistes }}/500 artistes — {{ stats.nbSalles }}/200 salles
      </p>
      <a href="/api/preferences" class="bouton bouton-secondaire">Télécharger (JSON)</a>
    </div>

    <div class="param-bloc">
      <h3 class="param-libelle">Importer des préférences</h3>
      <p class="param-aide">
        Importez un fichier JSON exporté par Strapontin. Mode Fusionner conserve vos
        données existantes ; mode Écraser les remplace.
      </p>
      <form class="form-vertical form-compact" @submit.prevent="importerPreferences">
        <div class="form-groupe">
          <label for="import-fichier">Fichier JSON</label>
          <div class="form-fichier">
            <input
              id="import-fichier"
              ref="importFichierRef"
              type="file"
              accept=".json,application/json"
              required
              class="form-fichier__input"
              @change="majNomFichier"
            />
            <button
              type="button"
              class="bouton bouton-secondaire"
              @click="importFichierRef?.click()"
            >Choisir un fichier</button>
            <span class="form-fichier__nom">{{ nomFichier }}</span>
          </div>
          <p class="form-aide">Fichier .json exporté depuis Strapontin</p>
        </div>
        <div class="form-groupe">
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
        <div class="form-groupe form-checkboxes">
          <label>
            <input v-model="importerConfig" type="checkbox" />
            Importer la configuration (départements, notifications, thème)
          </label>
        </div>
        <button type="submit" class="bouton bouton-secondaire">Importer</button>
      </form>
    </div>

    <div class="param-bloc param-bloc-danger">
      <h3 class="param-libelle">Supprimer mon compte</h3>
      <p class="param-aide">
        Supprime définitivement votre compte et toutes vos données. Cette action est
        irréversible.
      </p>
      <button type="button" class="bouton bouton-danger" @click="ouvrirDialogSuppression">
        Supprimer mon compte
      </button>
    </div>
  </section>

  <dialog ref="dialogSuppressionRef" id="dialog-supprimer-compte">
    <p>
      Supprime définitivement votre compte et toutes vos données.
      Cette action est irréversible.
    </p>
    <div class="form-groupe">
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
      <button type="button" class="bouton bouton-secondaire" @click="fermerDialogSuppression">
        Annuler
      </button>
      <button
        type="button"
        class="bouton bouton-danger"
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
import EtatChargement from '../EtatChargement.vue'
import { corpsJsonOuVide } from '../../utils/reponseJson.js'

const router = useRouter()
const { afficherToast } = useToasts()
const { dialogRef: dialogSuppressionRef, ouvrir: ouvrirDialogSuppressionModal, fermer: fermerDialogSuppressionModal } = useDialog()

const chargement = ref(true)
const stats = shallowRef(null)
const importFichierRef = ref(null)
const importMode = ref('fusionner')
const importerConfig = ref(false)
const confirmationSuppression = ref('')
const nomFichier = ref('Aucun fichier sélectionné')

function majNomFichier() {
  const fichierEl = importFichierRef.value
  nomFichier.value = fichierEl?.files?.[0]?.name ?? 'Aucun fichier sélectionné'
}

async function charger() {
  try {
    const rep = await fetch('/api/parametres/stats')
    if (rep.ok) stats.value = await rep.json()
  } catch (_) {
    afficherToast('Erreur lors du chargement des statistiques.', 'erreur')
  } finally {
    chargement.value = false
  }
}

async function importerPreferences() {
  const fichierEl = importFichierRef.value
  if (!fichierEl?.files?.length) return

  const formData = new FormData()
  formData.append('fichier', fichierEl.files[0])
  formData.append('mode', importMode.value)
  formData.append('importerConfig', importerConfig.value ? 'true' : 'false')

  try {
    const rep = await fetch('/api/preferences/importer', {
      method: 'POST',
      body: formData,
    })
    const corps = await corpsJsonOuVide(rep)
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
      nomFichier.value = 'Aucun fichier sélectionné'
    } else {
      afficherToast(corps.erreur || "Erreur lors de l'import.", 'erreur')
    }
  } catch (_) {
    afficherToast("Erreur réseau lors de l'import.", 'erreur')
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
  try {
    const rep = await fetch('/api/user', { method: 'DELETE' })
    if (rep.ok || rep.status === 204) {
      fermerDialogSuppression()
      afficherToast('Compte supprimé.', 'succes')
      router.push('/login')
    } else {
      fermerDialogSuppression()
      const corps = await corpsJsonOuVide(rep)
      afficherToast(corps.erreur || 'Erreur lors de la suppression du compte.', 'erreur')
    }
  } catch (_) {
    fermerDialogSuppression()
    afficherToast('Erreur réseau lors de la suppression du compte.', 'erreur')
  }
}

onMounted(charger)
</script>
