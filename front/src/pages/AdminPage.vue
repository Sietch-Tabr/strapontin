<template>
  <div>
    <div class="page-header">
      <h1>Administration</h1>
    </div>

    <div v-if="chargement" class="etat-chargement" aria-live="polite">Chargement…</div>

    <template v-else>
      <nav class="param-onglets" aria-label="Sections de l'administration">
        <a
          v-for="onglet in onglets"
          :key="onglet.ancre"
          :href="onglet.ancre"
          class="param-onglet"
          @click.prevent="naviguerVers(onglet.ancre)"
        >{{ onglet.label }}</a>
      </nav>

      <div class="admin-sections">

        <!-- Section Tableau de bord -->
        <section :class="['admin-section', { actif: ongletActif === '#jobs' }]" id="jobs">

          <!-- Quota TM -->
          <div class="param-bloc">
            <h3 class="param-label">Quota Ticketmaster API</h3>
            <div v-if="quotaTM" class="quota-tm-carte">
              <div class="quota-tm-chiffres">
                <span>
                  <strong>{{ quotaTM.dispo }}</strong>
                  <span class="quota-tm-sep">{{ ' / ' + quotaTM.total }}</span>
                  <span class="quota-tm-unite">appels restants</span>
                </span>
                <span class="quota-tm-pct">{{ pctQuota }}%</span>
              </div>
              <div
                class="quota-tm-barre-fond"
                role="progressbar"
                aria-valuemin="0"
                :aria-valuenow="quotaTM.dispo"
                :aria-valuemax="quotaTM.total"
                aria-label="Quota Ticketmaster"
              >
                <div
                  class="quota-tm-barre-remplissage"
                  :class="classeBarre"
                  :style="{ width: pctQuota + '%' }"
                ></div>
              </div>
              <p
                v-if="etatQuota !== 'ok'"
                class="badge-quota"
                :class="etatQuota === 'critique' ? 'badge-quota-critique' : 'badge-quota-attention'"
              >
                {{ etatQuota === 'critique' ? 'Quota critique (moins de 10 %)' : 'Quota faible (moins de 20 %)' }}
              </p>
              <p v-if="quotaTM.reset > 0" class="param-aide">
                Remise à zéro le {{ formaterTs(quotaTM.reset) }}
              </p>
              <p v-if="quotaTM.misAJour" class="param-aide">
                Actualisé le {{ formaterInstant(quotaTM.misAJour) }}
              </p>
            </div>
            <p v-else class="etat-vide-texte">Disponible après le prochain fetch Ticketmaster.</p>
          </div>

          <!-- Actualisations -->
          <div class="param-bloc">
            <h3 class="param-label">Actualisations</h3>
            <div v-if="statsJobs.length" class="jobs-resume">
              <span v-if="nbJobsOk > 0" class="jobs-resume-ok">
                <strong>{{ nbJobsOk }}</strong> OK
              </span>
              <span v-if="nbJobsErreur > 0" class="jobs-resume-erreur">
                <strong>{{ nbJobsErreur }}</strong> en erreur
              </span>
            </div>
            <div v-if="statsJobs.length" class="jobs-liste">
              <div v-for="stat in statsJobs" :key="stat.typeJob" class="job-carte">
                <div class="job-carte-entete">
                  <p class="job-carte-label">{{ LABELS_JOBS[stat.typeJob] ?? stat.typeJob }}</p>
                  <span
                    v-if="jobsEnCours.includes(stat.typeJob)"
                    class="job-en-cours"
                    aria-busy="true"
                    aria-label="En cours"
                  >
                    <svg class="spinner" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                      <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                    </svg>
                    En cours
                  </span>
                </div>
                <p class="job-carte-detail">
                  <span v-if="stat.derniereExec">{{ formaterInstant(stat.derniereExec) }}</span>
                  <span v-if="stat.derniereExec && stat.dureeMs != null">{{ ' · ' + formaterDuree(stat.dureeMs) }}</span>
                  <span v-if="stat.nbInseres != null && stat.nbInseres > 0">{{ ' · +' + stat.nbInseres + ' insérés' }}</span>
                  <span v-if="stat.nbMisAJour != null && stat.nbMisAJour > 0">{{ ' · ' + stat.nbMisAJour + ' màj' }}</span>
                  <span v-if="!stat.derniereExec">Pas encore exécuté</span>
                  <span v-if="stat.erreur" class="badge badge-erreur">{{ stat.erreur.length > 80 ? stat.erreur.substring(0, 80) + '…' : stat.erreur }}</span>
                </p>
                <p v-if="stat.prochaineExec" class="job-carte-detail">
                  Prochaine exécution : {{ formaterInstant(stat.prochaineExec) }}
                </p>
              </div>
            </div>
            <p v-else class="etat-vide-texte">Aucun job exécuté pour l'instant.</p>
            <div class="relancer-actions">
              <button
                type="button"
                id="btn-relancer"
                class="btn btn-secondary"
                :disabled="relancerEnCours"
                @click="relancerFetches"
              >
                Relancer les fetches maintenant
              </button>
              <span v-if="relancerStatut" id="relancer-statut" class="relancer-statut" aria-live="polite">{{ relancerStatut }}</span>
            </div>
          </div>

          <!-- Worker -->
          <div class="param-bloc">
            <h3 class="param-label">Worker</h3>
            <div class="worker-carte" :class="{ 'worker-alerte': workerEnAlerte }">
              <div v-if="workerDepuisTexte">
                <p class="worker-carte-label">Dernier job complété</p>
                <p class="worker-carte-detail">{{ workerDepuisTexte }}</p>
                <p v-if="workerEnAlerte" class="worker-alerte-texte" role="alert">Inactif depuis plus de 3 h</p>
              </div>
              <div v-else>
                <p class="worker-carte-detail">Aucun job complété — worker non démarré.</p>
              </div>
            </div>
          </div>
        </section>

        <!-- Section Logs -->
        <section :class="['admin-section', { actif: ongletActif === '#logs' }]" id="logs">
          <div class="param-bloc">
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
              <button type="button" class="btn btn-secondaire logs-btn-action" @click="reinitialiserFiltresLogs">Réinitialiser</button>
              <button type="button" class="btn btn-secondaire logs-btn-action" @click="rafraichirLogs">Rafraîchir</button>
            </div>
            <p v-if="!filtresNiveaux.has('info')" class="logs-note-info">
              Les logs de niveau info sont masqués par défaut. Activez-les via le filtre ci-dessus.
            </p>
            <div v-if="logs.length" class="logs-conteneur">
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
                    <template v-for="entree in logs" :key="entree.ts + entree.source + entree.message">
                      <tr
                        v-show="logVisible(entree)"
                        :class="entree.niveau === 'error' ? 'log-error' : entree.niveau === 'warn' ? 'log-warn' : ''"
                      >
                        <td class="log-ts" data-label="Horodatage">{{ formaterTs(entree.ts) }}</td>
                        <td data-label="Niveau">{{ entree.niveau }}</td>
                        <td data-label="Source">{{ entree.source }}</td>
                        <td class="log-message" data-label="Message">
                          {{ entree.message }}
                          <div v-if="parseMeta(entree.meta)" class="log-meta">
                            <span
                              v-for="(val, cle) in parseMeta(entree.meta)"
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

        <!-- Section Configuration -->
        <section :class="['admin-section', { actif: ongletActif === '#configuration' }]" id="configuration">

          <!-- APP_CONFIG_SECRET -->
          <div class="param-bloc">
            <div class="param-label-ligne">
              <h3 class="param-label">APP_CONFIG_SECRET</h3>
              <span v-if="appConfigSecretPresente" class="badge badge-succes" role="status">Clé configurée</span>
              <span v-else class="badge badge-manquant" role="status">Clé absente</span>
            </div>
            <p class="param-aide">Variable d'environnement de chiffrement de la configuration. Changer cette clé rend toutes les valeurs chiffrées illisibles.</p>
          </div>

          <!-- Ticketmaster -->
          <div class="param-bloc">
            <h3 class="param-label">Ticketmaster</h3>
            <template v-if="configParCle['TICKETMASTER_API_KEY']">
              <div class="param-label-ligne">
                <span class="param-label">TICKETMASTER_API_KEY</span>
                <span v-if="configParCle['TICKETMASTER_API_KEY'].presente" class="badge badge-succes">Configurée</span>
                <span v-else class="badge badge-manquant" role="status">Non configurée</span>
              </div>
              <p class="param-aide">Clé d'API Ticketmaster Discovery v2.</p>
              <form class="form-inline" @submit.prevent="sauvegarderConfig('TICKETMASTER_API_KEY', $event)">
                <input type="password" name="valeur" autocomplete="new-password" placeholder="Nouvelle valeur…" aria-label="Valeur de TICKETMASTER_API_KEY" />
                <button type="submit" class="btn btn-secondary">Enregistrer</button>
              </form>
            </template>
          </div>

          <!-- Email Resend -->
          <div class="param-bloc">
            <h3 class="param-label">Email (Resend)</h3>
            <template v-if="configParCle['RESEND_API_KEY']">
              <div class="param-label-ligne">
                <span class="param-label">RESEND_API_KEY</span>
                <span v-if="configParCle['RESEND_API_KEY'].presente" class="badge badge-succes">Configurée</span>
                <span v-else class="badge badge-manquant" role="status">Non configurée</span>
              </div>
              <p class="param-aide">Clé d'API Resend pour les notifications email.</p>
              <form class="form-inline" @submit.prevent="sauvegarderConfig('RESEND_API_KEY', $event)">
                <input type="password" name="valeur" autocomplete="new-password" placeholder="Nouvelle valeur…" aria-label="Valeur de RESEND_API_KEY" />
                <button type="submit" class="btn btn-secondary">Enregistrer</button>
              </form>
            </template>
            <template v-if="configParCle['RESEND_WEBHOOK_SECRET']">
              <div class="config-groupe-item">
                <div class="param-label-ligne">
                  <span class="param-label">RESEND_WEBHOOK_SECRET</span>
                  <span v-if="configParCle['RESEND_WEBHOOK_SECRET'].presente" class="badge badge-succes">Configurée</span>
                  <span v-else class="badge badge-manquant" role="status">Non configurée</span>
                </div>
                <p class="param-aide">{{ descriptionWebhookSecret }}</p>
                <form class="form-inline" @submit.prevent="sauvegarderConfig('RESEND_WEBHOOK_SECRET', $event)">
                  <input type="password" name="valeur" autocomplete="new-password" placeholder="Nouvelle valeur…" aria-label="Valeur de RESEND_WEBHOOK_SECRET" />
                  <button type="submit" class="btn btn-secondary">Enregistrer</button>
                </form>
              </div>
            </template>
          </div>

          <!-- Général -->
          <div class="param-bloc">
            <h3 class="param-label">Général</h3>
            <template v-for="cle in ['CONTACT_EMAIL', 'PUBLIC_BASE_URL']" :key="cle">
              <div class="config-groupe-item">
                <div class="param-label-ligne">
                  <span class="param-label">{{ cle }}</span>
                  <span v-if="configParCle[cle]?.presente" class="badge badge-succes">Configurée</span>
                  <span v-else class="badge badge-manquant" role="status">Non configurée</span>
                </div>
                <p class="param-aide">{{ DESCRIPTIONS_CONFIG[cle] }}</p>
                <p v-if="configParCle[cle]?.valeur" class="param-aide">{{ configParCle[cle].valeur }}</p>
                <form class="form-inline" @submit.prevent="sauvegarderConfig(cle, $event)">
                  <input
                    type="text"
                    name="valeur"
                    :value="configParCle[cle]?.valeur ?? ''"
                    autocomplete="off"
                    :aria-label="'Valeur de ' + cle"
                  />
                  <button type="submit" class="btn btn-secondary">Enregistrer</button>
                </form>
              </div>
            </template>
          </div>

          <!-- VAPID -->
          <div class="param-bloc param-bloc-danger">
            <h3 class="param-label">Notifications push (VAPID)</h3>
            <details class="config-vapid-details">
              <summary>Qu'est-ce que les clés VAPID ?</summary>
              <p class="param-aide">
                Les clés VAPID permettent d'authentifier le serveur auprès des services de
                notification push. Régénérer les clés invalide tous les abonnements existants.
                Les utilisateurs devront se réabonner.
              </p>
            </details>
            <template v-for="cle in ['VAPID_PUBLIC_KEY', 'VAPID_PRIVATE_KEY']" :key="cle">
              <div class="config-groupe-item">
                <div class="param-label-ligne">
                  <span class="param-label">{{ cle }}</span>
                  <span v-if="configParCle[cle]?.presente" class="badge badge-succes">Configurée</span>
                  <span v-else class="badge badge-manquant" role="status">Non configurée</span>
                </div>
              </div>
            </template>
            <div class="config-groupe-item">
              <p class="param-aide config-vapid-avertissement">Régénérer les clés invalide tous les abonnements push existants.</p>
              <button type="button" class="btn btn-secondary" @click="ouvrirDialogVapid">
                Régénérer les clés VAPID (invalide tous les abonnements push)
              </button>
            </div>
          </div>
        </section>

        <!-- Section Bannière -->
        <section :class="['admin-section', { actif: ongletActif === '#banniere' }]" id="banniere">
          <div class="param-bloc">
            <h3 class="param-label">Message système</h3>
            <p class="param-aide">
              Publiez un message visible par tous les utilisateurs connectés dans la minute.
              Le message disparaît automatiquement selon son type ou manuellement sur fermeture.
            </p>
            <div class="form-champ">
              <label for="banniere-message" class="form-label">Message</label>
              <textarea
                id="banniere-message"
                v-model="banniereMessage"
                name="message"
                class="form-control"
                rows="2"
                maxlength="500"
                placeholder="Maintenance planifiée ce soir à 22h…"
              ></textarea>
            </div>
            <div class="form-champ">
              <label for="banniere-type" class="form-label">Type</label>
              <select id="banniere-type" v-model="banniereType" name="type" class="form-control">
                <option value="info">Information (auto-dismiss 10 s)</option>
                <option value="warning">Avertissement (auto-dismiss 20 s)</option>
                <option value="error">Erreur (fermeture manuelle)</option>
              </select>
            </div>
            <div class="form-actions">
              <button type="button" class="btn btn-primary" @click="publierBanniere">Publier</button>
              <button type="button" class="btn btn-secondaire" @click="effacerBanniere">Effacer</button>
            </div>
          </div>
        </section>

        <!-- Section Utilisateurs -->
        <section :class="['admin-section', { actif: ongletActif === '#utilisateurs' }]" id="utilisateurs">

          <!-- Créer un compte -->
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

          <!-- Liste -->
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
                    <button
                      type="button"
                      class="btn btn-sm btn-secondary"
                      @click="toggleModif(u.id)"
                    >Modifier</button>
                    <button
                      type="button"
                      class="btn btn-sm btn-secondary"
                      @click="reinitMdp(u)"
                    >Réinit. MDP</button>
                    <button
                      type="button"
                      class="btn btn-sm btn-danger"
                      @click="ouvrirDialogSupprimer(u)"
                    >Supprimer</button>
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

      </div>
    </template>

    <!-- Dialog confirmation VAPID -->
    <dialog ref="dialogVapidRef" id="dialog-vapid">
      <p class="dialog-titre">Régénérer les clés VAPID ?</p>
      <p class="dialog-corps">
        Tous les abonnements push existants seront invalidés. Les utilisateurs devront se réabonner aux notifications.
      </p>
      <div class="dialog-pied">
        <button type="button" id="dialog-vapid-confirmer" class="btn btn-secondary" @click="confirmerVapid">Régénérer</button>
        <button type="button" id="dialog-vapid-annuler" class="btn btn-secondaire" @click="fermerDialogVapid">Annuler</button>
      </div>
    </dialog>

    <!-- Dialog confirmation suppression utilisateur -->
    <dialog ref="dialogSupprimerUserRef" id="dialog-supprimer-user">
      <p class="dialog-titre">Supprimer ce compte ?</p>
      <p id="dialog-supprimer-user-nom" class="dialog-corps">{{ userASupprimer?.name }}</p>
      <div class="dialog-pied">
        <button type="button" id="dialog-supprimer-user-confirmer" class="btn btn-danger" @click="confirmerSuppression">Supprimer</button>
        <button type="button" id="dialog-supprimer-user-annuler" class="btn btn-secondaire" @click="fermerDialogSupprimer">Annuler</button>
      </div>
    </dialog>

    <!-- Dialog invitation -->
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
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, reactive } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useToasts } from "../composables/useToasts.js";
import { formaterInstant, formaterTs } from "../composables/useDates.js";

