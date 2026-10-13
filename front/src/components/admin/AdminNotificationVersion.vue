<template>
  <div class="param-bloc">
    <h3 class="param-libelle">Notification de nouvelle version</h3>
    <p class="param-aide">
      Envoie une notification push aux utilisateurs ayant activé « Nouvelle
      version de l'application » dans leurs préférences (version courante :
      {{ versionApplication || "—" }}). Vérifiez d'abord qu'une entrée existe
      pour cette version dans le changelog utilisateur : aucune vérification
      automatique n'est faite côté serveur.
    </p>
    <div class="form-actions">
      <button
        type="button"
        class="bouton bouton-primaire"
        :disabled="enCours"
        @click="ouvrir"
      >
        Notifier la nouvelle version
      </button>
    </div>
  </div>

  <dialog
    id="dialog-notifier-version"
    ref="dialogRef"
    aria-labelledby="titre-dialog-notifier-version"
    aria-modal="true"
  >
    <h2 id="titre-dialog-notifier-version" class="dialog-titre">
      Notifier la nouvelle version ?
    </h2>
    <p class="dialog-corps">
      Une notification push sera envoyée à tous les utilisateurs ayant activé «
      Nouvelle version de l'application ». Vérifiez au préalable qu'une entrée
      existe bien pour la version {{ versionApplication || "—" }} dans le
      changelog utilisateur.
    </p>
    <div class="dialog-pied">
      <button
        id="dialog-notifier-version-confirmer"
        type="button"
        class="bouton bouton-primaire"
        @click="confirmer"
      >
        Notifier
      </button>
      <button
        id="dialog-notifier-version-annuler"
        type="button"
        class="bouton bouton-texte"
        @click="fermer"
      >
        Annuler
      </button>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: "AdminNotificationVersion" });

import { onMounted, ref } from "vue";
import { appelApi, lireJson } from "../../utils/api.js";
import { useDialog } from "../../composables/useDialog.js";
import { useToasts } from "../../composables/useToasts.js";

const { afficherToast } = useToasts();
const { dialogRef, ouvrir, fermer } = useDialog();
const versionApplication = ref("");
const enCours = ref(false);

onMounted(async () => {
  try {
    const rep = await appelApi("/api/admin/info");
    if (rep.ok) versionApplication.value = (await lireJson(rep)).version ?? "";
  } catch {
    versionApplication.value = "";
  }
});

async function confirmer() {
  fermer();
  enCours.value = true;
  try {
    const rep = await appelApi("/api/admin/notifier-version", {
      method: "POST",
    });
    const corps = await lireJson(rep);
    if (rep.ok) {
      afficherToast(
        `Notification envoyée pour la version ${corps.version}.`,
        "succes",
      );
    } else {
      afficherToast(
        corps.erreur || "Erreur lors de la notification.",
        "erreur",
      );
    }
  } catch {
    afficherToast("Erreur réseau lors de la notification.", "erreur");
  } finally {
    enCours.value = false;
  }
}
</script>
