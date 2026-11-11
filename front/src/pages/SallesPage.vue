<template>
  <div>
    <div class="page-entete">
      <h1>Mes salles</h1>
      <p v-if="donnees" class="page-sous-titre">
        {{ donnees.salles.length }} salle{{
          donnees.salles.length !== 1 ? "s" : ""
        }}
        suivie{{ donnees.salles.length !== 1 ? "s" : "" }}
      </p>
    </div>

    <form class="form-recherche" @submit.prevent="rechercher">
      <label for="q-salle" class="lecteur-ecran">Rechercher une salle</label>
      <input
        id="q-salle"
        v-model="q"
        type="text"
        placeholder="Rechercher ou saisir une salle…"
        minlength="2"
        autocomplete="off"
      />
      <button
        type="submit"
        class="bouton bouton-secondaire"
        :disabled="!etat.peutLancer"
      >
        Rechercher
      </button>
    </form>
    <p class="form-aide">{{ CONSIGNE_RECHERCHE }}</p>
    <p
      v-if="!etat.peutLancer"
      class="form-aide form-aide-avertissement"
      role="status"
    >
      {{ etat.explication }}
    </p>
    <p class="form-aide">
      La recherche porte sur les concerts en base et les suggestions
      OpenStreetMap/Ticketmaster. Si une salle n'apparaît pas, vous pouvez
      l'ajouter directement par nom.
    </p>

    <div
      v-if="erreur"
      class="alerte alerte-erreur"
      role="alert"
      aria-live="assertive"
    >
      {{ erreur }}
    </div>

    <EtatChargement v-if="chargement" />

    <template v-else-if="donnees">
      <section
        v-if="donnees.resultats && donnees.resultats.length > 0"
        class="section-resultats"
        aria-live="polite"
        aria-atomic="false"
      >
        <h2 class="section-libelle">Résultats pour « {{ qSoumis }} »</h2>
        <ul class="liste-cartes">
          <li
            v-for="r in donnees.resultats"
            :key="r.salle + r.tmVenueId + r.osmId"
            class="carte-item"
          >
            <div>
              <p class="carte-nom">{{ r.salle }}</p>
              <p v-if="r.ville" class="carte-sous-titre">{{ r.ville }}</p>
              <span v-if="r.tmVenueId" class="badge badge-source">TM</span>
              <span
                v-if="r.osmId"
                class="badge badge-source badge-source-metadonnees"
                >osm</span
              >
            </div>
            <button
              type="button"
              class="bouton bouton-petit bouton-secondaire"
              :aria-label="'Suivre ' + r.salle"
              @click="ajouter(r.salle, r.tmVenueId, r.osmId, r.wikidataId)"
            >
              Suivre
            </button>
          </li>
        </ul>
      </section>

      <div
        v-else-if="qSoumis && qSoumis.trim().length >= 2"
        class="section-resultats"
        aria-live="polite"
      >
        <div
          v-if="donnees.dejaLaSuivie"
          class="alerte alerte-info"
          role="status"
        >
          Vous suivez déjà « {{ qSoumis }} ».
        </div>
        <div v-else class="alerte alerte-info" role="status">
          <p>Aucun résultat pour « {{ qSoumis }} ».</p>
          <button
            type="button"
            class="bouton bouton-petit bouton-secondaire"
            @click="ouvrirSignalement"
          >
            Signaler cette salle
          </button>
        </div>
      </div>

      <div v-if="donnees.salles.length === 0" class="etat-vide">
        <p class="etat-vide-titre">Aucune salle suivie.</p>
        <p class="etat-vide-texte">
          Recherchez une salle ci-dessus pour commencer.
        </p>
      </div>

      <section v-else>
        <h2 class="section-libelle">Salles suivies</h2>
        <ul class="liste-cartes">
          <li
            v-for="salle in donnees.salles"
            :key="salle.id"
            class="carte-item"
          >
            <div>
              <span class="carte-nom">
                {{ salle.nom }}
              </span>
              <p v-if="salle.couverte === false" class="carte-sous-titre">
                {{ MESSAGE_SALLE_NON_COUVERTE }}
              </p>
            </div>
            <button
              type="button"
              class="bouton bouton-danger bouton-petit"
              :aria-label="'Supprimer ' + salle.nom"
              @click="demanderSuppression(salle)"
            >
              Supprimer
            </button>
          </li>
        </ul>
      </section>
    </template>

    <DialogueSuppressionSuivi
      ref="dialogueSuppression"
      titre="Supprimer la salle"
      :nom="salleASupprimer?.nom"
      @confirmer="confirmerSuppression"
    />

    <dialog
      id="dialog-signaler-salle"
      ref="dialogSignalementRef"
      aria-labelledby="titre-signaler-salle"
      aria-modal="true"
    >
      <div class="dialog-entete">
        <h2 id="titre-signaler-salle" class="dialog-titre">
          Signaler une salle manquante
        </h2>
      </div>
      <form id="form-signaler-salle" @submit.prevent="envoyerSignalement">
        <div class="champ">
          <label for="sig-nom" class="champ-label"
            >Nom de la salle <span aria-hidden="true">*</span></label
          >
          <input
            id="sig-nom"
            v-model="signalement.nom"
            type="text"
            required
            maxlength="200"
            autocomplete="off"
          />
        </div>
        <div class="champ">
          <label for="sig-ville" class="champ-label">Ville / code postal</label>
          <input
            id="sig-ville"
            v-model="signalement.ville"
            type="text"
            maxlength="100"
            autocomplete="off"
          />
        </div>
        <div class="champ">
          <label for="sig-url" class="champ-label">Site ou billetterie</label>
          <input
            id="sig-url"
            v-model="signalement.urlSite"
            type="url"
            maxlength="500"
            autocomplete="off"
          />
        </div>
        <div class="champ">
          <label for="sig-commentaire" class="champ-label">Commentaire</label>
          <textarea
            id="sig-commentaire"
            v-model="signalement.commentaire"
            rows="3"
            maxlength="1000"
          ></textarea>
        </div>
        <div class="dialog-pied">
          <button
            type="submit"
            class="bouton bouton-primaire"
            :disabled="signalementEnCours"
          >
            Envoyer
          </button>
          <button
            type="button"
            class="bouton bouton-texte"
            @click="dialogSignalementRef.close()"
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
import { notificationsJoignables } from "../composables/useEtatNotifications.js";
defineOptions({ name: "SallesPage" });

