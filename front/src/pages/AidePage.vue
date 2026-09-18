<template>
  <div>
    <div class="page-entete">
      <h1>Aide</h1>
    </div>

    <div class="contenu-largeur-moyenne">
      <ChecklistPremiersPas />

      <p class="aide-pied">
        <button
          type="button"
          class="bouton bouton-texte"
          @click="revoirOnboarding"
        >
          Revoir la présentation de bienvenue
        </button>
      </p>

      <section class="aide-section" aria-labelledby="titre-usage">
        <p id="titre-usage" class="aide-libelle">Usage courant</p>
        <div class="aide-grille">
          <div class="carte">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-book-open" />
              </svg>
              Artistes &amp; salles
            </h2>
            <ul>
              <li>Recherchez et ajoutez les artistes et salles à suivre.</li>
              <li>
                Les concerts sont mis à jour automatiquement toutes les 2 heures
                depuis Ticketmaster et Eventim.
              </li>
              <li>
                Le badge <strong>Nouveau</strong> signale les concerts apparus
                depuis votre dernière notification.
              </li>
            </ul>
          </div>

          <div class="carte">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-bell" />
              </svg>
              Notifications
            </h2>
            <ul>
              <li>
                <strong>Email</strong> : récapitulatif quotidien à 8h ou à
                chaque mise à jour. À activer dans
                <RouterLink to="/parametres">Paramètres</RouterLink>.
              </li>
              <li>
                <strong>Push</strong> : notification instantanée sur votre
                appareil. Désactivé par défaut.
              </li>
            </ul>
          </div>

          <div class="carte">
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

          <div class="carte">
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

          <div class="carte carte-large">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-compass" />
              </svg>
              Mes spectacles &amp; Découverte
            </h2>
            <ul>
              <li>
                <strong>Mes spectacles</strong> : vos prochains concerts
                filtrables par catégorie et mois. Paginé par 100.
              </li>
              <li>
                <strong>Découverte</strong> : tous les concerts en base,
                filtrables par département.
              </li>
            </ul>
          </div>
        </div>
      </section>

      <section class="aide-section" aria-labelledby="titre-infos">
        <p id="titre-infos" class="aide-libelle">Informations</p>
        <div class="aide-grille">
          <div class="carte">
            <h2 class="carte-titre">
              <svg width="16" height="16" aria-hidden="true">
                <use href="/icons/lucide-sprite.svg#icon-shield-check" />
              </svg>
              Données personnelles
            </h2>
            <ul>
              <li>
                Données collectées : nom, identifiant, email, préférences et
                sessions. Utilisées uniquement pour le fonctionnement de
                l'application, jamais partagées.
              </li>
              <li>
                Hébergement sur serveur privé en France. Données conservées tant
                que le compte est actif.
              </li>
              <li>
                Export intégral et suppression de compte depuis
                <RouterLink to="/parametres">Paramètres → Compte</RouterLink>.
              </li>
            </ul>
          </div>

          <div class="carte">
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

          <div class="carte">
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

          <div class="carte">
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

      <p class="aide-pied">
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
          <select id="type-signalement" v-model="typeSignalement" autofocus>
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
defineOptions({ name: "AidePage" });

import { ref, shallowRef, onMounted } from "vue";
import { useToasts } from "../composables/useToasts.js";
import { corpsJsonOuVide } from "../utils/reponseJson.js";
import { useOnboarding } from "../composables/useOnboarding.js";
import ChecklistPremiersPas from "../components/ChecklistPremiersPas.vue";

const { afficherToast } = useToasts();
const { ouvrir: revoirOnboarding } = useOnboarding();

const config = shallowRef(null);
const dialogRef = ref(null);
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
}

async function envoyerSignalement() {
  erreurSignalement.value = "";
  envoiEnCours.value = true;
  try {
    const rep = await fetch("/api/aide/signalement", {
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
      const data = await corpsJsonOuVide(rep);
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
    const rep = await fetch("/api/aide/config");
    if (rep.ok) config.value = await rep.json();
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
  margin-bottom: 2.5rem;
}

.aide-grille {
  display: grid;
  gap: 0.75rem;
  grid-template-columns: repeat(auto-fill, minmax(18rem, 1fr));
}

.carte {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 1rem;
}

.carte-titre {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.875rem;
  font-weight: 500;
  margin-bottom: 0.5rem;
}

.carte ul {
  list-style: disc;
  padding-left: 1.25rem;
  font-size: 0.875rem;
  color: var(--fg-muted);
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.carte p {
  font-size: 0.875rem;
  color: var(--fg-muted);
}

.carte a {
  text-decoration: underline;
}

.carte-large {
  grid-column: 1 / -1;
}

.aide-pied {
  font-size: 0.875rem;
  color: var(--fg-muted);
}

.aide-pied a {
  text-decoration: underline;
}
</style>