const route = useRoute();
const router = useRouter();
const { afficherToast } = useToasts();

const chargement = ref(true);

const onglets = [
  { ancre: "#jobs",          label: "Tableau de bord" },
  { ancre: "#logs",          label: "Logs" },
  { ancre: "#configuration", label: "Configuration" },
  { ancre: "#banniere",      label: "Bannière" },
  { ancre: "#utilisateurs",  label: "Utilisateurs" },
];
const ongletActif = ref("#jobs");

function naviguerVers(ancre) {
  ongletActif.value = ancre;
  router.replace({ hash: ancre });
}

const LABELS_JOBS = {
  "fetch:artistes:batch": "Artistes suivis",
  "fetch:region":         "Concerts par région",
  "notifier:digest":      "Notifications digest",
  "purger:concerts":      "Purge concerts",
  "recalculer:matches":   "Recalcul matchs",
  "fetch:salle":          "Salle",
};

const DESCRIPTIONS_CONFIG = {
  CONTACT_EMAIL:  "Adresse email affichée dans les emails envoyés.",
  PUBLIC_BASE_URL: "URL publique de l'application (ex. https://strapontin.kwaite.fr).",
};

// --- État ---

const statsJobs = ref([]);
const quotaTM = ref(null);
const jobsEnCours = ref([]);
const workerLastSuccess = ref(null);
const etatQuota = ref("ok");
const relancerEnCours = ref(false);
const relancerStatut = ref("");
let pollingInterval = null;

