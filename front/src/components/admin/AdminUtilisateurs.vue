<template>
  <section id="utilisateurs" class="admin-section">
    <div class="param-bloc">
      <h3 class="param-libelle">Créer un compte</h3>
      <form
        class="form-creer-utilisateur form-inline"
        @submit.prevent="creerUtilisateur"
      >
        <input
          v-model="nouvelEmail"
          type="email"
          required
          placeholder="adresse@email.fr"
          aria-label="Adresse email du nouvel utilisateur"
          autocomplete="off"
        />
        <button type="submit" class="bouton bouton-secondaire">Inviter</button>
      </form>
    </div>

    <EtatChargement v-if="chargement" />
    <div v-else-if="utilisateurs.length" class="param-bloc">
      <h3 class="param-libelle">Utilisateurs ({{ total }})</h3>
      <ul class="user-liste">
        <li v-for="u in utilisateurs" :key="u.id" class="user-carte">
          <div class="user-carte-entete">
            <div class="user-carte-info">
              <div class="user-carte-badges">
                <span v-if="u.username" class="badge badge-mono"
                  >@{{ u.username }}</span
                >
                <span v-if="u.role === 'admin'" class="badge badge-info"
                  >admin</span
                >
                <span
                  v-if="u.mustChangeCredentials"
                  class="badge badge-avertissement"
                  >activation en attente</span
                >
                <span
                  v-if="u.revocationAdminLe"
                  class="badge badge-avertissement"
                  >révocation
                  {{ joursAvantRevocation(u.revocationAdminLe) }}</span
                >
                <span v-if="u.verrouille" class="badge badge-danger"
                  >verrouillé</span
                >
              </div>
              <p v-if="u.displayUsername" class="user-carte-nom">
                {{ u.displayUsername }}
              </p>
              <p class="user-carte-email">{{ u.email }}</p>
              <p class="user-carte-activite">
                <span v-if="u.derniereActivite"
                  >Dernière activité :
                  {{ formaterInstant(u.derniereActivite) }}</span
                >
                <span v-else>Aucune session active</span>
              </p>
              <p class="user-carte-suivi">
                {{ u.nbArtistes }} artiste{{ u.nbArtistes !== 1 ? "s" : "" }},
                {{ u.nbSalles }} salle{{ u.nbSalles !== 1 ? "s" : "" }}
              </p>
            </div>
            <div
              v-if="u.id !== utilisateurCourantId"
              class="user-carte-actions"
            >
              <button
                type="button"
                class="bouton bouton-petit bouton-secondaire"
                @click="ouvrirModif(u.id)"
              >
                Modifier
              </button>
              <button
                type="button"
                class="bouton bouton-petit bouton-secondaire"
                @click="reinitMdp(u)"
              >
                Réinit. MDP
              </button>
              <button
                type="button"
                class="bouton bouton-petit bouton-secondaire"
                @click="reinitTotp(u)"
              >
                Réinit. 2FA
              </button>
              <button
                type="button"
                class="bouton bouton-petit bouton-secondaire"
                @click="revoquerSessions(u)"
              >
                Révoquer sessions
              </button>
              <button
                v-if="u.revocationAdminLe"
                type="button"
                class="bouton bouton-petit bouton-secondaire"
                @click="annulerRevocation(u)"
              >
                Annuler la révocation
              </button>
              <button
                v-if="!u.verrouille"
                type="button"
                class="bouton bouton-petit bouton-danger"
                @click="verrouillerCompte(u)"
              >
                Verrouiller
              </button>
              <button
                v-else
                type="button"
                class="bouton bouton-petit bouton-secondaire"
                @click="deverrouillerCompte(u)"
              >
                Déverrouiller
              </button>
              <button
                type="button"
                class="bouton bouton-petit bouton-danger"
                @click="ouvrirDialogSupprimer(u)"
              >
                Supprimer
              </button>
            </div>
          </div>
          <div
            :id="'modif-' + u.id"
            class="modif-ligne"
            :class="{ visible: modifOuverts.has(u.id) }"
          >
            <form
              class="form-modif-utilisateur"
              @submit.prevent="sauvegarderModif(u)"
            >
              <input
                v-model="modifData[u.id].nom"
                type="text"
                placeholder="Nom d'affichage"
                aria-label="Nom d'affichage de l'utilisateur"
              />
              <input
                v-model="modifData[u.id].email"
                type="email"
                placeholder="Email"
                aria-label="Adresse email de l'utilisateur"
              />
              <select
                v-model="modifData[u.id].role"
                aria-label="Rôle de l'utilisateur"
              >
                <option value="user">user</option>
                <option value="admin">admin</option>
              </select>
              <button type="submit" class="bouton bouton-petit bouton-primaire">
                Enregistrer
              </button>
              <button
                type="button"
                class="bouton bouton-petit bouton-secondaire"
                @click="fermerModif(u.id)"
              >
                Annuler
              </button>
              <span v-if="modifErreurs[u.id]" class="erreur-modif">{{
                modifErreurs[u.id]
              }}</span>
            </form>
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
          >Page {{ page + 1 }} / {{ totalPages }} ({{
            total
          }}
          utilisateurs)</span
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

  <dialog id="dialog-supprimer-user" ref="dialogSupprimerUserRef">
    <p class="dialog-titre">Supprimer ce compte ?</p>
    <p id="dialog-supprimer-user-nom" class="dialog-corps">
      {{ userASupprimer?.email }}
    </p>
    <div class="dialog-pied">
      <button
        id="dialog-supprimer-user-confirmer"
        type="button"
        class="bouton bouton-danger"
        @click="confirmerSuppression"
      >
        Supprimer
      </button>
      <button
        id="dialog-supprimer-user-annuler"
        type="button"
        class="bouton bouton-secondaire"
        @click="fermerDialogSupprimer"
      >
        Annuler
      </button>
    </div>
  </dialog>

  <dialog id="dialog-reinit-mdp" ref="dialogReinitMdpRef">
    <p class="dialog-titre">Réinitialiser le mot de passe ?</p>
    <p class="dialog-corps">
      Un email de réinitialisation sera envoyé à
      <strong>{{ userAReinitMdp?.email }}</strong
      >.
    </p>
    <div class="dialog-pied">
      <button
        id="dialog-reinit-mdp-confirmer"
        type="button"
        class="bouton bouton-secondaire"
        @click="confirmerReinitMdp"
      >
        Envoyer
      </button>
      <button
        id="dialog-reinit-mdp-annuler"
        type="button"
        class="bouton bouton-secondaire"
        @click="fermerDialogReinitMdp"
      >
        Annuler
      </button>
    </div>
  </dialog>

  <dialog id="dialog-reinit-totp" ref="dialogReinitTotpRef">
    <p class="dialog-titre">Désactiver la 2FA ?</p>
    <p class="dialog-corps">
      La double authentification de
      <strong>{{ userAReinitTotp?.email }}</strong> sera désactivée.
    </p>
    <div class="dialog-pied">
      <button
        id="dialog-reinit-totp-confirmer"
        type="button"
        class="bouton bouton-secondaire"
        @click="confirmerReinitTotp"
      >
        Désactiver
      </button>
      <button
        id="dialog-reinit-totp-annuler"
        type="button"
        class="bouton bouton-secondaire"
        @click="fermerDialogReinitTotp"
      >
        Annuler
      </button>
    </div>
  </dialog>

  <dialog id="dialog-revoquer-sessions" ref="dialogRevoquerSessionsRef">
    <p class="dialog-titre">Révoquer toutes les sessions ?</p>
    <p class="dialog-corps">
      Toutes les sessions actives de
      <strong>{{ userARevoquerSessions?.email }}</strong> seront déconnectées.
    </p>
    <div class="dialog-pied">
      <button
        id="dialog-revoquer-sessions-confirmer"
        type="button"
        class="bouton bouton-secondaire"
        @click="confirmerRevoquerSessions"
      >
        Révoquer
      </button>
      <button
        id="dialog-revoquer-sessions-annuler"
        type="button"
        class="bouton bouton-secondaire"
        @click="fermerDialogRevoquerSessions"
      >
        Annuler
      </button>
    </div>
  </dialog>

  <dialog id="dialog-verrouiller-compte" ref="dialogVerrouillerCompteRef">
    <p class="dialog-titre">Verrouiller ce compte ?</p>
    <p class="dialog-corps">
      <strong>{{ userAVerrouiller?.email }}</strong> ne pourra plus se connecter
      et toutes ses sessions actives seront déconnectées.
    </p>
    <div class="dialog-pied">
      <button
        id="dialog-verrouiller-compte-confirmer"
        type="button"
        class="bouton bouton-danger"
        @click="confirmerVerrouillerCompte"
      >
        Verrouiller
      </button>
      <button
        id="dialog-verrouiller-compte-annuler"
        type="button"
        class="bouton bouton-secondaire"
        @click="fermerDialogVerrouillerCompte"
      >
        Annuler
      </button>
    </div>
  </dialog>

  <dialog id="dialog-confirmer-role" ref="dialogConfirmerRoleRef">
    <p class="dialog-titre">Confirmer le changement de rôle ?</p>
    <p id="dialog-confirmer-role-texte" class="dialog-corps">
      {{ texteConfirmationRole }}
    </p>
    <div class="dialog-pied">
      <button
        id="dialog-confirmer-role-ok"
        type="button"
        class="bouton bouton-secondaire"
        @click="confirmerChangementRole"
      >
        Confirmer
      </button>
      <button
        id="dialog-confirmer-role-annuler"
        type="button"
        class="bouton bouton-secondaire"
        @click="fermerDialogConfirmerRole"
      >
        Annuler
      </button>
    </div>
  </dialog>

  <dialog id="dialog-invitation" ref="dialogInvitationRef">
    <template v-if="lienInvitation">
      <p class="dialog-titre">Compte créé</p>
      <p class="dialog-corps">
        Aucun service email n'est configuré : transmettez ce lien à
        <strong>{{ inviteEmail }}</strong> pour qu'il finalise son inscription.
        Il expire après utilisation.
      </p>
      <div class="dialog-lien">
        <input
          id="lien-invitation"
          type="text"
          readonly
          :value="lienInvitation"
          aria-label="Lien d'invitation"
        />
        <button
          type="button"
          class="bouton bouton-secondaire"
          @click="copierLien"
        >
          Copier
        </button>
      </div>
    </template>
    <template v-else>
      <p class="dialog-titre">Invitation envoyée</p>
      <p class="dialog-corps">
        Un email d'invitation a été envoyé à <strong>{{ inviteEmail }}</strong
        >. Le lien est valable 7 jours.
      </p>
    </template>
    <div class="dialog-pied">
      <button
        id="btn-fermer-invitation"
        type="button"
        class="bouton bouton-primaire"
        @click="fermerDialogInvitation"
      >
        Fermer
      </button>
    </div>
  </dialog>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "AdminUtilisateurs" });

