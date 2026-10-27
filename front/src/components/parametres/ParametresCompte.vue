<template>
  <section id="compte" class="parametres-section">
    <h2 class="lecteur-ecran">Compte</h2>

    <EtatChargement v-if="chargement" />
    <template v-else>
      <div class="param-bloc">
        <h3 class="param-libelle">Profil</h3>
        <form
          class="form-vertical form-compact"
          @submit.prevent="sauvegarderProfil"
        >
          <div class="form-groupe">
            <label for="email">Email</label>
            <input
              id="email"
              v-model="email"
              type="email"
              maxlength="254"
              autocomplete="email"
            />
            <p v-if="profil?.pendingEmail" class="form-aide">
              En attente de confirmation : {{ profil.pendingEmail }}
            </p>
          </div>
          <button type="submit" class="bouton bouton-primaire">
            Enregistrer
          </button>
        </form>
      </div>

      <div class="param-bloc">
        <h3 class="param-libelle">Identifiant de connexion</h3>
        <form
          class="form-vertical form-compact"
          @submit.prevent="sauvegarderIdentifiant"
        >
          <div class="form-groupe">
            <label for="identifiant">Identifiant</label>
            <input
              id="identifiant"
              v-model="identifiant"
              type="text"
              :minlength="IDENTIFIANT_MIN"
              :maxlength="IDENTIFIANT_MAX"
              :pattern="IDENTIFIANT_MOTIF"
              autocomplete="username"
              aria-describedby="aide-identifiant"
            />
            <p id="aide-identifiant" class="form-aide">
              {{ AIDE_IDENTIFIANT }}
            </p>
          </div>
          <button type="submit" class="bouton bouton-primaire">Modifier</button>
        </form>
      </div>

      <div class="param-bloc">
        <h3 class="param-libelle">Authentification à deux facteurs (TOTP)</h3>
        <div v-if="deuxFacteursActif">
          <p class="param-aide">TOTP activé sur votre compte.</p>
          <form
            class="form-vertical form-compact"
            @submit.prevent="desactiverTotp"
          >
            <div class="form-groupe">
              <label for="totp-desact-code">Code de l'application</label>
              <input
                id="totp-desact-code"
                v-model="totpDesactCode"
                type="text"
                class="champ-code-totp"
                inputmode="numeric"
                pattern="[0-9]{6}"
                maxlength="6"
                autocomplete="one-time-code"
                aria-describedby="aide-totp-desact"
                required
              />
              <p id="aide-totp-desact" class="form-aide">
                Pour désactiver le TOTP, saisissez le code à 6 chiffres affiché
                par votre application d'authentification.
              </p>
            </div>
            <button type="submit" class="bouton bouton-danger bouton-petit">
              Désactiver le TOTP
            </button>
          </form>
        </div>
        <div v-else>
          <p class="param-aide">
            Protégez votre compte avec une application d'authentification
            (Google Authenticator, Aegis…).
          </p>
          <RouterLink
            to="/parametres/totp"
            class="bouton bouton-secondaire bouton-petit"
          >
            Configurer le TOTP
          </RouterLink>
        </div>
      </div>

      <div class="param-bloc">
        <h3 class="param-libelle">Mot de passe</h3>
        <p
          v-if="sessionRef?.changementMotDePasseExige"
          class="alerte alerte-avertissement"
          role="alert"
        >
          Suite à un incident de sécurité, un nouveau mot de passe est exigé : à
          votre prochaine connexion, définissez-le avec « Mot de passe oublié »
          (lien envoyé par email).
        </p>
        <p class="param-aide">La session sera invalidée après le changement.</p>
        <form
          class="form-vertical form-compact"
          @submit.prevent="changerMotDePasse"
        >
          <div class="form-groupe">
            <label for="mdp-actuel">Mot de passe actuel</label>
            <input
              id="mdp-actuel"
              v-model="mdpActuel"
              type="password"
              required
              autocomplete="current-password"
            />
          </div>
          <div class="form-groupe">
            <label for="mdp-nouveau">Nouveau mot de passe</label>
            <input
              id="mdp-nouveau"
              v-model="mdpNouveau"
              type="password"
              required
              :minlength="MOT_DE_PASSE_MIN"
              autocomplete="new-password"
              aria-describedby="aide-mdp-nouveau"
            />
            <p id="aide-mdp-nouveau" class="form-aide">
              {{ AIDE_MOT_DE_PASSE }}
            </p>
            <span class="form-aide" aria-live="polite">
              {{
                mdpNouveau.length > 0
                  ? mdpNouveau.length +
                    " caractère" +
                    (mdpNouveau.length > 1 ? "s" : "")
                  : ""
              }}
            </span>
          </div>
          <button type="submit" class="bouton bouton-primaire">Changer</button>
        </form>
      </div>

      <div class="param-bloc">
        <h3 class="param-libelle">Appareils connectés</h3>
        <p class="param-aide">Sessions actives sur votre compte.</p>
        <ul class="liste-appareils">
          <li
            v-for="appareil in appareils"
            :key="appareil.appareilId"
            class="ligne-appareil"
          >
            <span class="appareil-info">
              {{ appareil.nom }}
              <span v-if="appareil.sessionCourante" class="badge badge-nouveau"
                >Session actuelle</span
              >
              <span class="param-aide"
                >Dernière activité :
                {{ formaterDateHeure(appareil.derniereActivite) }}</span
              >
            </span>
            <button
              v-if="!appareil.sessionCourante"
              type="button"
              class="bouton bouton-danger bouton-petit"
              @click="revoquerAppareil(appareil.appareilId)"
            >
              Révoquer
            </button>
          </li>
          <li v-if="appareils.length === 0" class="param-aide">
            Aucun appareil enregistré.
          </li>
        </ul>
      </div>
    </template>
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
import {
  AIDE_IDENTIFIANT,
  AIDE_MOT_DE_PASSE,
  IDENTIFIANT_MAX,
  IDENTIFIANT_MIN,
  IDENTIFIANT_MOTIF,
  MOT_DE_PASSE_MIN,
} from "../../utils/identifiant.js";
defineOptions({ name: "ParametresCompte" });

