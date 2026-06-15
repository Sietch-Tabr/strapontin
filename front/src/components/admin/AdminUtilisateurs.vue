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
                <span v-if="u.username" class="badge badge-mono">@{{ u.username }}</span>
                <span v-if="u.role === 'admin'" class="badge badge-info">admin</span>
                <span v-if="u.mustChangeCredentials" class="badge badge-warn">activation en attente</span>
                <span v-if="u.revocationAdminLe" class="badge badge-warn">révocation {{ joursAvantRevocation(u.revocationAdminLe) }}</span>
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
              <button type="button" class="btn btn-sm btn-secondary" @click="reinitTotp(u)">Réinit. 2FA</button>
              <button
                v-if="u.revocationAdminLe"
                type="button"
                class="btn btn-sm btn-secondary"
                @click="annulerRevocation(u)"
              >Annuler la révocation</button>
              <button type="button" class="btn btn-sm btn-danger" @click="ouvrirDialogSupprimer(u)">Supprimer</button>
            </div>
          </div>
          <div :id="'modif-' + u.id" class="modif-ligne" :class="{ visible: modifOuverts.has(u.id) }">
            <form class="form-modif-utilisateur" @submit.prevent="sauvegarderModif(u)">
              <input v-model="modifData[u.id].nom" type="text" placeholder="Nom d'affichage" aria-label="Nom d'affichage de l'utilisateur" />
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
    <p id="dialog-supprimer-user-nom" class="dialog-corps">{{ userASupprimer?.email }}</p>
    <div class="dialog-pied">
      <button type="button" id="dialog-supprimer-user-confirmer" class="btn btn-danger" @click="confirmerSuppression">Supprimer</button>
      <button type="button" id="dialog-supprimer-user-annuler" class="btn btn-secondary" @click="fermerDialogSupprimer">Annuler</button>
    </div>
  </dialog>

  <dialog ref="dialogReinitMdpRef" id="dialog-reinit-mdp">
    <p class="dialog-titre">Réinitialiser le mot de passe ?</p>
    <p class="dialog-corps">Un email de réinitialisation sera envoyé à <strong>{{ userAReinitMdp?.email }}</strong>.</p>
    <div class="dialog-pied">
      <button type="button" id="dialog-reinit-mdp-confirmer" class="btn btn-secondary" @click="confirmerReinitMdp">Envoyer</button>
      <button type="button" id="dialog-reinit-mdp-annuler" class="btn btn-secondary" @click="fermerDialogReinitMdp">Annuler</button>
    </div>
  </dialog>

  <dialog ref="dialogReinitTotpRef" id="dialog-reinit-totp">
    <p class="dialog-titre">Désactiver la 2FA ?</p>
    <p class="dialog-corps">La double authentification de <strong>{{ userAReinitTotp?.email }}</strong> sera désactivée.</p>
    <div class="dialog-pied">
      <button type="button" id="dialog-reinit-totp-confirmer" class="btn btn-secondary" @click="confirmerReinitTotp">Désactiver</button>
      <button type="button" id="dialog-reinit-totp-annuler" class="btn btn-secondary" @click="fermerDialogReinitTotp">Annuler</button>
    </div>
  </dialog>

  <dialog ref="dialogConfirmerRoleRef" id="dialog-confirmer-role">
    <p class="dialog-titre">Confirmer le changement de rôle ?</p>
    <p class="dialog-corps" id="dialog-confirmer-role-texte">{{ texteConfirmationRole }}</p>
    <div class="dialog-pied">
      <button type="button" id="dialog-confirmer-role-ok" class="btn btn-secondary" @click="confirmerChangementRole">Confirmer</button>
      <button type="button" id="dialog-confirmer-role-annuler" class="btn btn-secondary" @click="fermerDialogConfirmerRole">Annuler</button>
    </div>
  </dialog>

  <dialog ref="dialogInvitationRef" id="dialog-invitation">
    <template v-if="lienInvitation">
      <p class="dialog-titre">Compte créé</p>
      <p class="dialog-corps">
        Aucun service email n'est configuré : transmettez ce lien à <strong>{{ inviteEmail }}</strong> pour qu'il finalise son inscription. Il expire après utilisation.
      </p>
      <div class="dialog-lien">
        <input id="lien-invitation" type="text" readonly :value="lienInvitation" aria-label="Lien d'invitation" />
        <button type="button" class="btn btn-secondary" @click="copierLien">Copier</button>
      </div>
    </template>
    <template v-else>
      <p class="dialog-titre">Invitation envoyée</p>
      <p class="dialog-corps">
        Un email d'invitation a été envoyé à <strong>{{ inviteEmail }}</strong>. Le lien est valable 7 jours.
      </p>
    </template>
    <div class="dialog-pied">
      <button type="button" id="btn-fermer-invitation" class="btn btn-primary" @click="fermerDialogInvitation">Fermer</button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'AdminUtilisateurs' })