import { ref, shallowRef, reactive, onMounted, computed } from "vue";
import EtatChargement from "../EtatChargement.vue";
import { useDialog } from "../../composables/useDialog.js";
import { useToasts } from "../../composables/useToasts.js";
import { formaterInstant } from "../../composables/useDates.js";

const { afficherToast } = useToasts();
const {
  dialogRef: dialogSupprimerUserRef,
  ouvrir: ouvrirDialogSupprimerModal,
  fermer: fermerDialogSupprimerModal,
} = useDialog();
const {
  dialogRef: dialogInvitationRef,
  ouvrir: ouvrirDialogInvitationModal,
  fermer: fermerDialogInvitationModal,
} = useDialog();
const {
  dialogRef: dialogReinitMdpRef,
  ouvrir: ouvrirDialogReinitMdpModal,
  fermer: fermerDialogReinitMdpModal,
} = useDialog();
const {
  dialogRef: dialogReinitTotpRef,
  ouvrir: ouvrirDialogReinitTotpModal,
  fermer: fermerDialogReinitTotpModal,
} = useDialog();
const {
  dialogRef: dialogRevoquerSessionsRef,
  ouvrir: ouvrirDialogRevoquerSessionsModal,
  fermer: fermerDialogRevoquerSessionsModal,
} = useDialog();
const {
  dialogRef: dialogConfirmerRoleRef,
  ouvrir: ouvrirDialogConfirmerRoleModal,
  fermer: fermerDialogConfirmerRoleModal,
} = useDialog();
const {
  dialogRef: dialogVerrouillerCompteRef,
  ouvrir: ouvrirDialogVerrouillerCompteModal,
  fermer: fermerDialogVerrouillerCompteModal,
} = useDialog();