import { ref, onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";
import { useToasts } from "../../composables/useToasts.js";
import { sessionRef } from "../../composables/useSession.js";
import EtatChargement from "../EtatChargement.vue";

const router = useRouter();
const { afficherToast } = useToasts();

const chargement = ref(true);
const profil = ref(null);
const email = ref("");
const identifiant = ref("");
const deuxFacteursActif = ref(false);
const totpDesactCode = ref("");
const mdpActuel = ref("");
const mdpNouveau = ref("");
const appareils = ref([]);
const controleur = new AbortController();

function formaterDateHeure(iso) {
  return new Date(iso).toLocaleString("fr-FR", {
    dateStyle: "short",
    timeStyle: "short",
  });
}

async function chargerAppareils() {
  try {
    const rep = await appelApi("/api/user/appareils", {
      signal: controleur.signal,
    });
    if (rep.ok) appareils.value = await lireJson(rep);
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Impossible de charger la liste des appareils.", "erreur");
  }
}

async function revoquerAppareil(appareilId) {
  try {
    const rep = await appelApi(`/api/user/appareils/${appareilId}`, {
      method: "DELETE",
      signal: controleur.signal,
    });
    if (rep.ok) {
      appareils.value = appareils.value.filter(
        (a) => a.appareilId !== appareilId,
      );
      afficherToast("Appareil révoqué.", "succes");
    } else {
      afficherToast("Erreur lors de la révocation.", "erreur");
    }
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Erreur réseau lors de la révocation.", "erreur");
  }
}

async function charger() {
  try {
    const rep = await appelApi("/api/user/profile", {
      signal: controleur.signal,
    });
    if (rep.ok) {
      profil.value = await lireJson(rep);
      email.value = profil.value.email ?? "";
      identifiant.value = profil.value.identifiant ?? "";
      deuxFacteursActif.value = profil.value.twoFactorActif ?? false;
    }
  } catch (e) {
    if (e.name !== "AbortError")
      afficherToast("Erreur lors du chargement du profil.", "erreur");
  } finally {
    chargement.value = false;
  }
}

async function sauvegarderProfil() {
  const emailActuel = profil.value?.email ?? "";
  const emailChange =
    email.value && email.value.toLowerCase() !== emailActuel.toLowerCase();
  if (!emailChange) return;

  try {
    const rep = await appelApi("/api/parametres/email-change", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: email.value }),
      signal: controleur.signal,
    });
    const corps = await lireJson(rep);
    if (rep.ok) {
      afficherToast(
        "Un email de confirmation a été envoyé à " + email.value + ".",
        "avertissement",
      );
      if (profil.value) profil.value.pendingEmail = email.value;
    } else {
      afficherToast(
        corps.erreur || "Erreur lors du changement d'email.",
        "erreur",
      );
    }
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Erreur réseau lors du changement d'email.", "erreur");
  }
}

async function sauvegarderIdentifiant() {
  try {
    const rep = await appelApi("/api/user/identifiant", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ identifiant: identifiant.value }),
      signal: controleur.signal,
    });
    const corps = await lireJson(rep);
    if (rep.ok) {
      afficherToast("Identifiant modifié.", "succes");
    } else {
      afficherToast(corps.erreur || "Erreur.", "erreur");
    }
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Erreur réseau lors de la modification.", "erreur");
  }
}

async function desactiverTotp() {
  try {
    const rep = await appelApi("/api/totp/desactiver", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: totpDesactCode.value }),
      signal: controleur.signal,
    });
    const corps = await lireJson(rep);
    if (rep.ok) {
      deuxFacteursActif.value = false;
      totpDesactCode.value = "";
      afficherToast("Authentification à deux facteurs désactivée.", "succes");
    } else {
      afficherToast(corps.erreur || "Code TOTP incorrect.", "erreur");
    }
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Erreur réseau lors de la désactivation.", "erreur");
  }
}

async function changerMotDePasse() {
  try {
    const rep = await appelApi("/api/user/password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        motDePasseActuel: mdpActuel.value,
        motDePasseNouveau: mdpNouveau.value,
      }),
      signal: controleur.signal,
    });
    if (rep.ok) {
      afficherToast("Mot de passe modifié. Reconnectez-vous.", "succes");
      // Session invalidée côté serveur : l'oublier évite que la garde du routeur
      // renvoie vers l'accueil (ou vers ce formulaire) au lieu de la connexion.
      sessionRef.value = null;
      router.push("/login?reset=true");
    } else {
      const corps = await lireJson(rep);
      afficherToast(
        corps.erreur || "Erreur lors du changement de mot de passe.",
        "erreur",
      );
    }
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast(
      "Erreur réseau lors du changement de mot de passe.",
      "erreur",
    );
  }
}

onMounted(() => {
  charger();
  chargerAppareils();
});

onUnmounted(() => controleur.abort());
</script>

<style scoped>
.champ-code-totp {
  width: 9rem;
}

.liste-appareils {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--espace-4);
}

.ligne-appareil {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--espace-8);
  padding: var(--espace-4) 0;
  border-bottom: 1px solid var(--border);
}

.ligne-appareil:last-child {
  border-bottom: none;
}

.appareil-info {
  display: flex;
  flex-direction: column;
  gap: var(--espace-1);
  font-size: var(--texte-m);
}
</style>
