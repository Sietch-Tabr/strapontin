<template>
  <div>
    <div class="page-header">
      <h1>Paramètres</h1>
    </div>

    <div v-if="chargement" class="etat-chargement" aria-live="polite">Chargement…</div>

    <template v-else>
      <select
        class="param-onglets-mobile"
        :value="ongletActif"
        aria-label="Sections des paramètres"
        @change="naviguerVers($event.target.value)"
      >
        <option v-for="onglet in onglets" :key="onglet.ancre" :value="onglet.ancre">{{ onglet.label }}</option>
      </select>

      <nav class="param-onglets" aria-label="Sections des paramètres">
        <a
          v-for="onglet in onglets"
          :key="onglet.ancre"
          :href="onglet.ancre"
          class="param-onglet"
          :class="{ actif: ongletActif === onglet.ancre }"
          :aria-current="ongletActif === onglet.ancre ? 'true' : undefined"
          @click.prevent="naviguerVers(onglet.ancre)"
        >{{ onglet.label }}</a>
      </nav>

      <div class="parametres-sections">

        <!-- Préférences -->
        <section :class="['parametres-section', { actif: ongletActif === '#preferences' }]" id="preferences">
          <h2 class="sr-only">Préférences</h2>

          <div class="param-bloc">
            <h3 class="param-label">Départements de découverte</h3>
            <p class="param-aide">
              Séparés par des virgules (ex. : 75, 92, 93). Utilisés par la page Découverte.
              Pour la Corse, utilisez 2A ou 2B.
            </p>
            <form class="form-inline" @submit.prevent="sauvegarderDepartements">
              <input
                v-model="departements"
                name="departements"
                type="text"
                placeholder="75, 92, 93…"
                autocomplete="off"
              />
              <button type="submit" class="btn btn-secondary">Enregistrer</button>
            </form>
          </div>

          <div class="param-bloc">
            <h3 class="param-label" id="label-filtre-disponibles">
              Concerts disponibles uniquement
            </h3>
            <p class="param-aide">
              Masque les concerts dont la billetterie est fermée (annulés, complets…)
              sur l'accueil et Mes spectacles.
            </p>
            <form class="form-toggle">
              <label class="switch switch--succes">
                <input
                  v-model="filtreDisponibles"
                  type="checkbox"
                  aria-labelledby="label-filtre-disponibles"
                  @change="sauvegarderFiltres"
                />
                <span class="switch-curseur"></span>
              </label>
            </form>
          </div>

          <div class="param-bloc">
            <h3 class="param-label">Thème de l'interface</h3>
            <div class="theme-choix" aria-label="Choisir le thème">
              <button
                v-for="opt in optionsTheme"
                :key="opt.valeur"
                type="button"
                class="btn btn-theme"
                :class="{ actif: theme === opt.valeur }"
                :aria-pressed="theme === opt.valeur ? 'true' : 'false'"
                @click="changerTheme(opt.valeur)"
              >{{ opt.label }}</button>
            </div>
            <p class="param-aide">Le mode Automatique suit la préférence de votre appareil.</p>
          </div>
        </section>

        <!-- Notifications -->
        <section :class="['parametres-section', { actif: ongletActif === '#notifications' }]" id="notifications">
          <h2 class="sr-only">Notifications</h2>

          <div class="param-bloc">
            <h3 class="param-label">Notifications email</h3>
            <form class="form-vertical" @submit.prevent="sauvegarderNotifications">
              <div class="form-group">
                <label for="frequence">Fréquence des emails</label>
                <select
                  id="frequence"
                  v-model="frequenceNotifications"
                  :disabled="!config?.resendDisponible"
                >
                  <option value="jamais">Jamais</option>
                  <option value="quotidien">Récapitulatif quotidien (8h)</option>
                  <option value="temps_reel">À chaque nouveau concert</option>
                </select>
                <p v-if="!config?.resendDisponible" class="form-aide">
                  Les emails ne sont pas disponibles (clé Resend non configurée).
                </p>
              </div>

              <fieldset class="notif-grille">
                <legend class="form-label">Concerts à notifier</legend>
                <div class="notif-grille__entete" aria-hidden="true">
                  <span></span>
                  <span>Email</span>
                  <span>Push</span>
                </div>
                <div class="notif-grille__ligne">
                  <span class="notif-grille__libelle">Artistes suivis</span>
                  <label class="notif-grille__case">
                    <span class="sr-only">Email — artistes suivis</span>
                    <input
                      v-model="notifArtistes"
                      type="checkbox"
                      :disabled="!config?.resendDisponible"
                    />
                  </label>
                  <label class="notif-grille__case">
                    <span class="sr-only">Push — artistes suivis</span>
                    <input v-model="notifArtistesPush" type="checkbox" />
                  </label>
                </div>
                <div class="notif-grille__ligne">
                  <span class="notif-grille__libelle">Salles suivies</span>
                  <label class="notif-grille__case">
                    <span class="sr-only">Email — salles suivies</span>
                    <input
                      v-model="notifSalles"
                      type="checkbox"
                      :disabled="!config?.resendDisponible"
                    />
                  </label>
                  <label class="notif-grille__case">
                    <span class="sr-only">Push — salles suivies</span>
                    <input v-model="notifSallesPush" type="checkbox" />
                  </label>
                </div>
              </fieldset>

              <button type="submit" class="btn btn-secondary">Enregistrer</button>
            </form>
          </div>

          <!-- Push notifications -->
          <div v-if="pushSupporte" class="param-bloc param-bloc-push">
            <h3 class="param-label">Notifications push</h3>
            <div v-if="!vapidDisponible">
              <p class="param-aide">
                Les notifications push ne sont pas configurées (clé VAPID manquante dans la
                configuration admin).
              </p>
            </div>
            <div v-else-if="pushPermission === 'denied'">
              <p class="param-aide">
                Les notifications sont bloquées dans votre navigateur. Pour les activer,
                modifiez les permissions du site dans les paramètres de votre navigateur.
              </p>
            </div>
            <div v-else-if="pushAbonne">
              <p class="param-aide">
                Les notifications push sont activées sur cet appareil.
              </p>
              <button
                type="button"
                class="btn btn-secondary"
                :disabled="pushEnCours"
                @click="desabonnerPush"
              >
                Désactiver les notifications push
              </button>
            </div>
            <div v-else>
              <p class="param-aide">
                Recevez une notification sur cet appareil en même temps que vos emails de digest.
              </p>
              <p v-if="frequenceNotifications === 'jamais'" class="form-aide">
                Les notifications push ne fonctionneront qu'avec une fréquence email active.
              </p>
              <button
                type="button"
                class="btn btn-secondary"
                :disabled="pushEnCours || frequenceNotifications === 'jamais'"
                @click="abonnerPush"
              >
                Activer les notifications push
              </button>
            </div>
          </div>
        </section>

        <!-- Compte -->
        <section :class="['parametres-section', { actif: ongletActif === '#compte' }]" id="compte">
          <h2 class="sr-only">Compte</h2>

          <div class="param-bloc">
            <h3 class="param-label">Profil</h3>
            <form class="form-vertical form-compact" @submit.prevent="sauvegarderProfil">
              <div class="form-group">
                <label for="nom">Nom d'affichage</label>
                <input
                  id="nom"
                  v-model="nom"
                  type="text"
                  maxlength="100"
                  autocomplete="name"
                />
              </div>
              <div class="form-group">
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
              <button type="submit" class="btn btn-secondary">Enregistrer</button>
            </form>
          </div>

          <div class="param-bloc">
            <h3 class="param-label" id="label-identifiant">Identifiant de connexion</h3>
            <form class="form-inline" @submit.prevent="sauvegarderIdentifiant">
              <label for="identifiant" class="sr-only">Identifiant de connexion</label>
              <input
                id="identifiant"
                v-model="identifiant"
                type="text"
                minlength="3"
                maxlength="30"
                pattern="[a-zA-Z0-9_]+"
                autocomplete="username"
              />
              <button type="submit" class="btn btn-secondary">Modifier</button>
            </form>
          </div>

          <div class="param-bloc">
            <h3 class="param-label">Authentification à deux facteurs (TOTP)</h3>
            <div v-if="twoFactorActif">
              <p class="param-aide">TOTP activé sur votre compte.</p>
              <form class="form-vertical form-compact" @submit.prevent="desactiverTotp">
                <div class="form-group">
                  <label for="totp-desact-code">Code de confirmation</label>
                  <input
                    id="totp-desact-code"
                    v-model="totpDesactCode"
                    type="text"
                    inputmode="numeric"
                    pattern="[0-9]{6}"
                    maxlength="6"
                    autocomplete="one-time-code"
                    placeholder="123456"
                    required
                  />
                </div>
                <button type="submit" class="btn btn-danger btn-sm">Désactiver le TOTP</button>
              </form>
            </div>
            <div v-else>
              <p class="param-aide">
                Protégez votre compte avec une application d'authentification
                (Google Authenticator, Aegis…).
              </p>
              <RouterLink to="/parametres/totp" class="btn btn-secondary btn-sm">
                Configurer le TOTP
              </RouterLink>
            </div>
          </div>

          <div class="param-bloc">
            <h3 class="param-label">Mot de passe</h3>
            <p class="param-aide">La session sera invalidée après le changement.</p>
            <form class="form-vertical form-compact" @submit.prevent="changerMotDePasse">
              <div class="form-group">
                <label for="mdp-actuel">Mot de passe actuel</label>
                <input
                  id="mdp-actuel"
                  v-model="mdpActuel"
                  type="password"
                  required
                  autocomplete="current-password"
                />
              </div>
              <div class="form-group">
                <label for="mdp-nouveau">Nouveau mot de passe</label>
                <input
                  id="mdp-nouveau"
                  v-model="mdpNouveau"
                  type="password"
                  required
                  minlength="12"
                  autocomplete="new-password"
                  placeholder="12 caractères minimum"
                />
                <span class="form-aide" aria-live="polite">
                  {{ mdpNouveau.length > 0 ? mdpNouveau.length + ' caractère' + (mdpNouveau.length > 1 ? 's' : '') : '' }}
                </span>
              </div>
              <button type="submit" class="btn btn-secondary">Changer</button>
            </form>
          </div>
        </section>

        <!-- Données -->
        <section :class="['parametres-section', { actif: ongletActif === '#donnees' }]" id="donnees">
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

      </div>
    </template>

    <!-- Dialog suppression de compte -->
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
      <div class="dialog-actions">
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
  </div>
