<template>
  <div class="app-layout">
    <SidebarNav
      :nav-principal="navPrincipal"
      :nav-secondaire="navSecondaire"
      :nav-footer="navFooter"
      :nb-nouveau="nbNouveau"
      :nom-utilisateur="nomUtilisateur"
      :est-admin="estAdmin"
    />
    <main id="contenu-principal" class="main-content">
      <BanniereSysteme :banniere="banniere" @update:banniere="banniere = $event" />
      <RouterView />
    </main>
  </div>

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

  <ToastContainer />
</template>

<script setup>
import { ref, computed, watchEffect, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import SidebarNav from './components/layout/SidebarNav.vue'
import BottomNav from './components/layout/BottomNav.vue'
import DrawerNav from './components/layout/DrawerNav.vue'
import BanniereSysteme from './components/layout/BanniereSysteme.vue'
import ToastContainer from './components/layout/ToastContainer.vue'
import { useTheme } from './composables/useTheme.js'
import { useSession } from './composables/useSession.js'

useTheme()

const { session, chargerSession } = useSession()
const route = useRoute()

const drawerOuvert = ref(false)
const banniere = ref(null)

const navPrincipal = [
  { href: '/', label: 'Accueil', icone: 'icon-home' },
  { href: '/spectacles', label: 'Mes spectacles', icone: 'icon-ticket' },
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

// Fermer le drawer à chaque navigation
watchEffect(() => {
  void route.path
  drawerOuvert.value = false
})

// Badge PWA
watchEffect(() => {
  if (!('setAppBadge' in navigator)) return
  const n = nbNouveau.value
  if (n > 0) {
    navigator.setAppBadge(n)
  } else {
    navigator.clearAppBadge()
  }
})

onMounted(async () => {
  await chargerSession()
  await chargerBanniere()
})

async function chargerBanniere() {
  try {
    const rep = await fetch('/api/banniere/json')
    banniere.value = rep.ok ? await rep.json() : null
  } catch (_) {}
}
</script>
