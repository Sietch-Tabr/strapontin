<template>
  <a href="#contenu-principal" class="lien-evitement">Aller au contenu</a>
  <template v-if="!estPageAuth">
    <div class="structure-app">
      <SidebarNav
        v-if="!estMobile"
        :nav-principal="navPrincipal"
        :nav-secondaire="navSecondaire"
        :nav-footer="navFooter"
        :nb-nouveau="nbNouveau"
        :changelog-non-vu="changelogNonVu"
        :est-admin="estAdmin"
      />
      <main id="contenu-principal" class="contenu-principal">
        <BanniereIncident />
        <BanniereSysteme />
        <RouterView />
      </main>
    </div>

    <template v-if="estMobile">
      <BottomNav
        :nav-principal="navPrincipal"
        :nb-nouveau="nbNouveau"
        :drawer-ouvert="drawerOuvert"
        @ouvrir-drawer="drawerOuvert = true"
      />
      <DrawerNav
        :ouvert="drawerOuvert"
        :nav-secondaire="navSecondaire"
        :nav-footer="navFooter"
        :changelog-non-vu="changelogNonVu"
        :est-admin="estAdmin"
        @fermer="drawerOuvert = false"
      />
    </template>
  </template>
  <template v-else>
    <BanniereIncident />
    <RouterView />
  </template>

  <ToastContainer />
  <OnboardingModal />
  <InstallPwaBanner />

  <Transition name="calque-maj">
    <div
      v-if="miseAJourEnCours"
      class="calque-mise-a-jour"
      role="status"
      aria-label="Mise à jour en cours"
    >
      <img src="/images/icon-192.png" alt="" class="calque-logo" />
      <p class="calque-texte">Mise à jour en cours…</p>
    </div>
  </Transition>
</template>

<script setup>
import { appelApi, lireJson } from "./utils/api.js";
defineOptions({ name: "App" });

import { ref, computed, watchEffect, watch, onMounted } from "vue";

const miseAJourEnCours = ref(false);
import { useRoute, useRouter } from "vue-router";
import SidebarNav from "./components/layout/SidebarNav.vue";
import BottomNav from "./components/layout/BottomNav.vue";
import DrawerNav from "./components/layout/DrawerNav.vue";
import BanniereSysteme from "./components/layout/BanniereSysteme.vue";
import BanniereIncident from "./components/layout/BanniereIncident.vue";
import ToastContainer from "./components/layout/ToastContainer.vue";
import OnboardingModal from "./components/layout/OnboardingModal.vue";
import InstallPwaBanner from "./components/layout/InstallPwaBanner.vue";
import { chargerBanniere } from "./composables/useBanniere.js";
import { verifierOnboarding } from "./composables/useOnboarding.js";
import { useTheme } from "./composables/useTheme.js";
import { useSession } from "./composables/useSession.js";
import { useEstMobile } from "./composables/useBreakpoint.js";
import { useToasts } from "./composables/useToasts.js";
import { ecouterMiseAJourServiceWorker } from "./composables/useMiseAJourServiceWorker.js";

useTheme();
const estMobile = useEstMobile();

const { session, chargerSession } = useSession();
const route = useRoute();
const router = useRouter();
const { afficherToast, fermerToast } = useToasts();

const drawerOuvert = ref(false);

const estPageAuth = computed(() => !!route.meta.authPage);

const navPrincipal = [
  { href: "/", label: "Accueil", icone: "icon-home" },
  {
    href: "/spectacles",
    label: "Mes spectacles",
    icone: "icon-calendar-heart",
  },
  { href: "/decouverte", label: "Découverte", icone: "icon-compass" },
];
const navSecondaire = [
  { href: "/artistes", label: "Mes artistes", icone: "icon-music" },
  { href: "/salles", label: "Mes salles", icone: "icon-building-2" },
];
const navFooter = [
  { href: "/parametres", label: "Paramètres", icone: "icon-settings" },
  { href: "/aide", label: "Aide", icone: "icon-help-circle" },
  { href: "/changelog", label: "Nouveautés", icone: "icon-sparkles" },
];

const nbNouveau = computed(() => session.value?.nbNouveau ?? 0);
const estAdmin = computed(() => session.value?.role === "admin");