</template>

<script setup>
defineOptions({ name: 'ParametresPage' })

import { ref, computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useDialog } from "../composables/useDialog.js";
import { useToasts } from "../composables/useToasts.js";
import { useTheme } from "../composables/useTheme.js";

const route = useRoute();
const router = useRouter();
const { afficherToast } = useToasts();
const { enregistrerTheme, lireThemeCookie } = useTheme();

const chargement = ref(true);
const config = ref(null);
const profil = ref(null);
const stats = ref(null);

// Onglets
const onglets = [
  { ancre: "#preferences", label: "Préférences" },
  { ancre: "#notifications", label: "Notifications" },
  { ancre: "#compte", label: "Compte" },
  { ancre: "#donnees", label: "Données" },
];
const ongletActif = ref("#preferences");

function naviguerVers(ancre) {
  ongletActif.value = ancre;
  router.replace({ hash: ancre });
}

// Préférences
const departements = ref("");
const filtreDisponibles = ref(false);
const theme = ref("auto");
const optionsTheme = [
  { valeur: "auto", label: "Automatique" },
  { valeur: "clair", label: "Clair" },
  { valeur: "sombre", label: "Sombre" },
];

// Notifications
const frequenceNotifications = ref("jamais");
const notifArtistes = ref(false);
const notifSalles = ref(false);
const notifArtistesPush = ref(false);
const notifSallesPush = ref(false);

