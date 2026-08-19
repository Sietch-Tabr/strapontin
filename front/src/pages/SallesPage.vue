<template>
  <div>
    <div class="page-header">
      <h1>Mes salles</h1>
      <p v-if="donnees" class="page-subtitle">
        {{ donnees.salles.length }} salle{{ donnees.salles.length !== 1 ? 's' : '' }} suivie{{ donnees.salles.length !== 1 ? 's' : '' }}
      </p>
    </div>

    <form class="form-recherche" @submit.prevent="rechercher">
      <label for="q-salle" class="sr-only">Rechercher une salle</label>
      <input
        id="q-salle"
        v-model="q"
        type="text"
        placeholder="Rechercher ou saisir une salle…"
        minlength="2"
        autocomplete="off"
      />
      <button type="submit" class="btn btn-secondary">Rechercher</button>
    </form>
    <p class="form-aide">Saisissez au moins 2 caractères. La recherche porte sur les concerts en base, les salles Digitick configurées et les suggestions OpenStreetMap/Ticketmaster. Si une salle n'apparaît pas, vous pouvez l'ajouter directement par nom.</p>

    <div v-if="erreur" class="alerte alerte-erreur" role="alert" aria-live="assertive">{{ erreur }}</div>

    <EtatChargement v-if="chargement" />

    <template v-else-if="donnees">
      <section
        v-if="donnees.resultats && donnees.resultats.length > 0"
        class="section-resultats"
        aria-live="polite"
        aria-atomic="false"
      >
        <h2 class="section-label">Résultats pour « {{ qSoumis }} »</h2>
        <ul class="liste-cartes">
          <li v-for="r in donnees.resultats" :key="r.salle + r.tmVenueId + r.digitickSlug + r.osmId" class="carte-item">
            <div>
              <p class="carte-nom">{{ r.salle }}</p>
              <p v-if="r.ville" class="carte-sous-titre">{{ r.ville }}</p>
              <span v-if="r.tmVenueId" class="badge badge-source">TM</span>
              <span v-if="r.osmId" class="badge badge-source badge-source-metadonnees">osm</span>
              <span v-if="r.digitickSlug" class="badge badge-source badge-source-digitick">Digitick</span>
            </div>
            <button
              type="button"
              class="btn btn-sm btn-secondary"
              :aria-label="'Suivre ' + r.salle"
              @click="ajouter(r.salle, r.tmVenueId, r.osmId, r.wikidataId)"
            >Suivre</button>
          </li>
        </ul>
      </section>

      <div
        v-else-if="qSoumis && qSoumis.trim().length >= 2"
        class="section-resultats"
        aria-live="polite"
      >
        <div v-if="donnees.dejaLaSuivie" class="alerte alerte-info" role="status">
          Vous suivez déjà « {{ qSoumis }} ».
        </div>
        <div v-else class="alerte alerte-info" role="status">
          <p style="margin: 0 0 10px">Aucun résultat pour « {{ qSoumis }} ».</p>
          <button
            type="button"
            class="btn btn-sm btn-secondary"
            @click="ouvrirSignalement"
          >Signaler cette salle</button>
        </div>
      </div>

      <div v-if="donnees.salles.length === 0" class="etat-vide">
        <p class="etat-vide-titre">Aucune salle suivie.</p>
        <p class="etat-vide-texte">Recherchez une salle ci-dessus pour commencer.</p>
      </div>

      <section v-else>
        <h2 class="section-label">Salles suivies</h2>
        <ul class="liste-cartes">
          <li v-for="salle in donnees.salles" :key="salle.id" class="carte-item">
            <span class="carte-nom">
              {{ salle.nom }}
              <span v-if="salle.aDigitick" class="badge badge-source badge-source-digitick">Digitick</span>
            </span>
            <button
              type="button"
              class="btn btn-sm btn-danger-texte"
              :aria-label="'Supprimer ' + salle.nom"
              @click="demanderSuppression(salle)"
            >Supprimer</button>
          </li>
        </ul>
      </section>
    </template>

    <dialog
      ref="dialogRef"
      id="dialog-confirmer-suppr-salle"
      aria-labelledby="titre-confirmer-suppr-salle"
      aria-modal="true"
    >
      <div class="dialog-entete">
        <h2 id="titre-confirmer-suppr-salle" class="dialog-titre">Supprimer la salle</h2>
      </div>
      <p>Arrêter de suivre « <strong>{{ salleASupprimer?.nom }}</strong> » ?</p>
      <div class="dialog-pied">
        <button type="button" class="btn btn-danger" @click="confirmerSuppression">Supprimer</button>
        <button type="button" class="btn btn-texte" @click="dialogRef.close()">Annuler</button>
      </div>
    </dialog>

    <dialog
      ref="dialogSignalementRef"
      id="dialog-signaler-salle"
      aria-labelledby="titre-signaler-salle"
      aria-modal="true"
    >
      <div class="dialog-entete">
        <h2 id="titre-signaler-salle" class="dialog-titre">Signaler une salle manquante</h2>
      </div>
      <form id="form-signaler-salle" @submit.prevent="envoyerSignalement">
        <div class="champ">
          <label for="sig-nom" class="champ-label">Nom de la salle <span aria-hidden="true">*</span></label>
          <input id="sig-nom" v-model="signalement.nom" type="text" required maxlength="200" autocomplete="off" />
        </div>
        <div class="champ">
          <label for="sig-ville" class="champ-label">Ville / code postal</label>
          <input id="sig-ville" v-model="signalement.ville" type="text" maxlength="100" autocomplete="off" />
        </div>
        <div class="champ">
          <label for="sig-url" class="champ-label">Site ou billetterie</label>
          <input id="sig-url" v-model="signalement.urlSite" type="url" maxlength="500" autocomplete="off" />
        </div>
        <div class="champ">
          <label for="sig-commentaire" class="champ-label">Commentaire</label>
          <textarea id="sig-commentaire" v-model="signalement.commentaire" rows="3" maxlength="1000"></textarea>
        </div>
        <div class="dialog-pied">
          <button type="submit" class="btn btn-secondary" :disabled="signalementEnCours">Envoyer</button>
          <button type="button" class="btn btn-texte" @click="dialogSignalementRef.close()">Annuler</button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup>