// badge "nouveautés" — changelog_version_vue pilote désormais un indicateur visuel,
// complétant l'intention initiale du mécanisme (jusque là une écriture sans aucun lecteur).
const changelogNonVu = ref(false);
async function chargerChangelogNonVu() {
  try {
    const rep = await appelApi("/api/changelog/nouveaute");
    if (rep.ok) changelogNonVu.value = (await lireJson(rep)).nonVu;
  } catch (e) {
    console.error("Échec chargement statut changelog", e);
  }
}

// La toute première résolution de route (depuis START_LOCATION, "/") déclenche déjà ce
// watcher — pas seulement les navigations réelles de l'utilisateur. Si un composant de page lazy
// (ex. SpectaclesPage) met du temps à charger, cette résolution initiale peut arriver après qu'un
// clic sur "Plus" ait déjà ouvert le drawer (drawerOuvert = true), le refermant aussitôt sans
// qu'aucune navigation réelle n'ait eu lieu — reproductible sur mobile-chrome (CPU throttling
// d'émulation mobile, course plus fréquente). Le premier appel n'est jamais une navigation
// utilisateur : il ne doit pas réinitialiser drawerOuvert.
let navigationInitiale = true;
watch(
  () => route.path,
  (chemin) => {
    if (!navigationInitiale) drawerOuvert.value = false;
    navigationInitiale = false;
    if (chemin === "/changelog") changelogNonVu.value = false;
  },
);

watchEffect(() => {
  if (!("setAppBadge" in navigator)) return;
  const n = nbNouveau.value;
  if (n > 0) {
    navigator.setAppBadge(n);
  } else {
    navigator.clearAppBadge();
  }
});

watch(estPageAuth, (nouveau, ancien) => {
  if (ancien && !nouveau) {
    chargerSession();
    chargerBanniere();
    verifierOnboarding();
    chargerChangelogNonVu();
  }
});

onMounted(async () => {
  // Le montage précède la résolution de la route initiale (route.meta encore vide) :
  // sans cette attente, une page publique appelait des endpoints authentifiés (401).
  await router.isReady();
  if (!estPageAuth.value) {
    await chargerSession();
    chargerBanniere();
    chargerChangelogNonVu();
    verifierOnboarding();
  }
  if ("serviceWorker" in navigator) {
    ecouterMiseAJourServiceWorker(miseAJourEnCours, navigator.serviceWorker, {
      naviguerVersAncre: (url) => router.push(url),
    });
  }

  let idToastHorsLigne = null;
  // si la PWA démarre alors que l'appareil est déjà hors-ligne (JS chargé depuis le cache
  // du service worker, mais réseau absent), aucun événement 'offline' ne se déclenche jamais —
  // les listeners de transition ci-dessous ne couvrent que les changements d'état, pas l'état
  // initial. Vérifier navigator.onLine au montage pour afficher le toast immédiatement.
  if (!navigator.onLine) {
    idToastHorsLigne = afficherToast("Vous êtes hors ligne.", "erreur");
  }
  window.addEventListener("offline", () => {
    idToastHorsLigne = afficherToast("Vous êtes hors ligne.", "erreur");
  });
  window.addEventListener("online", () => {
    if (idToastHorsLigne !== null) {
      fermerToast(idToastHorsLigne);
      idToastHorsLigne = null;
    }
    afficherToast("Connexion rétablie.", "succes");
    if (!estPageAuth.value) chargerSession();
  });
});
</script>

<style scoped>
.calque-mise-a-jour {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--bg);
}

.calque-logo {
  width: 96px;
  height: 96px;
  border-radius: 20px;
  animation: pulsation 1s ease-in-out infinite;
}

.calque-texte {
  margin-top: 1.25rem;
  font-size: 0.875rem;
  color: var(--texte-secondaire);
}

.structure-app {
  display: flex;
  min-height: 100vh;
}

.contenu-principal {
  margin-left: 0;
  flex: 1;
  padding: 1rem;
  padding-bottom: calc(var(--nav-h) + env(safe-area-inset-bottom) + 1rem);
  max-width: 1100px;
  view-transition-name: zone-principale;
  overflow-x: clip;
}

@media (width >= 1025px) {
  .contenu-principal {
    margin-left: max(
      var(--sidebar-w),
      calc((100vw + var(--sidebar-w) - 1100px) / 2)
    );
    padding: 2rem;
  }
}

.lien-evitement {
  position: absolute;
  left: -9999px;
}

.lien-evitement:focus {
  left: 1rem;
  top: 1rem;
  z-index: 9999;
  background: var(--bg);
  padding: 0.5rem 1rem;
  border-radius: 4px;
  outline: 2px solid var(--primary);
}
</style>