const logs = ref([]);
const filtreSource = ref("");
const filtresNiveaux = ref(new Set(["warn", "error"]));

const configs = ref([]);
const appConfigSecretPresente = ref(false);

const banniereMessage = ref("");
const banniereType = ref("info");

const utilisateurs = ref([]);
const utilisateurCourantId = ref(null);
const nouvelEmail = ref("");
const modifOuverts = ref(new Set());
const modifData = reactive({});
const modifErreurs = reactive({});
const userASupprimer = ref(null);
const lienInvitation = ref("");

const dialogVapidRef = ref(null);
const dialogSupprimerUserRef = ref(null);
const dialogInvitationRef = ref(null);

// --- Computed ---

const pctQuota = computed(() => {
  if (!quotaTM.value || quotaTM.value.total === 0) return 0;
  return Math.round((quotaTM.value.dispo * 100) / quotaTM.value.total);
});

const classeBarre = computed(() => {
  const p = pctQuota.value;
  if (p > 30) return "barre-ok";
  if (p > 10) return "barre-warn";
  return "barre-danger";
});

const nbJobsOk = computed(() => statsJobs.value.filter((j) => !j.erreur).length);
const nbJobsErreur = computed(() => statsJobs.value.filter((j) => j.erreur).length);

const workerEnAlerte = computed(() => {
  if (!workerLastSuccess.value) return false;
  const diffMin = (Date.now() - new Date(workerLastSuccess.value).getTime()) / 60000;
  return diffMin > 180;
});