import { computed, ref, shallowRef, onMounted, onUnmounted } from "vue";
import {
  CONSIGNE_RECHERCHE,
  etatRecherche,
} from "../utils/consigneRecherche.js";
import { useDialog } from "../composables/useDialog.js";
import { useToasts } from "../composables/useToasts.js";
import EtatChargement from "../components/EtatChargement.vue";
import DialogueSuppressionSuivi from "../components/DialogueSuppressionSuivi.vue";
import { MESSAGE_SALLE_NON_COUVERTE } from "../utils/couverture.js";

const { afficherToast } = useToasts();

const chargement = ref(true);
const donnees = shallowRef(null);
const q = ref("");
const qSoumis = ref("");
const erreur = ref("");
const salleASupprimer = ref(null);
const signalement = ref({ nom: "", ville: "", urlSite: "", commentaire: "" });
const signalementEnCours = ref(false);
const dialogueSuppression = ref(null);
const { dialogRef: dialogSignalementRef, ouvrir: ouvrirDialogSignalement } =
  useDialog();
const etat = computed(() => etatRecherche(q.value));
const controleur = new AbortController();

async function charger(recherche = "") {
  const params =
    recherche.trim().length >= 2 ? "?q=" + encodeURIComponent(recherche) : "";
  const rep = await appelApi("/api/salles" + params, {
    signal: controleur.signal,
  });
  if (!rep.ok) throw new Error();
  return lireJson(rep);
}

