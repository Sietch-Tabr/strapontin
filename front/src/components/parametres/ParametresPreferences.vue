<template>
  <section id="preferences" class="parametres-section">
    <h2 class="lecteur-ecran">Préférences</h2>

    <EtatChargement v-if="chargement" />
    <template v-else>
      <div class="param-bloc">
        <h3 class="param-libelle">Départements de découverte</h3>
        <p class="param-aide">
          Séparés par des virgules (ex. : 75, 92, 93). Utilisés par la page
          Découverte. Pour la Corse, utilisez 2A ou 2B.
        </p>
        <form class="form-inline" @submit.prevent="sauvegarderDepartements">
          <input
            v-model="departements"
            name="departements"
            type="text"
            placeholder="75, 92, 93…"
            autocomplete="off"
            maxlength="1000"
          />
          <button type="submit" class="bouton bouton-secondaire">
            Enregistrer
          </button>
        </form>
      </div>

      <div class="param-bloc">
        <h3 id="label-filtre-disponibles" class="param-libelle">
          Concerts disponibles uniquement
        </h3>
        <p class="param-aide">
          Masque les concerts dont la billetterie est fermée (annulés,
          complets…) sur l'accueil et Mes spectacles.
        </p>
        <form class="form-bascule">
          <label class="bascule bascule--succes">
            <input
              v-model="filtreDisponibles"
              type="checkbox"
              aria-labelledby="label-filtre-disponibles"
              @change="sauvegarderFiltres"
            />
            <span class="bascule-curseur"></span>
          </label>
        </form>
      </div>

      <div class="param-bloc">
        <h3 class="param-libelle">Thème de l'interface</h3>
        <div class="theme-choix" aria-label="Choisir le thème">
          <button
            v-for="opt in optionsTheme"
            :key="opt.valeur"
            type="button"
            class="bouton bouton-theme"
            :class="{ actif: theme === opt.valeur }"
            :aria-pressed="theme === opt.valeur ? 'true' : 'false'"
            @click="changerTheme(opt.valeur)"
          >
            {{ opt.label }}
          </button>
        </div>
        <p class="param-aide">
          Le mode Automatique suit la préférence de votre appareil.
        </p>
      </div>
    </template>
  </section>
</template>

<script setup>
defineOptions({ name: "ParametresPreferences" });

import { ref, onMounted } from "vue";
import { useToasts } from "../../composables/useToasts.js";
import { useTheme } from "../../composables/useTheme.js";
import EtatChargement from "../EtatChargement.vue";

const { afficherToast } = useToasts();
const { enregistrerTheme, lireThemeCookie } = useTheme();

const chargement = ref(true);
const departements = ref("");
const filtreDisponibles = ref(false);
const theme = ref("auto");
const optionsTheme = [
  { valeur: "auto", label: "Automatique" },
  { valeur: "clair", label: "Clair" },
  { valeur: "sombre", label: "Sombre" },
];

async function charger() {
  try {
    const rep = await fetch("/api/user/config");
    if (rep.ok) {
      const config = await rep.json();
      departements.value = config.departements?.join(", ") ?? "";
      filtreDisponibles.value = config.filtreDisponibles ?? false;
    }
    theme.value = lireThemeCookie() || "auto";
  } catch (_) {
    afficherToast("Erreur lors du chargement des préférences.", "erreur");
  } finally {
    chargement.value = false;
  }
}

async function sauvegarderDepartements() {
  try {
    const rep = await fetch("/api/user/config/departements", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ departements: departements.value }),
    });
    const corps = await rep.json();
    if (rep.ok) {
      if (corps.ignores?.length) {
        afficherToast(
          "Codes ignorés car non reconnus : " + corps.ignores.join(", "),
          "avertissement",
        );
      } else {
        afficherToast("Départements enregistrés.", "succes");
      }
    } else {
      afficherToast(corps.erreur || "Erreur.", "erreur");
    }
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

async function sauvegarderFiltres() {
  try {
    const rep = await fetch("/api/user/config/filtres", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filtreDisponibles: filtreDisponibles.value }),
    });
    if (!rep.ok)
      afficherToast("Erreur lors de l'enregistrement du filtre.", "erreur");
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

async function changerTheme(valeur) {
  theme.value = valeur;
  enregistrerTheme(valeur);
  try {
    const rep = await fetch("/api/user/config/theme", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ valeur }),
    });
    if (!rep.ok)
      afficherToast("Erreur lors de l'enregistrement du thème.", "erreur");
  } catch {
    afficherToast("Erreur réseau.", "erreur");
  }
}

onMounted(charger);
</script>

<style scoped>
.theme-choix {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.bouton-theme {
  border: 1px solid var(--border);
  background: none;
  color: var(--fg-muted);
  padding: 0.5rem 1rem;
  border-radius: var(--radius);
  cursor: pointer;
  font-size: 0.875rem;
  transition:
    background 0.15s,
    color 0.15s,
    border-color 0.15s;
}

.bouton-theme.actif {
  background: var(--fg);
  color: var(--bg);
  border-color: var(--fg);
}

.bouton-theme:hover {
  color: var(--fg);
  border-color: var(--fg-muted);
}

.form-bascule {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.bascule {
  display: inline-block;
  position: relative;
  width: 2.5rem;
  height: 1.5rem;
}

.bascule input {
  opacity: 0;
  width: 0;
  height: 0;
  position: absolute;
}

.bascule-curseur {
  position: absolute;
  inset: 0;
  background: var(--border);
  border-radius: 9999px;
  transition: background 0.2s;
  cursor: pointer;
}

.bascule-curseur::before {
  content: "";
  position: absolute;
  left: 0.1875rem;
  top: 0.1875rem;
  width: 1.125rem;
  height: 1.125rem;
  background: white;
  border-radius: 50%;
  transition: transform 0.2s;
}

.bascule input:checked + .bascule-curseur {
  background: var(--primary);
}

.bascule input:checked + .bascule-curseur::before {
  transform: translateX(1rem);
}

.bascule--succes input:checked + .bascule-curseur {
  background: var(--succes);
}
</style>
