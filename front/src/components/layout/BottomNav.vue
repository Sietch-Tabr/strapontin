<template>
  <nav id="bottom-nav" class="bottom-nav" aria-label="Navigation principale">
    <div class="bottom-nav-items">
      <a
        v-for="item in navPrincipal"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        :aria-current="estActif(item.href) ? 'page' : undefined"
        @click.prevent="naviguer(item.href)"
      >
        <span v-if="item.href === '/'" class="bottom-nav-badge-wrapper">
          <svg class="icon" aria-hidden="true">
            <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
          </svg>
          <span
            v-if="nbNouveau > 0"
            class="nav-badge"
            :aria-label="nbNouveau + ' nouveaux concerts'"
          >{{ nbNouveau > 99 ? '99+' : nbNouveau }}</span>
        </span>
        <svg v-else class="icon" aria-hidden="true">
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.href === '/spectacles' ? 'Spectacles' : item.label }}</span>
      </a>
      <button
        type="button"
        id="btn-nav-plus"
        class="bottom-nav-plus"
        :class="{ actif: estDansDrawer }"
        :aria-expanded="drawerOuvert"
        aria-controls="dialog-nav-plus"
        @click="$emit('ouvrirDrawer')"
      >
        <span class="bottom-nav-badge-wrapper">
          <svg class="icon" aria-hidden="true">
            <use href="/icons/lucide-sprite.svg#icon-menu" />
          </svg>
          <span
            v-if="changelogNonVu"
            class="nav-badge--point nav-badge--point-pulse"
            aria-label="Nouveautés non lues"
          ></span>
        </span>
        <span>Plus</span>
      </button>
    </div>
  </nav>
</template>

<script setup>
defineOptions({ name: 'BottomNav' })

import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useNavigation } from '../../composables/useNavigation.js'

const props = defineProps({
  navPrincipal: { type: Array, default: () => [] },
  nbNouveau: { type: Number, default: 0 },
  drawerOuvert: { type: Boolean, default: false },
  changelogNonVu: { type: Boolean, default: false },
})

defineEmits(['ouvrirDrawer'])

const route = useRoute()
const { naviguer, estActif } = useNavigation()

const pagesDrawer = ['/artistes', '/salles', '/parametres', '/admin', '/aide', '/changelog']

const estDansDrawer = computed(() =>
  pagesDrawer.some((p) => route.path === p || route.path.startsWith(p + '/')),
)
</script>
