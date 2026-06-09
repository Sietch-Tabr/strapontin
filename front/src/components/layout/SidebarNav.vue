<template>
  <nav class="sidebar" aria-label="Navigation principale">
    <div class="sidebar-logo">
      <a href="/" @click.prevent="naviguer('/')">Strapontin</a>
    </div>
    <div class="sidebar-nav">
      <a
        v-for="item in navPrincipal"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        @click.prevent="naviguer(item.href)"
      >
        <svg class="icon" aria-hidden="true">
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.label }}</span>
        <span
          v-if="item.href === '/' && nbNouveau > 0"
          class="nav-badge nav-badge--sidebar"
          :aria-label="nbNouveau + ' nouveaux concerts'"
        >{{ nbNouveau > 99 ? '99+' : nbNouveau }}</span>
      </a>
      <hr />
      <a
        v-for="item in navSecondaire"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        @click.prevent="naviguer(item.href)"
      >
        <svg class="icon" aria-hidden="true">
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.label }}</span>
      </a>
    </div>
    <div class="sidebar-footer">
      <a
        v-if="estAdmin"
        href="/admin"
        :class="{ actif: estActif('/admin') }"
        @click.prevent="naviguer('/admin')"
      >
        <svg class="icon" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-shield-check" />
        </svg>
        Admin
      </a>
      <a
        v-for="item in navFooter"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        @click.prevent="naviguer(item.href)"
      >
        <svg class="icon" aria-hidden="true">
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.label }}</span>
      </a>
      <hr />
      <form action="/logout" method="post" @submit.prevent="deconnecter">
        <button type="submit" class="btn-deconnexion">Déconnexion</button>
      </form>
    </div>
  </nav>
</template>

<script setup>
defineOptions({ name: 'SidebarNav' })

import { useRoute } from 'vue-router'
import { useNavigation } from '../../composables/useNavigation.js'

const props = defineProps({
  navPrincipal: { type: Array, default: () => [] },
  navSecondaire: { type: Array, default: () => [] },
  navFooter: { type: Array, default: () => [] },
  nbNouveau: { type: Number, default: 0 },
  estAdmin: { type: Boolean, default: false },
})

const route = useRoute()
const { naviguer } = useNavigation()

function estActif(href) {
  if (href === '/') return route.path === '/'
  return route.path === href || route.path.startsWith(href + '/')
}

async function deconnecter() {
  await fetch('/logout', { method: 'POST' })
  window.location.href = '/login'
}
</script>
