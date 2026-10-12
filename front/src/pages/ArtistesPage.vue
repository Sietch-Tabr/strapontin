<template>
  <div>
    <div class="page-entete">
      <h1>Mes artistes</h1>
      <p v-if="donnees" class="page-sous-titre">
        {{ donnees.artistes.length }} artiste{{
          donnees.artistes.length !== 1 ? "s" : ""
        }}
        suivi{{ donnees.artistes.length !== 1 ? "s" : "" }}
      </p>
    </div>

    <form class="form-recherche" @submit.prevent="rechercher">
      <label for="q-artiste" class="lecteur-ecran">Rechercher un artiste</label>
      <input
        id="q-artiste"
        v-model="q"
        type="text"
        placeholder="Rechercher un artiste…"
        minlength="2"
        autocomplete="off"
      />
      <button
        type="submit"
        class="bouton bouton-secondaire"
        :disabled="q.trim().length < 2"
      >
        Rechercher
      </button>
    </form>
    <p class="form-aide">
      Saisissez au moins 2 caractères. La recherche propose les artistes déjà en
      base, puis des suggestions Wikidata et Deezer.
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
        v-if="donnees.suggestions && donnees.suggestions.length > 0"
        class="section-resultats"
        aria-live="polite"
        aria-atomic="false"
      >
        <h2 class="section-libelle">Résultats pour « {{ qSoumis }} »</h2>
        <ul class="liste-cartes">
          <li
            v-for="s in donnees.suggestions"
            :key="s.nom + (s.wikidataId || '')"
            class="carte-item"
          >
            <div>
              <p class="carte-nom">
                {{ s.nom }}
                <span
                  v-if="s.source === 'wikidata'"
                  class="badge-source badge-source-metadonnees"
                  >wikidata</span
                >
              </p>
              <p v-if="s.description" class="carte-sous-titre">
                {{ s.description }}
              </p>
            </div>
            <button
              type="button"
              class="bouton bouton-petit bouton-secondaire"
              :aria-label="'Suivre ' + s.nom"
              @click="ajouter(s.nom, s.wikidataId)"
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
          v-if="donnees.dejaLeSuivi"
          class="alerte alerte-info"
          role="status"
        >
          Vous suivez déjà « {{ qSoumis }} ».
        </div>
        <div v-else class="alerte alerte-info" role="status">
          <p>Aucun résultat pour « {{ qSoumis }} ».</p>
          <button
            type="button"
            class="bouton bouton-primaire"
            @click="ajouter(qSoumis)"
          >
            Ajouter « {{ qSoumis }} » quand même
          </button>
        </div>
      </div>

      <div v-if="donnees.artistes.length === 0" class="etat-vide">
        <p class="etat-vide-titre">Aucun artiste suivi.</p>
        <p class="etat-vide-texte">
          Recherchez un artiste ci-dessus pour commencer.
        </p>
      </div>

      <section v-else>
        <h2 class="section-libelle">Artistes suivis</h2>
        <ul class="liste-cartes">
          <li
            v-for="artiste in donnees.artistes"
            :key="artiste.id"
            class="carte-item"
          >
            <span class="carte-nom">{{ artiste.artisteAffiche }}</span>
            <button
              type="button"
              class="bouton bouton-danger bouton-petit"
              :aria-label="'Supprimer ' + artiste.artisteAffiche"
              @click="demanderSuppression(artiste)"
            >
              Supprimer
            </button>
          </li>
        </ul>
      </section>
    </template>

    <dialog
      id="dialog-confirmer-suppr-artiste"
      ref="dialogRef"
      aria-labelledby="titre-confirmer-suppr-artiste"
      aria-modal="true"
    >
      <div class="dialog-entete">
        <h2 id="titre-confirmer-suppr-artiste" class="dialog-titre">
          Supprimer l'artiste
        </h2>
      </div>
      <p>
        Arrêter de suivre «
        <strong>{{ artisteASupprimer?.artisteAffiche }}</strong> » ?
      </p>
      <div class="dialog-pied">
        <button
          type="button"
          class="bouton bouton-danger"
          @click="confirmerSuppression"
        >
          Supprimer
        </button>
        <button
          type="button"
          class="bouton bouton-texte"
          @click="dialogRef.close()"
        >
          Annuler
        </button>
      </div>
    </dialog>
  </div>
</template>

<script setup>
import { appelApi, lireJson } from "../utils/api.js";
import { notificationsJoignables } from "../composables/useEtatNotifications.js";
defineOptions({ name: "ArtistesPage" });

import { ref, shallowRef, onMounted, onUnmounted } from "vue";
import { useDialog } from "../composables/useDialog.js";
import { useToasts } from "../composables/useToasts.js";
import EtatChargement from "../components/EtatChargement.vue";

const { afficherToast } = useToasts();

const chargement = ref(true);
const donnees = shallowRef(null);
const q = ref("");
const qSoumis = ref("");
const erreur = ref("");
const artisteASupprimer = ref(null);
const { dialogRef, ouvrir: ouvrirDialog, fermer: fermerDialog } = useDialog();
const controleur = new AbortController();

async function charger(recherche = "") {
  const params =
    recherche.trim().length >= 2 ? "?q=" + encodeURIComponent(recherche) : "";
  const rep = await appelApi("/api/artistes" + params, {
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

async function ajouter(nom, wikidataId = null) {
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
    const rep = await appelApi("/api/artistes", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ nom, wikidataId }),
      signal: controleur.signal,
    });
    if (rep.status === 409) {
      afficherToast("Vous suivez déjà cet artiste.", "info");
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
    afficherToast("Impossible d'ajouter cet artiste.", "erreur");
  }
}

function demanderSuppression(artiste) {
  artisteASupprimer.value = artiste;
  ouvrirDialog();
}

async function confirmerSuppression() {
  fermerDialog();
  if (!artisteASupprimer.value) return;
  try {
    const rep = await appelApi("/api/artistes/" + artisteASupprimer.value.id, {
      method: "DELETE",
      signal: controleur.signal,
    });
    if (!rep.ok) throw new Error();
    afficherToast("Artiste retiré de votre liste.", "succes");
    donnees.value = await charger();
  } catch (e) {
    if (e.name === "AbortError") return;
    afficherToast("Impossible de supprimer cet artiste.", "erreur");
  } finally {
    artisteASupprimer.value = null;
  }
}

onMounted(async () => {
  try {
    donnees.value = await charger();
  } catch (e) {
    if (e.name !== "AbortError")
      afficherToast("Impossible de charger les artistes.", "erreur");
  } finally {
    chargement.value = false;
  }
});

onUnmounted(() => controleur.abort());
</script>