import { ref, shallowRef, reactive, onMounted, computed } from 'vue'
import { useDialog } from '../../composables/useDialog.js'
import { useToasts } from '../../composables/useToasts.js'
import { formaterInstant } from '../../composables/useDates.js'

const { afficherToast } = useToasts()
const { dialogRef: dialogSupprimerUserRef, ouvrir: ouvrirDialogSupprimerModal, fermer: fermerDialogSupprimerModal } = useDialog()
const { dialogRef: dialogInvitationRef, ouvrir: ouvrirDialogInvitationModal, fermer: fermerDialogInvitationModal } = useDialog()
const { dialogRef: dialogReinitMdpRef, ouvrir: ouvrirDialogReinitMdpModal, fermer: fermerDialogReinitMdpModal } = useDialog()
const { dialogRef: dialogReinitTotpRef, ouvrir: ouvrirDialogReinitTotpModal, fermer: fermerDialogReinitTotpModal } = useDialog()
const { dialogRef: dialogConfirmerRoleRef, ouvrir: ouvrirDialogConfirmerRoleModal, fermer: fermerDialogConfirmerRoleModal } = useDialog()

const utilisateurs = shallowRef([])
const utilisateurCourantId = ref(null)
const nouvelEmail = ref('')
const modifOuverts = ref(new Set())
const modifData = reactive({})
const modifErreurs = reactive({})
const userASupprimer = ref(null)
const userAReinitMdp = ref(null)
const userAReinitTotp = ref(null)
const inviteEmail = ref('')
const lienInvitation = ref('')
const modifEnAttenteRole = ref(null)

const texteConfirmationRole = computed(() => {
  if (!modifEnAttenteRole.value) return ''
  const { user: u, data: d } = modifEnAttenteRole.value
  if (u.role === 'admin' && d.role === 'user') {
    return `La révocation de ${u.email} sera effective dans 7 jours. Un email de préavis lui sera envoyé.`
  }
  return `${u.email} sera promu administrateur. Un email de confirmation lui sera envoyé.`
})

function joursAvantRevocation(revocationAdminLe) {
  const diff = new Date(revocationAdminLe) - new Date()
  const jours = Math.ceil(diff / (1000 * 60 * 60 * 24))
  if (jours <= 0) return 'imminente'
  return `dans ${jours} jour${jours > 1 ? 's' : ''}`
}

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
      modifData[u.id] = { nom: u.displayUsername ?? '', email: u.email ?? '', role: u.role ?? 'user' }
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
    inviteEmail.value = corps.email ?? ''
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
  if (navigator.clipboard) {
    navigator.clipboard.writeText(lienInvitation.value)
    afficherToast('Lien copié.', 'succes')
  }
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
  const roleChange = d.role !== u.role
  const retrograder = u.role === 'admin' && d.role === 'user'

  if (roleChange) {
    modifEnAttenteRole.value = { user: u, data: { ...d } }
    ouvrirDialogConfirmerRoleModal()
    return
  }

  await envoyerPatch(u, d)
}