// Push
const pushSupporte = ref(false);
const vapidDisponible = ref(false);
const pushPermission = ref("default");
const pushAbonne = ref(false);
const pushEnCours = ref(false);
let swRegistration = null;
let cleVapidPublique = null;

// Compte
const nom = ref("");
const email = ref("");
const identifiant = ref("");
const twoFactorActif = ref(false);
const totpDesactCode = ref("");
const mdpActuel = ref("");
const mdpNouveau = ref("");

// Données
const importFichierRef = ref(null);
const importMode = ref("fusionner");
const importerConfig = ref(false);

// Dialog suppression
const { dialogRef: dialogSuppressionRef, ouvrir: ouvrirDialogSuppressionModal, fermer: fermerDialogSuppressionModal } = useDialog();
const confirmationSuppression = ref("");

// --- Chargement initial ---

async function charger() {
  chargement.value = true;
  try {
    const [repConfig, repProfil, repStats] = await Promise.all([
      fetch("/api/user/config"),
      fetch("/api/user/profile"),
      fetch("/api/parametres/stats"),
    ]);

    if (repConfig.ok) {
      config.value = await repConfig.json();
      departements.value = config.value.departements?.join(", ") ?? "";
      filtreDisponibles.value = config.value.filtreDisponibles ?? false;
      frequenceNotifications.value = config.value.frequenceNotifications ?? "jamais";
      notifArtistes.value = config.value.notifArtistes ?? false;
      notifSalles.value = config.value.notifSalles ?? false;
      notifArtistesPush.value = config.value.notifArtistesPush ?? false;
      notifSallesPush.value = config.value.notifSallesPush ?? false;
    }

    if (repProfil.ok) {
      profil.value = await repProfil.json();
      nom.value = profil.value.nom ?? "";
      email.value = profil.value.email ?? "";
      identifiant.value = profil.value.identifiant ?? "";
      twoFactorActif.value = profil.value.twoFactorActif ?? false;
    }

    if (repStats.ok) {
      stats.value = await repStats.json();
    }

    theme.value = lireThemeCookie() || "auto";

    await initialiserPush();
  } catch (_) {
    afficherToast("Erreur lors du chargement des paramètres.", "erreur");
  } finally {
    chargement.value = false;
  }
}