const utilisateurs = shallowRef([]);
const page = ref(0);
const totalPages = ref(1);
const total = ref(0);
const chargement = ref(true);
const utilisateurCourantId = ref(null);
const nouvelEmail = ref("");
const modifOuverts = ref(new Set());
const modifData = reactive({});
const modifErreurs = reactive({});
const userASupprimer = ref(null);
const userAReinitMdp = ref(null);
const userAReinitTotp = ref(null);
const userARevoquerSessions = ref(null);
const userAVerrouiller = ref(null);
const inviteEmail = ref("");
const lienInvitation = ref("");
const modifEnAttenteRole = ref(null);

const texteConfirmationRole = computed(() => {
  if (!modifEnAttenteRole.value) return "";
  const { user: u, data: d } = modifEnAttenteRole.value;
  if (u.role === "admin" && d.role === "user") {
    return `La révocation de ${u.email} sera effective dans 7 jours. Un email de préavis lui sera envoyé.`;
  }
  return `${u.email} sera promu administrateur. Un email de confirmation lui sera envoyé.`;
});

function joursAvantRevocation(revocationAdminLe) {
  const diff = new Date(revocationAdminLe) - new Date();
  const jours = Math.ceil(diff / (1000 * 60 * 60 * 24));
  if (jours <= 0) return "imminente";
  return `dans ${jours} jour${jours > 1 ? "s" : ""}`;
}