async function envoyerPatch(u, d) {
  const corps = {}
  if (d.nom !== (u.displayUsername ?? '')) corps.nom = d.nom
  if (d.email !== u.email) corps.email = d.email
  if (d.role !== u.role) corps.role = d.role

  if (Object.keys(corps).length === 0) {
    toggleModif(u.id)
    return
  }

  const rep = await fetch('/api/admin/utilisateurs/' + u.id, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(corps),
  })
  const repCorps = await rep.json()
  if (rep.ok) {
    toggleModif(u.id)
    await rechargerUtilisateurs()
    afficherToast('Utilisateur modifié.', 'succes')
  } else {
    modifErreurs[u.id] = repCorps.erreur || 'Erreur.'
  }
}

function fermerDialogConfirmerRole() {
  fermerDialogConfirmerRoleModal()
  modifEnAttenteRole.value = null
}

async function confirmerChangementRole() {
  if (!modifEnAttenteRole.value) return
  const { user: u, data: d } = modifEnAttenteRole.value
  const retrograder = u.role === 'admin' && d.role === 'user'
  fermerDialogConfirmerRole()

  if (retrograder) {
    const rep = await fetch('/api/admin/utilisateurs/' + u.id + '/revoquer-admin', { method: 'POST' })
    const corps = await rep.json()
    if (rep.ok) {
      toggleModif(u.id)
      await rechargerUtilisateurs()
      afficherToast('Révocation initiée. L\'administrateur sera prévenu par email.', 'succes')
    } else {
      modifErreurs[u.id] = corps.erreur || 'Erreur.'
    }
  } else {
    await envoyerPatch(u, d)
  }
}

async function annulerRevocation(u) {
  const rep = await fetch('/api/admin/utilisateurs/' + u.id + '/revoquer-admin', { method: 'DELETE' })
  const corps = await rep.json()
  if (rep.ok) {
    await rechargerUtilisateurs()
    afficherToast('Révocation annulée.', 'succes')
  } else {
    afficherToast(corps.erreur || 'Erreur.', 'erreur')
  }
}

async function rechargerUtilisateurs() {
  const repUsers = await fetch('/api/admin/utilisateurs')
  if (repUsers.ok) {
    utilisateurs.value = await repUsers.json()
    initialiserModifData()
  }
}

function reinitMdp(u) {
  userAReinitMdp.value = u
  ouvrirDialogReinitMdpModal()
}

function fermerDialogReinitMdp() {
  fermerDialogReinitMdpModal()
  userAReinitMdp.value = null
}

async function confirmerReinitMdp() {
  if (!userAReinitMdp.value) return
  const userId = userAReinitMdp.value.id
  fermerDialogReinitMdp()
  const rep = await fetch('/api/admin/utilisateurs/' + userId + '/reset-mdp', { method: 'POST' })
  const corps = await rep.json()
  if (rep.ok) afficherToast('Email de réinitialisation envoyé.', 'succes')
  else afficherToast(corps.erreur || 'Erreur.', 'erreur')
}

function reinitTotp(u) {
  userAReinitTotp.value = u
  ouvrirDialogReinitTotpModal()
}

function fermerDialogReinitTotp() {
  fermerDialogReinitTotpModal()
  userAReinitTotp.value = null
}

async function confirmerReinitTotp() {
  if (!userAReinitTotp.value) return
  const userId = userAReinitTotp.value.id
  fermerDialogReinitTotp()
  const rep = await fetch('/api/admin/utilisateurs/' + userId + '/reset-totp', { method: 'POST' })
  const corps = await rep.json()
  if (rep.ok) afficherToast('2FA réinitialisée. L\'utilisateur pourra se reconnecter sans code.', 'succes')
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
