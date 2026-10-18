<template>
  <div>
    <div class="page-entete">
      <h1>Aide</h1>
    </div>

    <div class="contenu-largeur-moyenne">
      <ChecklistPremiersPas />

      <p class="aide-pied texte-secondaire">
        <button
          type="button"
          class="bouton bouton-secondaire"
          @click="revoirOnboarding"
        >
          Revoir le tour guidé des premières étapes
        </button>
      </p>

      <section class="aide-section" aria-labelledby="titre-usage">
        <p id="titre-usage" class="aide-libelle">Usage courant</p>
        <div class="aide-grille">
          <div class="aide-carte cadre cadre-fond cadre-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-book-open" />
              </svg>
              Artistes &amp; salles
            </h2>
            <ul>
              <li>Recherchez et ajoutez les artistes et salles à suivre.</li>
              <li>
                Les concerts sont mis à jour automatiquement depuis Ticketmaster
                et Eventim, toutes les deux heures en journée.
              </li>
              <li>
                Le badge <strong>Nouveau</strong> signale les concerts que vous
                n'avez pas encore vus ; il disparaît après votre passage sur
                l'accueil.
              </li>
              <li>
                <strong>Masquer</strong> retire de l'accueil un concert, ou tous
                les concerts à venir d'un artiste, après confirmation. Le filtre
                <strong>Masqués</strong> les affiche, et
                <strong>Réafficher</strong> les remet en place.
              </li>
              <li>
                Le filtre <strong>Indisponibles</strong> ajoute les concerts
                complets ou annulés ; sa valeur par défaut se règle dans les
                paramètres.
              </li>
            </ul>
          </div>

          <div class="aide-carte cadre cadre-fond cadre-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-bell" />
              </svg>
              Notifications
            </h2>
            <ul>
              <li>
                <strong>Fréquence</strong>, dans
                <RouterLink to="/parametres">Paramètres</RouterLink> :
                <template
                  v-for="(option, i) in FREQUENCES"
                  :key="option.valeur"
                >
                  {{ option.libelle.charAt(0).toLowerCase()
                  }}{{ option.libelle.slice(1)
                  }}{{ i < FREQUENCES.length - 1 ? " ; " : "." }}
                </template>
                Elle s'applique à l'email comme au push.
              </li>
              <li>
                <strong>Email</strong> : activé par défaut sur un nouveau
                compte, en récapitulatif hebdomadaire des concerts des artistes
                suivis. Chaque contenu (artistes, salles) se coche à part.
              </li>
              <li>
                <strong>Push</strong> : notification sur votre appareil,
                désactivée par défaut ; il faut d'abord activer les
                notifications sur au moins un appareil. {{ PLAGE_SILENCIEUSE }}
              </li>
            </ul>
          </div>

          <div class="aide-carte cadre cadre-fond cadre-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-smartphone" />
              </svg>
              Installer l'application
            </h2>
            <ul>
              <li>
                <strong>iOS</strong> : Safari → Partager → « Sur l'écran
                d'accueil ».
              </li>
              <li>
                <strong>Android</strong> : Chrome → menu ⋮ → « Installer
                l'application ».
              </li>
              <li>
                <strong>Desktop</strong> : icône d'installation dans la barre
                d'adresse.
              </li>
            </ul>
          </div>

          <div class="aide-carte cadre cadre-fond cadre-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-settings" />
              </svg>
              Paramètres
            </h2>
            <ul>
              <li>
                Configurez vos départements de recherche et le filtre des
                billets indisponibles.
              </li>
              <li>
                Thème Automatique, Clair ou Sombre — synchronisé sur tous vos
                appareils.
              </li>
              <li>Export et import de vos préférences au format JSON.</li>
            </ul>
          </div>

          <div class="aide-carte aide-carte-large cadre cadre-fond cadre-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-compass" />
              </svg>
              Mes spectacles &amp; Découverte
            </h2>
            <ul>
              <li>
                <strong>Mes spectacles</strong> : vos prochains concerts
                filtrables par classification, genre et mois.
              </li>
              <li>
                <strong>Découverte</strong> : tous les concerts de vos
                départements, même ceux d'artistes que vous ne suivez pas.
              </li>
            </ul>
          </div>
        </div>
      </section>

      <section class="aide-section" aria-labelledby="titre-infos">
        <p id="titre-infos" class="aide-libelle">Informations</p>
        <div class="aide-grille">
          <div class="aide-carte cadre cadre-fond cadre-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-shield-check" />
              </svg>
              Données personnelles
            </h2>
            <ul>
              <li>
                Données collectées : identifiant, email, préférences, appareils
                connectés (navigateur, système) et, dans le journal technique,
                adresse IP des connexions. Utilisées uniquement pour le
                fonctionnement de l'application, jamais partagées.
              </li>
              <li>
                Hébergement sur serveur privé en France. Un compte inutilisé
                pendant 6 mois reçoit un email, puis est supprimé avec toutes
                ses données 15 jours plus tard s'il reste inutilisé.
              </li>
              <li>
                Export intégral et suppression de compte depuis
                <RouterLink to="/parametres#donnees"
                  >Paramètres → Données</RouterLink
                >.
              </li>
            </ul>
          </div>

          <div class="aide-carte cadre cadre-fond cadre-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-scale" />
              </svg>
              Licence
            </h2>
            <p>
              Strapontin est distribué sous licence <strong>MIT</strong> —
              logiciel libre, utilisation, modification et redistribution
              autorisées sous réserve de conserver la mention de copyright.
            </p>
          </div>

          <div class="aide-carte cadre cadre-fond cadre-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-code" />
              </svg>
              Code source
            </h2>
            <p>
              Le code source est disponible sur
              <a
                href="https://codeberg.org/Kwaite/strapontin"
                target="_blank"
                rel="noopener noreferrer"
                >Codeberg</a
              >.
            </p>
          </div>

          <div class="aide-carte cadre cadre-fond cadre-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-message-square" />
              </svg>
              Signaler un problème
            </h2>
            <div v-if="config && config.resendDisponible">
              <p class="param-aide">Un bug, une suggestion, une question ?</p>
              <button
                id="btn-ouvrir-signalement"
                type="button"
                class="bouton bouton-secondaire"
                @click="ouvrirSignalement"
              >
                Ouvrir le formulaire
              </button>
            </div>
            <div v-else-if="config && config.contactEmail">
              <p class="param-aide">
                Écrivez à
                <a :href="'mailto:' + config.contactEmail">l'administrateur</a>.
              </p>
            </div>
            <div v-else-if="config">
              <p class="param-aide">
                Aucun moyen de contact configuré actuellement.
              </p>
            </div>
            <div v-if="config && config.signalLien">
              <p class="param-aide">
                Rejoignez la communauté sur
                <a
                  :href="config.signalLien"
                  target="_blank"
                  rel="noopener noreferrer"
                  >Signal</a
                >.
              </p>
            </div>
          </div>
        </div>
      </section>

      <p class="aide-pied texte-secondaire">
        <RouterLink to="/changelog">Voir les nouveautés →</RouterLink>
      </p>
    </div>

    <dialog
      v-if="config && config.resendDisponible"
      id="dialog-signaler"
      ref="dialogRef"
      aria-labelledby="titre-signalement"
      aria-modal="true"
    >
      <div class="dialog-entete">
        <h2 id="titre-signalement" class="dialog-titre">
          Signaler un problème ou une suggestion
        </h2>
        <button
          type="button"
          class="bouton-icone"
          aria-label="Fermer"
          @click="dialogRef.close()"
        >
          <svg width="18" height="18" aria-hidden="true">
            <use href="/icons/lucide-sprite.svg#icon-x" />
          </svg>
        </button>
      </div>

      <div v-if="succesSignalement" class="alerte alerte-succes" role="alert">
        Signalement envoyé. Merci !
      </div>
      <div v-if="erreurSignalement" class="alerte alerte-erreur" role="alert">
        {{ erreurSignalement }}
      </div>

      <form
        id="form-signalement"
        class="form-vertical"
        @submit.prevent="envoyerSignalement"
      >
        <div class="form-groupe">
          <label for="type-signalement">Type</label>
          <select
            id="type-signalement"
            ref="typeSignalementRef"
            v-model="typeSignalement"
          >
            <option value="bug">Bug</option>
            <option value="suggestion">Amélioration</option>
            <option value="question">Question</option>
          </select>
        </div>
        <div class="form-groupe">
          <label for="message-signalement">
            Message <span class="form-aide">(2000 caractères max)</span>
          </label>
          <textarea
            id="message-signalement"
            v-model="messageSignalement"
            rows="8"
            maxlength="2000"
            required
          ></textarea>
        </div>
        <div class="dialog-pied">
          <button
            type="submit"
            class="bouton bouton-primaire"
            :disabled="envoiEnCours"
          >
            Envoyer
          </button>
          <button
            type="button"
            class="bouton bouton-texte"
            @click="dialogRef.close()"
          >
            Annuler
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup>
import { appelApi, lireJson } from "../utils/api.js";
defineOptions({ name: "AidePage" });