async function charger() {
  try {
    const [repUsers, repProfil] = await Promise.all([
      appelApi(`/api/admin/utilisateurs?page=${page.value}`),
      appelApi("/api/user/profile"),
    ]);
    if (repUsers.ok) {
      const data = await lireJson(repUsers);
      utilisateurs.value = data.utilisateurs ?? [];
      page.value = data.page ?? 0;
      totalPages.value = data.totalPages ?? 1;
      total.value = data.total ?? 0;
      initialiserModifData();
    }
    if (repProfil.ok) {
      const profil = await lireJson(repProfil);
      utilisateurCourantId.value = profil.id ?? null;
    }
  } catch (_) {
    afficherToast("Erreur lors du chargement des utilisateurs.", "erreur");
  } finally {
    chargement.value = false;
  }
}

function allerPage(n) {
  page.value = Math.max(0, Math.min(n, totalPages.value - 1));
  charger();
}

function initialiserModifData() {
  for (const u of utilisateurs.value) {
    if (!modifData[u.id]) {
      modifData[u.id] = {
        nom: u.displayUsername ?? "",
        email: u.email ?? "",
        role: u.role ?? "user",
      };
    }
  }
}

async function creerUtilisateur() {
  const email = nouvelEmail.value.trim();
  if (!email) return;
  try {
    const rep = await appelApi("/api/admin/utilisateurs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email }),
    });
    const corps = await lireJson(rep);
    if (rep.ok || rep.status === 201) {
      nouvelEmail.value = "";
      inviteEmail.value = corps.email ?? "";
      lienInvitation.value = corps.lienReset ?? "";
      ouvrirDialogInvitationModal();
      // trié par date de création croissante : le nouveau compte est toujours en dernière
      // page, jamais sur la page actuellement affichée dès que le nombre d'utilisateurs
      // dépasse une page.
      await rechargerUtilisateurs();
      if (page.value < totalPages.value - 1) {
        await allerPage(totalPages.value - 1);
      }
    } else {
      afficherToast(corps.erreur || "Impossible de créer le compte.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau lors de la création du compte.", "erreur");
  }
}

