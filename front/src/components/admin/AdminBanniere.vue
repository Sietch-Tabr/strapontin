<template>
  <section id="communication" class="admin-section">
    <div class="param-bloc">
      <h3 class="param-libelle">Bannière d'information</h3>
      <p class="param-aide">
        Publiez un message visible par tous les utilisateurs connectés dans la
        minute. Le message réapparaît à chaque changement de page, sauf si
        l'utilisateur le ferme manuellement.
      </p>
      <p v-if="banniereActive" class="param-aide">
        <span class="badge badge-succes">Publiée</span>
        Type : {{ banniereActive.type }} — « {{ banniereActive.message }} »
      </p>
      <p v-else class="param-aide">
        <span class="badge badge-manquant" role="status"
          >Aucune bannière publiée</span
        >
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
        <select
          id="banniere-type"
          v-model="banniereType"
          name="type"
          class="form-control"
        >
          <option value="info">
            Information (se cache après 10 s, réapparaît à la prochaine page)
          </option>
          <option value="warning">
            Avertissement (se cache après 20 s, réapparaît à la prochaine page)
          </option>
          <option value="error">Erreur (fermeture manuelle uniquement)</option>
        </select>
      </div>
      <div class="form-actions">
        <button
          type="button"
          class="bouton bouton-primaire"
          @click="publierBanniere"
        >
          Publier
        </button>
        <button
          type="button"
          class="bouton bouton-danger-contour"
          @click="effacerBanniere"
        >
          Effacer
        </button>
      </div>
    </div>
    <AdminNotificationVersion />
    <div
      class="communication-exceptionnelle"
      role="group"
      aria-labelledby="titre-communication-exceptionnelle"
    >
      <h3 id="titre-communication-exceptionnelle" class="section-titre">
        Communication exceptionnelle
      </h3>
      <p class="param-aide">
        Réservée à un incident de sécurité : visible de tous, jusque sur la page
        de connexion.
      </p>
      <AdminIncident />
    </div>
  </section>
</template>

<script setup>
import { appelApi, lireJson } from "../../utils/api.js";
defineOptions({ name: "AdminBanniere" });

import { ref, onMounted } from "vue";
import AdminIncident from "./AdminIncident.vue";
import AdminNotificationVersion from "./AdminNotificationVersion.vue";
import { useToasts } from "../../composables/useToasts.js";
import { parseJsonOuNull } from "../../utils/reponseJson.js";
import { chargerBanniere } from "../../composables/useBanniere.js";

const { afficherToast } = useToasts();

const banniereMessage = ref("");
const banniereType = ref("info");
const banniereActive = ref(null);

async function chargerBanniereActive() {
  try {
    const rep = await appelApi("/api/banniere/json");
    const texte = rep.ok ? await rep.text() : "";
    banniereActive.value = parseJsonOuNull(texte);
  } catch {
    afficherToast("Erreur réseau lors du chargement de la bannière.", "erreur");
  }
}

async function publierBanniere() {
  const msg = banniereMessage.value.trim();
  if (!msg) return;
  try {
    const rep = await appelApi("/api/admin/banniere", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: msg, type: banniereType.value }),
    });
    const corps = await lireJson(rep);
    if (rep.ok) {
      afficherToast("Bannière publiée.", "succes");
      await chargerBanniereActive();
      await chargerBanniere();
    } else {
      afficherToast(corps.erreur || "Erreur lors de la publication.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau lors de la publication.", "erreur");
  }
}

async function effacerBanniere() {
  try {
    const rep = await appelApi("/api/admin/banniere", { method: "DELETE" });
    const corps = await lireJson(rep);
    if (rep.ok) {
      afficherToast("Bannière effacée.", "succes");
      await chargerBanniereActive();
      await chargerBanniere();
    } else {
      afficherToast(corps.erreur || "Erreur lors de l'effacement.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau lors de l'effacement.", "erreur");
  }
}

onMounted(chargerBanniereActive);
</script>

<style scoped>
.communication-exceptionnelle {
  margin-top: 2.5rem;
  padding-top: 1.5rem;
  border-top: 2px solid var(--danger);
}

.form-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.75rem;
}
</style>

<style scoped>
.bouton-danger-contour {
  background: none;
  border: 1px solid var(--danger);
  color: var(--danger-text);
}

.bouton-danger-contour:hover {
  background: var(--danger-bg);
}
</style>