// --- Gestion des succès depuis l'URL ---

function traiterSuccesUrl() {
  const succes = route.query.succes;
  const erreur = route.query.erreur;
  if (succes === "email-confirme") {
    afficherToast("Adresse email mise à jour.", "succes");
    ongletActif.value = "#compte";
  } else if (erreur === "lien-expire") {
    afficherToast("Lien expiré ou invalide. Recommencez depuis Paramètres.", "erreur");
    ongletActif.value = "#compte";
  } else if (erreur === "lien-invalide") {
    afficherToast("Lien invalide.", "erreur");
    ongletActif.value = "#compte";
  } else if (erreur === "email-deja-utilise") {
    afficherToast("Cette adresse email est désormais utilisée par un autre compte.", "erreur");
    ongletActif.value = "#compte";
  }
  if (succes || erreur) {
    router.replace({ query: {}, hash: ongletActif.value });
  }
}

onMounted(async () => {
  const hash = route.hash || "#preferences";
  ongletActif.value = onglets.some((o) => o.ancre === hash) ? hash : "#preferences";

  traiterSuccesUrl();
  await charger();
});

// --- Préférences ---

async function sauvegarderDepartements() {
  const rep = await fetch("/api/user/config/departements", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ departements: departements.value }),
  });
  const corps = await rep.json();
  if (rep.ok) {
    if (corps.ignores?.length) {
      afficherToast("Codes ignorés car non reconnus : " + corps.ignores.join(", "), "avertissement");
    } else {
      afficherToast("Départements enregistrés.", "succes");
    }
  } else {
    afficherToast(corps.erreur || "Erreur.", "erreur");
  }
}