defineOptions({ name: 'SallesPage' })

import { ref, shallowRef, onMounted } from 'vue'
import { useDialog } from '../composables/useDialog.js'
import { useToasts } from '../composables/useToasts.js'
import EtatChargement from '../components/EtatChargement.vue'
import { corpsJsonOuVide } from '../utils/reponseJson.js'

const { afficherToast } = useToasts()

const chargement = ref(true)
const donnees = shallowRef(null)
const q = ref('')
const qSoumis = ref('')
const erreur = ref('')
const salleASupprimer = ref(null)
const signalement = ref({ nom: '', ville: '', urlSite: '', commentaire: '' })
const signalementEnCours = ref(false)
const { dialogRef, ouvrir: ouvrirDialog, fermer: fermerDialog } = useDialog()
const { dialogRef: dialogSignalementRef, ouvrir: ouvrirDialogSignalement } = useDialog()

async function charger(recherche = '') {
  const params = recherche.trim().length >= 2 ? '?q=' + encodeURIComponent(recherche) : ''
  const rep = await fetch('/api/salles' + params)
  if (!rep.ok) throw new Error()
  return rep.json()
}

async function rechercher() {
  erreur.value = ''
  try {
    qSoumis.value = q.value
    donnees.value = await charger(q.value)
  } catch {
    afficherToast('Impossible de rechercher.', 'erreur')
  }
}

async function ajouter(nom, tmVenueId, osmId = null, wikidataId = null) {
  erreur.value = ''
  try {
    const rep = await fetch('/api/salles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nom, tmVenueId: tmVenueId || null, osmId, wikidataId }),
    })
    if (rep.status === 409) {
      afficherToast('Vous suivez déjà cette salle.', 'info')
      return
    }
    if (!rep.ok) {
      const data = await corpsJsonOuVide(rep)
      afficherToast(data.erreur || 'Erreur lors de l\'ajout.', 'erreur')
      return
    }
    afficherToast('Salle ajoutée — les concerts apparaîtront dans quelques secondes.', 'succes')
    q.value = ''
    qSoumis.value = ''
    donnees.value = await charger()
  } catch {
    afficherToast('Impossible d\'ajouter cette salle.', 'erreur')
  }
}

function demanderSuppression(salle) {
  salleASupprimer.value = salle
  ouvrirDialog()
}

async function confirmerSuppression() {
  fermerDialog()
  if (!salleASupprimer.value) return
  try {
    const rep = await fetch('/api/salles/' + salleASupprimer.value.id, {
      method: 'DELETE',
    })
    if (!rep.ok) throw new Error()
    afficherToast('Salle retirée de votre liste.', 'succes')
    donnees.value = await charger()
  } catch {
    afficherToast('Impossible de supprimer cette salle.', 'erreur')
  } finally {
    salleASupprimer.value = null
  }
}

function ouvrirSignalement() {
  signalement.value = { nom: qSoumis.value.trim(), ville: '', urlSite: '', commentaire: '' }
  ouvrirDialogSignalement()
}

async function envoyerSignalement() {
  signalementEnCours.value = true
  try {
    const rep = await fetch('/api/salles/signaler', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(signalement.value),
    })
    if (rep.status === 429) {
      afficherToast('Trop de signalements. Réessayez dans une heure.', 'erreur')
      return
    }
    if (!rep.ok) {
      const data = await corpsJsonOuVide(rep)
      afficherToast(data.erreur || 'Erreur lors du signalement.', 'erreur')
      return
    }
    dialogSignalementRef.value.close()
    afficherToast('Votre signalement a été transmis.', 'succes')
  } catch {
    afficherToast('Impossible d\'envoyer le signalement.', 'erreur')
  } finally {
    signalementEnCours.value = false
  }
}

onMounted(async () => {
  try {
    donnees.value = await charger()
  } catch {
    afficherToast('Impossible de charger les salles.', 'erreur')
  } finally {
    chargement.value = false
  }
})
</script>