function copierLien() {
  if (navigator.clipboard) {
    navigator.clipboard.writeText(lienInvitation.value);
    afficherToast("Lien copié.", "succes");
  }
}

function fermerDialogInvitation() {
  fermerDialogInvitationModal();
}

// Ouverture/fermeture par intention explicite, pas par bascule : « Modifier » n'a jamais
// vocation à fermer, ni la fermeture après sauvegarde à rouvrir. Une bascule XOR se désynchronise
// dès qu'un clic manuel et la fermeture asynchrone après sauvegarde s'entrelacent (le clic peut
// fermer une édition en cours pendant qu'une sauvegarde précédente est encore en vol, laissant le
// formulaire fermé alors que l'utilisateur vient de cliquer « Modifier »).
function ouvrirModif(userId) {
  const s = new Set(modifOuverts.value);
  s.add(userId);
  modifOuverts.value = s;
}

function fermerModif(userId) {
  const s = new Set(modifOuverts.value);
  s.delete(userId);
  modifOuverts.value = s;
}

async function sauvegarderModif(u) {
  delete modifErreurs[u.id];
  const d = modifData[u.id];
  const roleChange = d.role !== u.role;

  if (roleChange) {
    modifEnAttenteRole.value = { user: u, data: { ...d } };
    ouvrirDialogConfirmerRoleModal();
    return;
  }

  await envoyerPatch(u, d);
}

async function envoyerPatch(u, d, messageSucces = "Utilisateur modifié.") {
  const corps = {};
  if (d.nom !== (u.displayUsername ?? "")) corps.nom = d.nom;
  if (d.email !== u.email) corps.email = d.email;
  if (d.role !== u.role) corps.role = d.role;

  if (Object.keys(corps).length === 0) {
    fermerModif(u.id);
    return;
  }

  try {
    const rep = await appelApi("/api/admin/utilisateurs/" + u.id, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(corps),
    });
    const repCorps = await lireJson(rep);
    if (rep.ok) {
      fermerModif(u.id);
      await rechargerUtilisateurs();
      afficherToast(messageSucces, "succes");
    } else {
      modifErreurs[u.id] = repCorps.erreur || "Erreur.";
    }
  } catch {
    modifErreurs[u.id] = "Erreur réseau.";
  }
}

function fermerDialogConfirmerRole() {
  fermerDialogConfirmerRoleModal();
  modifEnAttenteRole.value = null;
}

// envoyer aussi nom/email (requête PATCH séparée) avant l'appel de révocation — PATCH
// /api/admin/utilisateurs/{id} délègue en interne à initierRevocationAdmin (transaction
// SERIALIZABLE + REQUIRES_NEW) quand role passe à "user" ; l'imbriquer dans la même transaction
// que la mise à jour nom/email épuise le pool de connexions (CannotCreateTransactionException,
// constaté en e2e). Deux requêtes séquentielles évitent cette imbrication.
async function confirmerChangementRole() {
  if (!modifEnAttenteRole.value) return;
  const { user: u, data: d } = modifEnAttenteRole.value;
  const retrograder = u.role === "admin" && d.role === "user";
  fermerDialogConfirmerRole();

  if (!retrograder) {
    await envoyerPatch(u, d);
    return;
  }

  try {
    const corpsNomEmail = {};
    if (d.nom !== (u.displayUsername ?? "")) corpsNomEmail.nom = d.nom;
    if (d.email !== u.email) corpsNomEmail.email = d.email;
    if (Object.keys(corpsNomEmail).length > 0) {
      const repNomEmail = await appelApi("/api/admin/utilisateurs/" + u.id, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpsNomEmail),
      });
      if (!repNomEmail.ok) {
        const corpsErreur = await lireJson(repNomEmail);
        modifErreurs[u.id] = corpsErreur.erreur || "Erreur.";
        return;
      }
    }

    const rep = await appelApi(
      "/api/admin/utilisateurs/" + u.id + "/revoquer-admin",
      { method: "POST" },
    );
    const corps = await lireJson(rep);
    if (rep.ok) {
      fermerModif(u.id);
      await rechargerUtilisateurs();
      afficherToast(
        "Révocation initiée. L'administrateur sera prévenu par email.",
        "succes",
      );
    } else {
      modifErreurs[u.id] = corps.erreur || "Erreur.";
    }
  } catch {
    modifErreurs[u.id] = "Erreur réseau.";
  }
}