async function sauvegarderFiltres() {
  await fetch("/api/user/config/filtres", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ filtreDisponibles: filtreDisponibles.value }),
  });
}

async function changerTheme(valeur) {
  theme.value = valeur;
  enregistrerTheme(valeur);
  await fetch("/api/user/config/theme", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ valeur }),
  });
}

// --- Notifications ---

async function sauvegarderNotifications() {
  const rep = await fetch("/api/user/config/notifications", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      frequenceNotifications: frequenceNotifications.value,
      notifArtistes: notifArtistes.value,
      notifSalles: notifSalles.value,
      notifArtistesPush: notifArtistesPush.value,
      notifSallesPush: notifSallesPush.value,
    }),
  });
  if (rep.ok) {
    afficherToast("Préférences de notification enregistrées.", "succes");
  } else {
    const corps = await rep.json();
    afficherToast(corps.erreur || "Erreur.", "erreur");
  }
}

// --- Push ---

async function initialiserPush() {
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) {
    pushSupporte.value = false;
    return;
  }
  pushSupporte.value = true;
  pushPermission.value = Notification.permission;

  try {
    const rep = await fetch("/api/push");
    if (rep.ok) {
      const vapid = await rep.json();
      vapidDisponible.value = vapid.disponible ?? false;
      cleVapidPublique = vapid.clePublique ?? null;
    }
  } catch (_) {
    vapidDisponible.value = false;
  }

  if (vapidDisponible.value && pushPermission.value !== "denied") {
    try {
      swRegistration = await navigator.serviceWorker.ready;
      const abonnement = await swRegistration.pushManager.getSubscription();
      pushAbonne.value = abonnement !== null;
    } catch (_) {
      pushAbonne.value = false;
    }
  }
}

function urlBase64VersUint8Array(base64) {
  const pad = "=".repeat((4 - (base64.length % 4)) % 4);
  const b64 = (base64 + pad).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(b64);
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
}

async function abonnerPush() {
  if (!swRegistration || !cleVapidPublique) return;
  pushEnCours.value = true;
  try {
    const permission = await Notification.requestPermission();
    pushPermission.value = permission;
    if (permission !== "granted") return;

    const abonnement = await swRegistration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64VersUint8Array(cleVapidPublique),
    });

    const rep = await fetch("/api/push", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        endpoint: abonnement.endpoint,
        keys: {
          p256dh: btoa(String.fromCharCode(...new Uint8Array(abonnement.getKey("p256dh")))),
          auth: btoa(String.fromCharCode(...new Uint8Array(abonnement.getKey("auth")))),
        },
      }),
    });

    if (rep.ok) {
      pushAbonne.value = true;
      document.cookie = "push_opt_in=1; path=/; max-age=" + 365 * 24 * 3600 + "; SameSite=Strict";
      afficherToast("Notifications push activées.", "succes");
    } else {
      await abonnement.unsubscribe();
      const corps = await rep.json();
      afficherToast(corps.erreur || "Erreur lors de l'abonnement.", "erreur");
    }
  } catch (_) {
    afficherToast("Erreur lors de l'abonnement push.", "erreur");
  } finally {
    pushEnCours.value = false;
  }
}

async function desabonnerPush() {
  if (!swRegistration) return;
  pushEnCours.value = true;
  try {
    const abonnement = await swRegistration.pushManager.getSubscription();
    if (abonnement) {
      await fetch("/api/push", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ endpoint: abonnement.endpoint }),
      });
      await abonnement.unsubscribe();
    }
    pushAbonne.value = false;
    document.cookie = "push_opt_in=; path=/; max-age=0; SameSite=Strict";
    afficherToast("Notifications push désactivées.", "succes");
  } catch (_) {
    afficherToast("Erreur lors du désabonnement push.", "erreur");
  } finally {
    pushEnCours.value = false;
  }
}

// --- Compte ---