const workerDepuisTexte = computed(() => {
  if (!workerLastSuccess.value) return null;
  const min = Math.floor((Date.now() - new Date(workerLastSuccess.value).getTime()) / 60000);
  if (min < 1) return "à l'instant";
  if (min < 60) return "il y a " + min + " min";
  const h = Math.floor(min / 60);
  const m = min % 60;
  return m > 0 ? "il y a " + h + " h " + m + " min" : "il y a " + h + " h";
});

const configParCle = computed(() => {
  const map = {};
  for (const c of configs.value) map[c.cle] = c;
  return map;
});

const descriptionWebhookSecret = computed(() => {
  const baseUrl = configParCle.value["PUBLIC_BASE_URL"]?.valeur ?? "PUBLIC_BASE_URL";
  return (
    "Clé de signature des webhooks Resend (détection des bounces email)." +
    " À récupérer dans le dashboard Resend → Webhooks → créer un endpoint" +
    " avec l'URL " + baseUrl + "/api/webhooks/resend" +
    " (événements : email.bounced, email.complained)."
  );
});

const logsVisibles = computed(
  () => logs.value.filter(logVisible).length
);

// --- Chargement ---

async function charger() {
  chargement.value = true;
  try {
    const [repJobs, repConfig, repInfo, repUsers, repLogs, repProfil] = await Promise.all([
      fetch("/api/admin/jobs"),
      fetch("/api/admin/config"),
      fetch("/api/admin/info"),
      fetch("/api/admin/utilisateurs"),
      fetch("/api/admin/logs"),
      fetch("/api/user/profile"),
    ]);

    if (repJobs.ok) {
      const d = await repJobs.json();
      statsJobs.value = d.statsJobs ?? [];
      quotaTM.value = d.quotaTM ?? null;
      jobsEnCours.value = d.jobsEnCours ?? [];
      workerLastSuccess.value = d.workerLastSuccess ?? null;
      etatQuota.value = d.etatQuota ?? "ok";
      if (jobsEnCours.value.length > 0) demarrerPolling();
    }

    if (repConfig.ok) configs.value = await repConfig.json();
    if (repInfo.ok) {
      const info = await repInfo.json();
      appConfigSecretPresente.value = info.appConfigSecretPresente ?? false;
    }

    if (repUsers.ok) {
      utilisateurs.value = await repUsers.json();
      initialiserModifData();
    }

    if (repLogs.ok) logs.value = await repLogs.json();

    if (repProfil.ok) {
      const profil = await repProfil.json();
      utilisateurCourantId.value = profil.id ?? null;
    }
  } catch (_) {
    afficherToast("Erreur lors du chargement.", "erreur");
  } finally {
    chargement.value = false;
  }
}

