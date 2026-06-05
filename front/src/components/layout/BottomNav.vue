<template>
  <nav id="bottom-nav" class="bottom-nav" aria-label="Navigation principale">
    <div class="bottom-nav-items">
      <a
        v-for="item in navPrincipal"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
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
        <svg class="icon" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-menu" />
        </svg>
        <span>Plus</span>
      </button>
    </div>
  </nav>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const props = defineProps({
  navPrincipal: { type: Array, default: () => [] },
  nbNouveau: { type: Number, default: 0 },
  drawerOuvert: { type: Boolean, default: false },
})

defineEmits(['ouvrirDrawer'])

const route = useRoute()
const router = useRouter()

const pagesDrawer = ['/artistes', '/salles', '/parametres', '/admin', '/aide', '/changelog']

const estDansDrawer = computed(() =>
  pagesDrawer.some((p) => route.path === p || route.path.startsWith(p + '/')),
)

function estActif(href) {
  if (href === '/') return route.path === '/'
  return route.path === href || route.path.startsWith(href + '/')
}

function naviguer(href) {
  if (router.resolve(href).matched.length === 0) {
    window.location.href = href
  } else {
    router.push(href)
  }
}
</script>
