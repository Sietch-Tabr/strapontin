<template>
  <ListeReferentielAdmin
    entite="artiste"
    :recherche-initiale="rechercheInitiale"
  >
    <template #aide>
      <p class="param-aide">
        Noms canoniques et statuts des sources externes. Le nom affiché écrase
        l'enrichissement automatique Wikidata/Deezer — laissez vide pour le
        réactiver.
      </p>
      <p class="param-aide">{{ AIDE_STATUTS_ARTISTES }}</p>
    </template>

    <template #statuts>
      <option value="a_jour">À jour</option>
      <option value="invalide">Invalide</option>
    </template>

    <template #apres-statut="{ item }">
      <p v-if="item.interventionDepuis" class="texte-note">
        Nom Ticketmaster douteux depuis
        {{ formaterInstant(item.interventionDepuis) }}
      </p>
    </template>

    <template #entetes-apres-wikidata>
      <th scope="col">Deezer</th>
    </template>

    <template #cellules-apres-wikidata="{ item }">
      <td data-label="Deezer">
        <a
          v-if="item.deezerId"
          :href="'https://www.deezer.com/artist/' + item.deezerId"
          target="_blank"
          rel="noopener noreferrer"
          >{{ item.deezerId }}</a
        >
        <span v-else class="etat-vide-texte">—</span>
      </td>
    </template>

    <template #actions-apres="{ item, charger }">
      <template v-if="item.statut === 'invalide'">
        <button
          type="button"
          class="bouton bouton-secondaire bouton-petit"
          title="Effacer le signal d'intervention sans fusion (divergence de nom jugée acceptable)"
          @click="ignorerIntervention(item, charger)"
        >
          Ignorer
        </button>
        <button
          v-if="item.identifiantTmExterne"
          type="button"
          class="bouton bouton-secondaire bouton-petit"
          title="Corriger directement l'identifiant Ticketmaster"
          @click="ouvrirCorrectionTm(item)"
        >
          Identifiant TM
        </button>
      </template>
    </template>

    <template #dialogues="{ charger }">
      <dialog
        ref="dialogIdentifiantTmRef"
        aria-labelledby="titre-identifiant-tm"
        aria-modal="true"
      >
        <div class="dialog-entete">
          <h2 id="titre-identifiant-tm" class="dialog-titre">
            Corriger l'identifiant Ticketmaster
          </h2>
        </div>
        <form @submit.prevent="sauvegarderIdentifiantTm(charger)">
          <div class="champ">
            <label for="edit-identifiant-tm" class="champ-label"
              >Identifiant Ticketmaster (attractionId)</label
            >
            <input
              id="edit-identifiant-tm"
              v-model="identifiantTm.valeur"
              type="text"
              maxlength="200"
              autocomplete="off"
              required
            />
            <p class="param-aide">
              Remplace le lien vers l'artiste Ticketmaster suivi et efface le
              signal d'intervention.
            </p>
          </div>
          <div class="dialog-pied">
            <button
              type="submit"
              class="bouton bouton-primaire"
              :disabled="enCoursIdentifiantTm"
            >
              Enregistrer
            </button>
            <button
              type="button"
              class="bouton bouton-texte"
              @click="fermerCorrectionTm"
            >
              Annuler
            </button>
          </div>
        </form>
      </dialog>
    </template>
  </ListeReferentielAdmin>
</template>

<script setup>
import { reactive, ref } from "vue";
import ListeReferentielAdmin from "./ListeReferentielAdmin.vue";
import { appelApi, lireJson } from "../../utils/api.js";
import { useDialog } from "../../composables/useDialog.js";
import { useToasts } from "../../composables/useToasts.js";
import { formaterInstant } from "../../composables/useDates.js";
import { AIDE_STATUTS_ARTISTES } from "../../utils/statutReferentiel.js";
defineOptions({ name: "AdminArtistes" });

defineProps({
  rechercheInitiale: { type: String, default: "" },
});

const { afficherToast } = useToasts();

// Actions propres aux artistes : necessite_intervention_le n'existe pas sur salle_sources.
async function ignorerIntervention(artiste, charger) {
  try {
    const rep = await appelApi(
      `/api/admin/artistes/${artiste.id}/ignorer-intervention`,
      { method: "POST" },
    );
    if (rep.ok) {
      afficherToast("Intervention ignorée.", "succes");
      await charger();
    } else {
      afficherToast("Erreur lors de la mise à jour.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

const {
  dialogRef: dialogIdentifiantTmRef,
  ouvrir: ouvrirDialogIdentifiantTm,
  fermer: fermerCorrectionTm,
} = useDialog();
const identifiantTm = reactive({ artisteId: null, valeur: "" });
const enCoursIdentifiantTm = ref(false);

function ouvrirCorrectionTm(artiste) {
  identifiantTm.artisteId = artiste.id;
  identifiantTm.valeur = artiste.identifiantTmExterne ?? "";
  ouvrirDialogIdentifiantTm();
}

async function sauvegarderIdentifiantTm(charger) {
  if (enCoursIdentifiantTm.value || !identifiantTm.valeur.trim()) return;
  enCoursIdentifiantTm.value = true;
  try {
    const rep = await appelApi(
      `/api/admin/artistes/${identifiantTm.artisteId}/identifiant-tm`,
      {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ identifiantExterne: identifiantTm.valeur }),
      },
    );
    if (rep.ok) {
      fermerCorrectionTm();
      afficherToast("Identifiant Ticketmaster corrigé.", "succes");
      await charger();
    } else {
      const data = await lireJson(rep);
      afficherToast(data.erreur || "Erreur lors de la correction.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  } finally {
    enCoursIdentifiantTm.value = false;
  }
}
</script>
