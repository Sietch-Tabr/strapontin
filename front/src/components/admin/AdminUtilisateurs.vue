<template>
  <section class="admin-section" id="utilisateurs">

    <div class="param-bloc">
      <h3 class="param-label">Créer un compte</h3>
      <form class="form-creer-utilisateur form-inline" @submit.prevent="creerUtilisateur">
        <input
          v-model="nouvelEmail"
          type="email"
          required
          placeholder="adresse@email.fr"
          aria-label="Adresse email du nouvel utilisateur"
          autocomplete="off"
        />
        <button type="submit" class="btn btn-secondary">Inviter</button>
      </form>
    </div>

    <div v-if="utilisateurs.length" class="param-bloc">
      <h3 class="param-label">Utilisateurs ({{ utilisateurs.length }})</h3>
      <ul class="user-liste">
        <li v-for="u in utilisateurs" :key="u.id" class="user-carte">
          <div class="user-carte-entete">
            <div class="user-carte-info">
              <div class="user-carte-badges">
                <span class="user-nom">{{ u.name }}</span>
                <span v-if="u.username" class="badge badge-mono">@{{ u.username }}</span>
                <span v-if="u.role === 'admin'" class="badge badge-info">admin</span>
                <span v-if="u.mustChangeCredentials" class="badge badge-warn">premier login en attente</span>
              </div>
              <p class="user-carte-email">{{ u.email }}</p>
              <p class="user-carte-activite">
                <span v-if="u.derniereActivite">Dernière activité : {{ formaterInstant(u.derniereActivite) }}</span>
                <span v-else>Jamais connecté</span>
              </p>
            </div>
            <div v-if="u.id !== utilisateurCourantId" class="user-carte-actions">
              <button type="button" class="btn btn-sm btn-secondary" @click="toggleModif(u.id)">Modifier</button>
              <button type="button" class="btn btn-sm btn-secondary" @click="reinitMdp(u)">Réinit. MDP</button>
              <button type="button" class="btn btn-sm btn-danger" @click="ouvrirDialogSupprimer(u)">Supprimer</button>
            </div>
          </div>
          <div :id="'modif-' + u.id" class="modif-ligne" :class="{ visible: modifOuverts.has(u.id) }">
            <form class="form-modif-utilisateur" @submit.prevent="sauvegarderModif(u)">
              <input v-model="modifData[u.id].nom" type="text" maxlength="100" placeholder="Nom" aria-label="Nom de l'utilisateur" />
              <input v-model="modifData[u.id].email" type="email" placeholder="Email" aria-label="Adresse email de l'utilisateur" />
              <select v-model="modifData[u.id].role" aria-label="Rôle de l'utilisateur">
                <option value="user">user</option>
                <option value="admin">admin</option>
              </select>
              <button type="submit" class="btn btn-sm btn-primary">Enregistrer</button>
              <button type="button" class="btn btn-sm btn-secondary" @click="toggleModif(u.id)">Annuler</button>
              <span v-if="modifErreurs[u.id]" class="erreur-modif">{{ modifErreurs[u.id] }}</span>
            </form>
          </div>
        </li>
      </ul>
    </div>

  </section>

  <dialog ref="dialogSupprimerUserRef" id="dialog-supprimer-user">
    <p class="dialog-titre">Supprimer ce compte ?</p>
    <p id="dialog-supprimer-user-nom" class="dialog-corps">{{ userASupprimer?.name }}</p>
    <div class="dialog-pied">
      <button type="button" id="dialog-supprimer-user-confirmer" class="btn btn-danger" @click="confirmerSuppression">Supprimer</button>
      <button type="button" id="dialog-supprimer-user-annuler" class="btn btn-secondary" @click="fermerDialogSupprimer">Annuler</button>
    </div>
  </dialog>

  <dialog ref="dialogInvitationRef" id="dialog-invitation">
    <p class="dialog-titre">Compte créé</p>
    <p class="dialog-corps">
      Envoyez ce lien à l'utilisateur pour qu'il finalise l'inscription. Il expire après utilisation.
    </p>
    <div class="dialog-lien">
      <input id="lien-invitation" type="text" readonly :value="lienInvitation" />
      <button type="button" class="btn btn-secondary" @click="copierLien">Copier</button>
    </div>
    <div class="dialog-pied">
      <button type="button" id="btn-fermer-invitation" class="btn btn-primary" @click="fermerDialogInvitation">Fermer</button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'AdminUtilisateurs' })