function initialiserModifData() {
  for (const u of utilisateurs.value) {
    if (!modifData[u.id]) {
      modifData[u.id] = { nom: u.name ?? "", email: u.email ?? "", role: u.role ?? "user" };
    }
  }
}

onMounted(async () => {
  const hash = route.hash || "#jobs";
  ongletActif.value = onglets.some((o) => o.ancre === hash) ? hash : "#jobs";
  await charger();
});

onUnmounted(() => {
  arreterPolling();
});

// --- Jobs / Relancer ---

function formaterDuree(ms) {
  if (ms == null) return "";
  return ms < 1000 ? ms + " ms" : Math.floor(ms / 1000) + " s";
}

async function relancerFetches() {
  relancerEnCours.value = true;
  relancerStatut.value = "Fetches en cours…";
  try {
    const rep = await fetch("/api/admin/relancer", { method: "POST" });
    if (rep.ok) {
      demarrerPolling();
    } else {
      relancerEnCours.value = false;
      relancerStatut.value = "Erreur — worker inaccessible.";
    }
  } catch (_) {
    relancerEnCours.value = false;
    relancerStatut.value = "Erreur réseau.";
  }
}

function demarrerPolling() {
  arreterPolling();
  pollingInterval = setInterval(async () => {
    try {
      const rep = await fetch("/api/admin/jobs");
      if (!rep.ok) return;
      const d = await rep.json();
      statsJobs.value = d.statsJobs ?? statsJobs.value;
      jobsEnCours.value = d.jobsEnCours ?? [];
      if (jobsEnCours.value.length === 0) {
        arreterPolling();
        relancerEnCours.value = false;
        relancerStatut.value = "";
      }
    } catch (_) {
      arreterPolling();
    }
  }, 3000);
}

