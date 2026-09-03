<template>
  <a href="#contenu-principal" class="lien-evitement">Aller au contenu</a>
  <template v-if="!estPageAuth">
    <div class="app-layout">
      <SidebarNav
        v-if="!estMobile"
        :nav-principal="navPrincipal"
        :nav-secondaire="navSecondaire"
        :nav-footer="navFooter"
        :nb-nouveau="nbNouveau"
        :changelog-non-vu="changelogNonVu"
        :est-admin="estAdmin"
      />
      <main id="contenu-principal" class="main-content">
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
  <RouterView v-else />

  <ToastContainer />
  <OnboardingModal />
  <InstallPwaBanner />

  <Transition name="overlay-maj">
    <div v-if="miseAJourEnCours" class="overlay-mise-a-jour" role="status" aria-label="Mise à jour en cours">
      <img src="/images/icon-192.png" alt="" class="overlay-logo" />
      <p class="overlay-texte">Mise à jour en cours…</p>
    </div>
  </Transition>
</template>

<script setup>
defineOptions({ name: 'App' })

import { ref, computed, watchEffect, watch, onMounted } from 'vue'

const miseAJourEnCours = ref(false)
import { useRoute, useRouter } from 'vue-router'
import SidebarNav from './components/layout/SidebarNav.vue'
import BottomNav from './components/layout/BottomNav.vue'
import DrawerNav from './components/layout/DrawerNav.vue'
import BanniereSysteme from './components/layout/BanniereSysteme.vue'
import ToastContainer from './components/layout/ToastContainer.vue'
import OnboardingModal from './components/layout/OnboardingModal.vue'
import InstallPwaBanner from './components/layout/InstallPwaBanner.vue'
import { chargerBanniere } from './composables/useBanniere.js'
import { verifierOnboarding } from './composables/useOnboarding.js'
import { useTheme } from './composables/useTheme.js'
import { useSession } from './composables/useSession.js'
import { useEstMobile } from './composables/useBreakpoint.js'
import { useToasts } from './composables/useToasts.js'
import { ecouterMiseAJourServiceWorker } from './composables/useMiseAJourServiceWorker.js'

useTheme()
const estMobile = useEstMobile()

const { session, chargerSession } = useSession()
const route = useRoute()
const router = useRouter()
const { afficherToast, fermerToast } = useToasts()

const drawerOuvert = ref(false)

const estPageAuth = computed(() => !!route.meta.authPage)

const navPrincipal = [
  { href: '/', label: 'Accueil', icone: 'icon-home' },
  { href: '/spectacles', label: 'Mes spectacles', icone: 'icon-calendar-heart' },
  { href: '/decouverte', label: 'Découverte', icone: 'icon-compass' },
]
const navSecondaire = [
  { href: '/artistes', label: 'Mes artistes', icone: 'icon-music' },
  { href: '/salles', label: 'Mes salles', icone: 'icon-building-2' },
]
const navFooter = [
  { href: '/parametres', label: 'Paramètres', icone: 'icon-settings' },
  { href: '/aide', label: 'Aide', icone: 'icon-help-circle' },
  { href: '/changelog', label: 'Nouveautés', icone: 'icon-sparkles' },
]

const nbNouveau = computed(() => session.value?.nbNouveau ?? 0)
const estAdmin = computed(() => session.value?.role === 'admin')

// badge "nouveautés" — changelog_version_vue pilote désormais un indicateur visuel,
// complétant l'intention initiale du mécanisme (jusque là une écriture sans aucun lecteur).
const changelogNonVu = ref(false)
async function chargerChangelogNonVu() {
  try {
    const rep = await fetch('/api/changelog/nouveaute')
    if (rep.ok) changelogNonVu.value = (await rep.json()).nonVu
  } catch (e) {
    console.error('Échec chargement statut changelog', e)
  }
}

// La toute première résolution de route (depuis START_LOCATION, "/") déclenche déjà ce
// watcher — pas seulement les navigations réelles de l'utilisateur. Si un composant de page lazy
// (ex. SpectaclesPage) met du temps à charger, cette résolution initiale peut arriver après qu'un
// clic sur "Plus" ait déjà ouvert le drawer (drawerOuvert = true), le refermant aussitôt sans
// qu'aucune navigation réelle n'ait eu lieu — reproductible sur mobile-chrome (CPU throttling
// d'émulation mobile, course plus fréquente). Le premier appel n'est jamais une navigation
// utilisateur : il ne doit pas réinitialiser drawerOuvert.
let navigationInitiale = true
watch(() => route.path, (chemin) => {
  if (!navigationInitiale) drawerOuvert.value = false
  navigationInitiale = false
  if (chemin === '/changelog') changelogNonVu.value = false
})

watchEffect(() => {
  if (!('setAppBadge' in navigator)) return
  const n = nbNouveau.value
  if (n > 0) {
    navigator.setAppBadge(n)
  } else {
    navigator.clearAppBadge()
  }
})

watch(estPageAuth, (nouveau, ancien) => {
  if (ancien && !nouveau) {
    chargerSession()
    chargerBanniere()
    verifierOnboarding()
    chargerChangelogNonVu()
  }
})

onMounted(async () => {
  await chargerSession()
  chargerBanniere()
  chargerChangelogNonVu()
  verifierOnboarding()
  if ('serviceWorker' in navigator) {
    ecouterMiseAJourServiceWorker(miseAJourEnCours, navigator.serviceWorker, {
      naviguerVersAncre: (url) => router.push(url),
    })
  }

  let idToastHorsLigne = null
  // si la PWA démarre alors que l'appareil est déjà hors-ligne (JS chargé depuis le cache
  // du service worker, mais réseau absent), aucun événement 'offline' ne se déclenche jamais —
  // les listeners de transition ci-dessous ne couvrent que les changements d'état, pas l'état
  // initial. Vérifier navigator.onLine au montage pour afficher le toast immédiatement.
  if (!navigator.onLine) {
    idToastHorsLigne = afficherToast('Vous êtes hors ligne.', 'erreur')
  }
  window.addEventListener('offline', () => {
    idToastHorsLigne = afficherToast('Vous êtes hors ligne.', 'erreur')
  })
  window.addEventListener('online', () => {
    if (idToastHorsLigne !== null) {
      fermerToast(idToastHorsLigne)
      idToastHorsLigne = null
    }
    afficherToast('Connexion rétablie.', 'succes')
    chargerSession()
  })
})

</script>