async function rechercher() {
  erreur.value = "";
  try {
    qSoumis.value = q.value;
    donnees.value = await charger(q.value);
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Impossible de rechercher.", "erreur");
  }
}

async function ajouter(nom, tmVenueId, osmId = null, wikidataId = null) {
  erreur.value = "";
  // le nudge doit se baser sur l'état AVANT l'ajout — un seul ajout ne doit pas se compter
  // lui-même comme preuve d'un compte déjà actif.
  let premierAjout = false;
  try {
    const repStats = await appelApi("/api/parametres/stats");
    if (repStats.ok) {
      const stats = await lireJson(repStats);
      premierAjout = (stats.nbArtistes ?? 0) + (stats.nbSalles ?? 0) === 0;
    }
  } catch (_) {
    // échec ignoré : le nudge ne s'affichera simplement pas
  }

  try {
    const rep = await appelApi("/api/salles", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        nom,
        tmVenueId: tmVenueId || null,
        osmId,
        wikidataId,
      }),
      signal: controleur.signal,
    });
    if (rep.status === 409) {
      afficherToast("Vous suivez déjà cette salle.", "info");
      return;
    }
    if (!rep.ok) {
      const data = await lireJson(rep);
      afficherToast(data.erreur || "Erreur lors de l'ajout.", "erreur");
      return;
    }
    afficherToast(
      `Recherche des concerts de « ${nom} » en cours : ils apparaîtront dans quelques secondes.`,
      "info",
    );
    if (premierAjout && (await notificationsJoignables()) === false) {
      afficherToast(
        "Astuce : activez les notifications dans Paramètres pour être alerté de vos prochains concerts.",
        "info",
      );
    }
    q.value = "";
    qSoumis.value = "";
    donnees.value = await charger();
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Impossible d'ajouter cette salle.", "erreur");
  }
}

function demanderSuppression(salle) {
  salleASupprimer.value = salle;
  dialogueSuppression.value.ouvrir();
}

async function confirmerSuppression() {
  dialogueSuppression.value.fermer();
  if (!salleASupprimer.value) return;
  try {
    const rep = await appelApi("/api/salles/" + salleASupprimer.value.id, {
      method: "DELETE",
      signal: controleur.signal,
    });
    if (!rep.ok) throw new Error();
    afficherToast("Salle retirée de votre liste.", "succes");
    donnees.value = await charger();
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Impossible de supprimer cette salle.", "erreur");
  } finally {
    salleASupprimer.value = null;
  }
}

function ouvrirSignalement() {
  signalement.value = {
    nom: qSoumis.value.trim(),
    ville: "",
    urlSite: "",
    commentaire: "",
  };
  ouvrirDialogSignalement();
}

async function envoyerSignalement() {
  signalementEnCours.value = true;
  try {
    const rep = await appelApi("/api/salles/signaler", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(signalement.value),
      signal: controleur.signal,
    });
    if (rep.status === 429) {
      afficherToast(
        "Trop de signalements. Réessayez dans une heure.",
        "erreur",
      );
      return;
    }
    if (!rep.ok) {
      const data = await lireJson(rep);
      afficherToast(data.erreur || "Erreur lors du signalement.", "erreur");
      return;
    }
    dialogSignalementRef.value.close();
    afficherToast("Votre signalement a été transmis.", "succes");
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Impossible d'envoyer le signalement.", "erreur");
  } finally {
    signalementEnCours.value = false;
  }
}

onMounted(async () => {
  try {
    donnees.value = await charger();
  } catch (e) {
    if (e.name !== "AbortError")
      afficherToast("Impossible de charger les salles.", "erreur");
  } finally {
    chargement.value = false;
  }
});

onUnmounted(() => controleur.abort());
</script>
