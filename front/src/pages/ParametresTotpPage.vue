<template>
  <div>
    <div class="page-entete">
      <h1>Configurer l'authentification à deux facteurs</h1>
    </div>

    <EtatChargement v-if="chargement" />

    <div v-else-if="erreurChargement" class="alerte alerte-erreur" role="alert">
      {{ erreurChargement }}
    </div>

    <template v-else>
      <div class="contenu-largeur-etroite">
        <div class="param-bloc">
          <h2 class="param-libelle">Étape 1 — Scannez le QR code</h2>
          <p class="param-aide">
            Ouvrez votre application d'authentification (Google Authenticator,
            Aegis, etc.) et scannez le QR code ci-dessous.
          </p>

          <img
            v-if="qrCodeSvg"
            class="totp-qr"
            :src="svgDataUri"
            alt="QR code TOTP"
          />

          <details class="totp-manuel">
            <summary>Saisie manuelle (si QR indisponible)</summary>
            <p class="param-aide">Entrez ce code dans votre application :</p>
            <code class="totp-secret-code">{{ totpSecret }}</code>
            <p class="param-aide param-aide--mt">
              Ou ouvrez ce lien depuis votre téléphone :
              <a :href="otpUri" class="break-all">{{ otpUri }}</a>
            </p>
          </details>
        </div>

        <div class="param-bloc">
          <h2 class="param-libelle">Étape 2 — Confirmez</h2>
          <p class="param-aide">
            Saisissez le code à 6 chiffres affiché dans votre application pour
            valider la configuration.
          </p>

          <div
            v-if="erreurCode"
            class="alerte alerte-erreur"
            role="alert"
            aria-live="assertive"
          >
            {{ erreurCode }}
          </div>

          <form autocomplete="off" @submit.prevent="confirmer">
            <div class="form-groupe">
              <label for="code">Code de vérification</label>
              <input
                id="code"
                ref="codeRef"
                v-model="code"
                name="code"
                type="text"
                inputmode="numeric"
                pattern="[0-9]{6}"
                maxlength="6"
                autocomplete="one-time-code"
                required
              />
            </div>
            <div class="form-actions">
              <button
                type="submit"
                class="bouton bouton-primaire"
                :disabled="enCours"
              >
                Confirmer
              </button>
              <RouterLink to="/parametres#compte" class="bouton bouton-texte">
                Annuler
              </RouterLink>
            </div>
          </form>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { appelApi, lireJson } from "../utils/api.js";
defineOptions({ name: "ParametresTotpPage" });

import { ref, computed, onMounted, nextTick } from "vue";
import EtatChargement from "../components/EtatChargement.vue";
import { useRouter } from "vue-router";
import { useToasts } from "../composables/useToasts.js";

const router = useRouter();
const { afficherToast } = useToasts();

const chargement = ref(true);
const erreurChargement = ref(null);
const otpUri = ref("");
const totpSecret = ref("");
const qrCodeSvg = ref("");
const svgDataUri = computed(() =>
  qrCodeSvg.value
    ? "data:image/svg+xml;base64," +
      btoa(unescape(encodeURIComponent(qrCodeSvg.value)))
    : "",
);
const code = ref("");
const erreurCode = ref(null);
const enCours = ref(false);
const codeRef = ref(null);

async function chargerSetup() {
  chargement.value = true;
  erreurChargement.value = null;
  try {
    const rep = await appelApi("/api/totp/setup");
    if (!rep.ok) {
      erreurChargement.value = "Impossible de charger la configuration TOTP.";
      return;
    }
    const donnees = await lireJson(rep);
    otpUri.value = donnees.otpUri ?? "";
    totpSecret.value = donnees.totpSecret ?? "";
    qrCodeSvg.value = donnees.qrCodeSvg ?? "";
  } catch (_) {
    erreurChargement.value = "Erreur lors du chargement.";
  } finally {
    chargement.value = false;
  }
  // Le champ n'existe qu'une fois le chargement terminé : focus posé après son rendu.
  await nextTick();
  codeRef.value?.focus();
}

async function confirmer() {
  erreurCode.value = null;
  enCours.value = true;
  try {
    const rep = await appelApi("/api/totp/confirmer", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: code.value }),
    });
    const corps = await lireJson(rep);
    if (rep.ok) {
      afficherToast("Authentification à deux facteurs activée.", "succes");
      router.push("/parametres#compte");
    } else if (corps.erreur?.includes("Session expirée")) {
      // Regénère un nouveau secret
      code.value = "";
      await chargerSetup();
      erreurCode.value = corps.erreur;
    } else {
      erreurCode.value = corps.erreur || "Code incorrect.";
      code.value = "";
      codeRef.value?.focus();
    }
  } catch (_) {
    erreurCode.value = "Erreur lors de la confirmation.";
  } finally {
    enCours.value = false;
  }
}

onMounted(chargerSetup);
</script>

<style scoped>
.param-aide--mt {
  margin-top: var(--espace-4);
}

.totp-qr {
  width: 200px;
  height: 200px;
  margin: var(--espace-8) 0;
}

.totp-secret-code {
  display: block;
  font-size: var(--texte-xl);
  letter-spacing: 0.15em;
  padding: var(--espace-6);
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  word-break: break-all;
  margin: var(--espace-4) 0;
}

.totp-manuel {
  margin-top: var(--espace-6);
}

.totp-manuel summary {
  cursor: pointer;
  font-size: var(--texte-m);
  color: var(--fg-muted);
}
</style>