import { ref, shallowRef, onMounted } from "vue";
import { useToasts } from "../composables/useToasts.js";
import { useOnboarding } from "../composables/useOnboarding.js";
import ChecklistPremiersPas from "../components/ChecklistPremiersPas.vue";
import { FREQUENCES, PLAGE_SILENCIEUSE } from "../utils/notifications.js";

const { afficherToast } = useToasts();
const { ouvrir: revoirOnboarding } = useOnboarding();

const config = shallowRef(null);
const dialogRef = ref(null);
const typeSignalementRef = ref(null);
const typeSignalement = ref("bug");
const messageSignalement = ref("");
const erreurSignalement = ref("");
const succesSignalement = ref(false);
const envoiEnCours = ref(false);

function ouvrirSignalement() {
  succesSignalement.value = false;
  erreurSignalement.value = "";
  messageSignalement.value = "";
  dialogRef.value?.showModal();
  typeSignalementRef.value?.focus();
}

async function envoyerSignalement() {
  erreurSignalement.value = "";
  envoiEnCours.value = true;
  try {
    const rep = await appelApi("/api/aide/signalement", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        type: typeSignalement.value,
        message: messageSignalement.value,
      }),
    });
    if (rep.status === 429) {
      erreurSignalement.value =
        "Trop de signalements. Réessayez dans une heure.";
      return;
    }
    if (!rep.ok) {
      const data = await lireJson(rep);
      erreurSignalement.value = data.erreur || "Une erreur est survenue.";
      return;
    }
    succesSignalement.value = true;
    messageSignalement.value = "";
    afficherToast("Signalement envoyé. Merci !", "succes");
    // fermer immédiatement empêchait Vue de jamais peindre l'alerte de succès (v-if
    // succesSignalement) — le dialog disparaissait avant le prochain repaint. Le délai laisse le
    // message visible un instant avant fermeture.
    setTimeout(() => dialogRef.value?.close(), 1500);
  } catch {
    erreurSignalement.value = "Impossible de se connecter au serveur.";
  } finally {
    envoiEnCours.value = false;
  }
}

onMounted(async () => {
  try {
    const rep = await appelApi("/api/aide/config");
    if (rep.ok) config.value = await lireJson(rep);
  } catch (e) {
    console.error("Échec chargement config aide", e);
  }
});
</script>

<style scoped>
.contenu-largeur-moyenne {
  max-width: 42rem;
}

.aide-section {
  margin-bottom: var(--espace-20);
}

.aide-grille {
  display: grid;
  gap: var(--espace-6);
  grid-template-columns: repeat(auto-fill, minmax(18rem, 1fr));
}

.carte-titre {
  display: flex;
  align-items: center;
  gap: var(--espace-3);
  font-size: var(--texte-m);
  font-weight: 500;
  margin-bottom: var(--espace-4);
}

.aide-carte ul {
  list-style: disc;
  padding-left: var(--espace-10);
  font-size: var(--texte-m);
  color: var(--fg-muted);
  display: flex;
  flex-direction: column;
  gap: var(--espace-2);
}

.aide-carte p {
  font-size: var(--texte-m);
  color: var(--fg-muted);
}

.aide-carte a {
  text-decoration: underline;
}

.aide-carte-large {
  grid-column: 1 / -1;
}

.aide-pied a {
  text-decoration: underline;
}
</style>