function arreterPolling() {
  if (pollingInterval) {
    clearInterval(pollingInterval);
    pollingInterval = null;
  }
}

// --- Logs ---

function logVisible(entree) {
  const okSource = !filtreSource.value || entree.source === filtreSource.value;
  const okNiveau = filtresNiveaux.value.size === 0 || filtresNiveaux.value.has(entree.niveau);
  return okSource && okNiveau;
}

function toggleNiveau(niv) {
  const s = new Set(filtresNiveaux.value);
  if (s.has(niv)) s.delete(niv);
  else s.add(niv);
  filtresNiveaux.value = s;
}

function reinitialiserFiltresLogs() {
  filtreSource.value = "";
  filtresNiveaux.value = new Set(["warn", "error"]);
}

async function rafraichirLogs() {
  try {
    const rep = await fetch("/api/admin/logs");
    if (rep.ok) logs.value = await rep.json();
  } catch (_) {
    afficherToast("Erreur lors du rafraîchissement des logs.", "erreur");
  }
}

function parseMeta(meta) {
  if (!meta) return null;
  try {
    const obj = JSON.parse(meta);
    if (typeof obj !== "object" || Array.isArray(obj) || !Object.keys(obj).length) return null;
    return obj;
  } catch {
    return null;
  }
}

// --- Configuration ---

async function sauvegarderConfig(cle, event) {
  const valeur = event.target.querySelector("[name=valeur]")?.value ?? "";
  const rep = await fetch("/api/admin/config", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cle, valeur }),
  });
  const corps = await rep.json();
  if (rep.ok) {
    afficherToast("Configuration sauvegardée.", "succes");
    const repConfig = await fetch("/api/admin/config");
    if (repConfig.ok) configs.value = await repConfig.json();
  } else {
    afficherToast(corps.erreur || "Erreur.", "erreur");
  }
}