import { ref, shallowRef, reactive, onMounted } from 'vue'
import { useDialog } from '../../composables/useDialog.js'
import { useToasts } from '../../composables/useToasts.js'
import { formaterInstant } from '../../composables/useDates.js'

const { afficherToast } = useToasts()
const { dialogRef: dialogSupprimerUserRef, ouvrir: ouvrirDialogSupprimerModal, fermer: fermerDialogSupprimerModal } = useDialog()
const { dialogRef: dialogInvitationRef, ouvrir: ouvrirDialogInvitationModal, fermer: fermerDialogInvitationModal } = useDialog()

const utilisateurs = shallowRef([])
const utilisateurCourantId = ref(null)
const nouvelEmail = ref('')
const modifOuverts = ref(new Set())
const modifData = reactive({})
const modifErreurs = reactive({})
const userASupprimer = ref(null)
const lienInvitation = ref('')

async function charger() {
  try {
    const [repUsers, repProfil] = await Promise.all([
      fetch('/api/admin/utilisateurs'),
      fetch('/api/user/profile'),
    ])
    if (repUsers.ok) {
      utilisateurs.value = await repUsers.json()
      initialiserModifData()
    }
    if (repProfil.ok) {
      const profil = await repProfil.json()
      utilisateurCourantId.value = profil.id ?? null
    }
  } catch (_) {
    afficherToast('Erreur lors du chargement des utilisateurs.', 'erreur')
  }
}

function initialiserModifData() {
  for (const u of utilisateurs.value) {
    if (!modifData[u.id]) {
      modifData[u.id] = { nom: u.name ?? '', email: u.email ?? '', role: u.role ?? 'user' }
    }
  }
}

async function creerUtilisateur() {
  const email = nouvelEmail.value.trim()
  if (!email) return
  const rep = await fetch('/api/admin/utilisateurs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  })
  const corps = await rep.json()
  if (rep.ok || rep.status === 201) {
    nouvelEmail.value = ''
    lienInvitation.value = corps.lienReset ?? ''
    ouvrirDialogInvitationModal()
    const repUsers = await fetch('/api/admin/utilisateurs')
    if (repUsers.ok) {
      utilisateurs.value = await repUsers.json()
      initialiserModifData()
    }
  } else {
    afficherToast(corps.erreur || 'Impossible de créer le compte.', 'erreur')
  }
}

function copierLien() {
  if (navigator.clipboard) navigator.clipboard.writeText(lienInvitation.value)
}

function fermerDialogInvitation() {
  fermerDialogInvitationModal()
}

function toggleModif(userId) {
  const s = new Set(modifOuverts.value)
  if (s.has(userId)) s.delete(userId)
  else s.add(userId)
  modifOuverts.value = s
}

async function sauvegarderModif(u) {
  delete modifErreurs[u.id]
  const d = modifData[u.id]
  const rep = await fetch('/api/admin/utilisateurs/' + u.id, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nom: d.nom, email: d.email, role: d.role }),
  })
  const corps = await rep.json()
  if (rep.ok) {
    toggleModif(u.id)
    const repUsers = await fetch('/api/admin/utilisateurs')
    if (repUsers.ok) {
      utilisateurs.value = await repUsers.json()
      initialiserModifData()
    }
    afficherToast('Utilisateur modifié.', 'succes')
  } else {
    modifErreurs[u.id] = corps.erreur || 'Erreur.'
  }
}

async function reinitMdp(u) {
  const rep = await fetch('/api/admin/utilisateurs/' + u.id + '/reset-mdp', { method: 'POST' })
  const corps = await rep.json()
  if (rep.ok) afficherToast('Email de réinitialisation envoyé.', 'succes')
  else afficherToast(corps.erreur || 'Erreur.', 'erreur')
}

function ouvrirDialogSupprimer(u) {
  userASupprimer.value = u
  ouvrirDialogSupprimerModal()
}

function fermerDialogSupprimer() {
  fermerDialogSupprimerModal()
  userASupprimer.value = null
}

async function confirmerSuppression() {
  if (!userASupprimer.value) return
  const userId = userASupprimer.value.id
  fermerDialogSupprimer()
  const rep = await fetch('/api/admin/utilisateurs/' + userId, { method: 'DELETE' })
  const corps = await rep.json()
  if (rep.ok) {
    utilisateurs.value = utilisateurs.value.filter((u) => u.id !== userId)
    afficherToast('Compte supprimé.', 'succes')
  } else {
    afficherToast(corps.erreur || 'Erreur lors de la suppression.', 'erreur')
  }
}

onMounted(charger)
</script>