async function annulerRevocation(u) {
  try {
    const rep = await appelApi(
      "/api/admin/utilisateurs/" + u.id + "/revoquer-admin",
      { method: "DELETE" },
    );
    const corps = await lireJson(rep);
    if (rep.ok) {
      await rechargerUtilisateurs();
      afficherToast("Révocation annulée.", "succes");
    } else {
      afficherToast(corps.erreur || "Erreur.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

async function rechargerUtilisateurs() {
  try {
    const repUsers = await appelApi(
      `/api/admin/utilisateurs?page=${page.value}`,
    );
    if (repUsers.ok) {
      const data = await lireJson(repUsers);
      utilisateurs.value = data.utilisateurs ?? [];
      page.value = data.page ?? 0;
      totalPages.value = data.totalPages ?? 1;
      total.value = data.total ?? 0;
      initialiserModifData();
    }
  } catch {
    afficherToast("Erreur réseau lors du rechargement.", "erreur");
  }
}

function reinitMdp(u) {
  userAReinitMdp.value = u;
  ouvrirDialogReinitMdpModal();
}

function fermerDialogReinitMdp() {
  fermerDialogReinitMdpModal();
  userAReinitMdp.value = null;
}

async function confirmerReinitMdp() {
  if (!userAReinitMdp.value) return;
  const userId = userAReinitMdp.value.id;
  fermerDialogReinitMdp();
  try {
    const rep = await appelApi(
      "/api/admin/utilisateurs/" + userId + "/reset-mdp",
      { method: "POST" },
    );
    const corps = await lireJson(rep);
    if (rep.ok) afficherToast("Email de réinitialisation envoyé.", "succes");
    else afficherToast(corps.erreur || "Erreur.", "erreur");
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

function reinitTotp(u) {
  userAReinitTotp.value = u;
  ouvrirDialogReinitTotpModal();
}

function fermerDialogReinitTotp() {
  fermerDialogReinitTotpModal();
  userAReinitTotp.value = null;
}

async function confirmerReinitTotp() {
  if (!userAReinitTotp.value) return;
  const userId = userAReinitTotp.value.id;
  fermerDialogReinitTotp();
  try {
    const rep = await appelApi(
      "/api/admin/utilisateurs/" + userId + "/reset-totp",
      { method: "POST" },
    );
    const corps = await lireJson(rep);
    if (rep.ok)
      afficherToast(
        "2FA réinitialisée. L'utilisateur pourra se reconnecter sans code.",
        "succes",
      );
    else afficherToast(corps.erreur || "Erreur.", "erreur");
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

function revoquerSessions(u) {
  userARevoquerSessions.value = u;
  ouvrirDialogRevoquerSessionsModal();
}

function fermerDialogRevoquerSessions() {
  fermerDialogRevoquerSessionsModal();
  userARevoquerSessions.value = null;
}

async function confirmerRevoquerSessions() {
  if (!userARevoquerSessions.value) return;
  const userId = userARevoquerSessions.value.id;
  fermerDialogRevoquerSessions();
  try {
    const rep = await appelApi(
      "/api/admin/utilisateurs/" + userId + "/revoquer-sessions",
      { method: "POST" },
    );
    const corps = await lireJson(rep);
    if (rep.ok) afficherToast("Sessions révoquées.", "succes");
    else afficherToast(corps.erreur || "Erreur.", "erreur");
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

function verrouillerCompte(u) {
  userAVerrouiller.value = u;
  ouvrirDialogVerrouillerCompteModal();
}

function fermerDialogVerrouillerCompte() {
  fermerDialogVerrouillerCompteModal();
  userAVerrouiller.value = null;
}

async function confirmerVerrouillerCompte() {
  if (!userAVerrouiller.value) return;
  const userId = userAVerrouiller.value.id;
  fermerDialogVerrouillerCompte();
  try {
    const rep = await appelApi(
      "/api/admin/utilisateurs/" + userId + "/verrouiller",
      {
        method: "POST",
      },
    );
    const corps = await lireJson(rep);
    if (rep.ok) {
      await rechargerUtilisateurs();
      afficherToast("Compte verrouillé.", "succes");
    } else {
      afficherToast(corps.erreur || "Erreur.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

async function deverrouillerCompte(u) {
  try {
    const rep = await appelApi(
      "/api/admin/utilisateurs/" + u.id + "/verrouiller",
      {
        method: "DELETE",
      },
    );
    const corps = await lireJson(rep);
    if (rep.ok) {
      await rechargerUtilisateurs();
      afficherToast("Compte déverrouillé.", "succes");
    } else {
      afficherToast(corps.erreur || "Erreur.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

function ouvrirDialogSupprimer(u) {
  userASupprimer.value = u;
  ouvrirDialogSupprimerModal();
}

function fermerDialogSupprimer() {
  fermerDialogSupprimerModal();
  userASupprimer.value = null;
}

async function confirmerSuppression() {
  if (!userASupprimer.value) return;
  const userId = userASupprimer.value.id;
  fermerDialogSupprimer();
  try {
    const rep = await appelApi("/api/admin/utilisateurs/" + userId, {
      method: "DELETE",
    });
    const corps = await lireJson(rep);
    if (rep.ok) {
      // recharge plutôt qu'un simple filtre local : total/totalPages doivent rester
      // cohérents avec le serveur (pagination), pas seulement la page affichée.
      await rechargerUtilisateurs();
      afficherToast("Compte supprimé.", "succes");
    } else {
      afficherToast(corps.erreur || "Erreur lors de la suppression.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau lors de la suppression.", "erreur");
  }
}

onMounted(charger);
</script>

<style scoped>
.badge-info {
  background: var(--info-bg);
  color: var(--info-text);
}

.badge-mono {
  font-family: monospace;
  background: var(--bg-muted);
  color: var(--fg-muted);
}

.badge-avertissement {
  background: var(--avertissement-bg);
  color: var(--avertissement-text);
}

.badge-danger {
  background: var(--danger-bg);
  color: var(--danger-text);
}

.user-liste {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.user-carte {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.user-carte-entete {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 0.75rem 1rem;
  gap: 1rem;
  flex-wrap: wrap;
}

@media (width >= 521px) {
  .user-carte-entete {
    flex-wrap: nowrap;
  }
}

.user-carte-info {
  min-width: 0;
  flex: 1;
}

.user-carte-badges {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.375rem;
  margin-bottom: 0.25rem;
}

.user-carte-email {
  font-size: 0.8125rem;
  color: var(--fg-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-carte-activite {
  font-size: 0.75rem;
  color: var(--fg-subtle);
  margin-top: 0.125rem;
}

.user-carte-actions {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  flex-shrink: 0;
  width: 100%;
  justify-content: flex-end;
  flex-wrap: wrap;
}

@media (width >= 521px) {
  .user-carte-actions {
    width: auto;
    justify-content: normal;
    flex-wrap: nowrap;
  }
}

.modif-ligne {
  border-top: 1px solid var(--border);
  padding: 0.75rem 1rem;
  background: var(--bg-card);
  display: none;
}

.modif-ligne.visible {
  display: block;
}

.form-modif-utilisateur {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  max-width: 28rem;
}

.form-modif-utilisateur input,
.form-modif-utilisateur select {
  width: auto;
  font-size: 0.8125rem;
  padding: 0.3125rem 0.5rem;
  border: 1px solid var(--border);
  border-radius: 0.25rem;
  background: var(--bg);
  color: var(--fg);
}

.form-modif-utilisateur input[name="nom"] {
  width: 9rem;
  max-width: 100%;
}

.form-modif-utilisateur input[name="email"] {
  width: 12rem;
  max-width: 100%;
}

.erreur-modif {
  font-size: 0.75rem;
  color: var(--danger);
}

.dialog-lien {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.dialog-lien input {
  flex: 1;
  font-family: ui-monospace, monospace;
  font-size: 0.8125rem;
}
</style>
