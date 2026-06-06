<template>
  <template v-if="!estPageAuth">
    <div class="app-layout">
      <SidebarNav
        v-if="!estMobile"
        :nav-principal="navPrincipal"
        :nav-secondaire="navSecondaire"
        :nav-footer="navFooter"
        :nb-nouveau="nbNouveau"
        :nom-utilisateur="nomUtilisateur"
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
        :nom-utilisateur="nomUtilisateur"
        :est-admin="estAdmin"
        @fermer="drawerOuvert = false"
      />
    </template>
  </template>
  <RouterView v-else />

  <ToastContainer />
</template>

<script setup>
defineOptions({ name: 'App' })

import { ref, computed, watchEffect, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import SidebarNav from './components/layout/SidebarNav.vue'
import BottomNav from './components/layout/BottomNav.vue'
import DrawerNav from './components/layout/DrawerNav.vue'
import BanniereSysteme from './components/layout/BanniereSysteme.vue'
import ToastContainer from './components/layout/ToastContainer.vue'
import { chargerBanniere } from './composables/useBanniere.js'
import { useTheme } from './composables/useTheme.js'
import { useSession } from './composables/useSession.js'
import { useEstMobile } from './composables/useBreakpoint.js'

useTheme()
const estMobile = useEstMobile()

const { session, chargerSession } = useSession()
const route = useRoute()

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
const nomUtilisateur = computed(() => session.value?.nom ?? '')
const estAdmin = computed(() => session.value?.role === 'admin')

watch(() => route.path, () => {
  drawerOuvert.value = false
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
  }
})

onMounted(async () => {
  await chargerSession()
  chargerBanniere()
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      window.location.reload()
    })
  }
})

</script>