async function sauvegarderProfil() {
  const emailActuel = profil.value?.email ?? "";
  const emailChange = email.value && email.value.toLowerCase() !== emailActuel.toLowerCase();
  const nomChange = nom.value.trim() !== (profil.value?.nom ?? "");

  if (nomChange) {
    const rep = await fetch("/api/user/profile", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ nom: nom.value.trim() }),
    });
    if (!rep.ok) {
      const corps = await rep.json();
      afficherToast(corps.erreur || "Erreur lors de la mise à jour du profil.", "erreur");
      return;
    }
    if (profil.value) profil.value.nom = nom.value.trim();
  }

  if (emailChange) {
    const rep = await fetch("/api/parametres/email-change", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: email.value }),
    });
    const corps = await rep.json();
    if (rep.ok) {
      afficherToast(
        "Un email de confirmation a été envoyé à " + email.value + ".",
        "avertissement"
      );
      if (profil.value) profil.value.pendingEmail = email.value;
    } else {
      afficherToast(corps.erreur || "Erreur lors du changement d'email.", "erreur");
    }
    return;
  }

  if (nomChange) {
    afficherToast("Profil enregistré.", "succes");
  }
}

async function sauvegarderIdentifiant() {
  const rep = await fetch("/api/user/identifiant", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ identifiant: identifiant.value }),
  });
  const corps = await rep.json();
  if (rep.ok) {
    afficherToast("Identifiant modifié.", "succes");
  } else {
    afficherToast(corps.erreur || "Erreur.", "erreur");
  }
}

async function desactiverTotp() {
  const rep = await fetch("/api/totp/desactiver", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code: totpDesactCode.value }),
  });
  const corps = await rep.json();
  if (rep.ok) {
    twoFactorActif.value = false;
    totpDesactCode.value = "";
    afficherToast("Authentification à deux facteurs désactivée.", "succes");
  } else {
    afficherToast(corps.erreur || "Code TOTP incorrect.", "erreur");
  }
}

async function changerMotDePasse() {
  const rep = await fetch("/api/user/password", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      motDePasseActuel: mdpActuel.value,
      motDePasseNouveau: mdpNouveau.value,
    }),
  });
  if (rep.ok) {
    router.push("/login?reset=true");
  } else {
    const corps = await rep.json();
    afficherToast(corps.erreur || "Erreur lors du changement de mot de passe.", "erreur");
  }
}

// --- Données ---

async function importerPreferences() {
  const fichierEl = importFichierRef.value;
  if (!fichierEl?.files?.length) return;

  const formData = new FormData();
  formData.append("fichier", fichierEl.files[0]);
  formData.append("mode", importMode.value);
  formData.append("importerConfig", importerConfig.value ? "true" : "false");

  const rep = await fetch("/api/preferences/importer", {
    method: "POST",
    body: formData,
  });
  const corps = await rep.json();
  if (rep.ok) {
    const a = corps.artistes ?? 0;
    const s = corps.salles ?? 0;
    const r = corps.rejetes ?? 0;
    const msg =
      a + " artiste" + (a > 1 ? "s" : "") +
      " et " + s + " salle" + (s > 1 ? "s" : "") +
      " importés" +
      (r > 0 ? " (" + r + " rejeté" + (r > 1 ? "s" : "") + ")" : "") + ".";
    afficherToast(msg, "succes");
    const repStats = await fetch("/api/parametres/stats");
    if (repStats.ok) stats.value = await repStats.json();
    fichierEl.value = "";
  } else {
    afficherToast(corps.erreur || "Erreur lors de l'import.", "erreur");
  }
}

function ouvrirDialogSuppression() {
  confirmationSuppression.value = "";
  ouvrirDialogSuppressionModal();
}

function fermerDialogSuppression() {
  fermerDialogSuppressionModal();
}

async function supprimerCompte() {
  if (confirmationSuppression.value !== "supprimer") return;
  const rep = await fetch("/api/user", { method: "DELETE" });
  if (rep.ok || rep.status === 204) {
    router.push("/login");
  } else {
    afficherToast("Erreur lors de la suppression du compte.", "erreur");
  }
}
</script>