function ouvrirDialogVapid() {
  dialogVapidRef.value?.showModal();
}

function fermerDialogVapid() {
  dialogVapidRef.value?.close();
}

async function confirmerVapid() {
  fermerDialogVapid();
  const rep = await fetch("/api/admin/vapid/generer", { method: "POST" });
  const corps = await rep.json();
  if (rep.ok) {
    afficherToast("Clés VAPID régénérées.", "succes");
    const repConfig = await fetch("/api/admin/config");
    if (repConfig.ok) configs.value = await repConfig.json();
  } else {
    afficherToast(corps.erreur || "Erreur lors de la génération.", "erreur");
  }
}

// --- Bannière ---

async function publierBanniere() {
  const msg = banniereMessage.value.trim();
  if (!msg) return;
  const rep = await fetch("/api/admin/banniere", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message: msg, type: banniereType.value }),
  });
  const corps = await rep.json();
  if (rep.ok) afficherToast("Bannière publiée.", "succes");
  else afficherToast(corps.erreur || "Erreur lors de la publication.", "erreur");
}

async function effacerBanniere() {
  const rep = await fetch("/api/admin/banniere", { method: "DELETE" });
  const corps = await rep.json();
  if (rep.ok) afficherToast("Bannière effacée.", "succes");
  else afficherToast(corps.erreur || "Erreur lors de l'effacement.", "erreur");
}

// --- Utilisateurs ---

async function creerUtilisateur() {
  const email = nouvelEmail.value.trim();
  if (!email) return;
  const rep = await fetch("/api/admin/utilisateurs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email }),
  });
  const corps = await rep.json();
  if (rep.ok || rep.status === 201) {
    nouvelEmail.value = "";
    lienInvitation.value = corps.lienReset ?? "";
    dialogInvitationRef.value?.showModal();
    const repUsers = await fetch("/api/admin/utilisateurs");
    if (repUsers.ok) {
      utilisateurs.value = await repUsers.json();
      initialiserModifData();
    }
  } else {
    afficherToast(corps.erreur || "Impossible de créer le compte.", "erreur");
  }
}

function copierLien() {
  if (navigator.clipboard) navigator.clipboard.writeText(lienInvitation.value);
}

function fermerDialogInvitation() {
  dialogInvitationRef.value?.close();
}

function toggleModif(userId) {
  const s = new Set(modifOuverts.value);
  if (s.has(userId)) s.delete(userId);
  else s.add(userId);
  modifOuverts.value = s;
}

async function sauvegarderModif(u) {
  delete modifErreurs[u.id];
  const d = modifData[u.id];
  const rep = await fetch("/api/admin/utilisateurs/" + u.id, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ nom: d.nom, email: d.email, role: d.role }),
  });
  const corps = await rep.json();
  if (rep.ok) {
    toggleModif(u.id);
    const repUsers = await fetch("/api/admin/utilisateurs");
    if (repUsers.ok) {
      utilisateurs.value = await repUsers.json();
      initialiserModifData();
    }
    afficherToast("Utilisateur modifié.", "succes");
  } else {
    modifErreurs[u.id] = corps.erreur || "Erreur.";
  }
}

async function reinitMdp(u) {
  const rep = await fetch("/api/admin/utilisateurs/" + u.id + "/reset-mdp", { method: "POST" });
  const corps = await rep.json();
  if (rep.ok) afficherToast("Email de réinitialisation envoyé.", "succes");
  else afficherToast(corps.erreur || "Erreur.", "erreur");
}

function ouvrirDialogSupprimer(u) {
  userASupprimer.value = u;
  dialogSupprimerUserRef.value?.showModal();
}

function fermerDialogSupprimer() {
  dialogSupprimerUserRef.value?.close();
  userASupprimer.value = null;
}

async function confirmerSuppression() {
  if (!userASupprimer.value) return;
  const userId = userASupprimer.value.id;
  fermerDialogSupprimer();
  const rep = await fetch("/api/admin/utilisateurs/" + userId, { method: "DELETE" });
  const corps = await rep.json();
  if (rep.ok) {
    utilisateurs.value = utilisateurs.value.filter((u) => u.id !== userId);
    afficherToast("Compte supprimé.", "succes");
  } else {
    afficherToast(corps.erreur || "Erreur lors de la suppression.", "erreur");
  }
}
</script>
