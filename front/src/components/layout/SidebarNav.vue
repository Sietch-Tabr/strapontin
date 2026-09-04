<template>
  <nav class="barre-laterale" aria-label="Navigation principale">
    <div class="barre-laterale-logo">
      <a href="/" @click.prevent="naviguer('/')">Strapontin</a>
    </div>
    <div class="barre-laterale-nav">
      <a
        v-for="item in navPrincipal"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        :aria-current="estActif(item.href) ? 'page' : undefined"
        @click.prevent="naviguer(item.href)"
      >
        <svg class="icone" aria-hidden="true">
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.label }}</span>
        <span
          v-if="item.href === '/' && nbNouveau > 0"
          class="nav-badge nav-badge--barre-laterale"
          :aria-label="nbNouveau + ' nouveaux concerts'"
        >{{ nbNouveau > 99 ? '99+' : nbNouveau }}</span>
      </a>
      <hr />
      <a
        v-for="item in navSecondaire"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        :aria-current="estActif(item.href) ? 'page' : undefined"
        @click.prevent="naviguer(item.href)"
      >
        <svg class="icone" aria-hidden="true">
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.label }}</span>
      </a>
    </div>
    <div class="barre-laterale-pied">
      <a
        v-if="estAdmin"
        href="/admin"
        :class="{ actif: estActif('/admin') }"
        :aria-current="estActif('/admin') ? 'page' : undefined"
        @click.prevent="naviguer('/admin')"
      >
        <svg class="icone" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-shield-check" />
        </svg>
        Admin
      </a>
      <a
        v-for="item in navFooter"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        :aria-current="estActif(item.href) ? 'page' : undefined"
        @click.prevent="naviguer(item.href)"
      >
        <svg
          class="icone"
          :class="{ 'icone--nouveaute-actif': item.href === '/changelog' && changelogNonVu }"
          aria-hidden="true"
        >
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.label }}</span>
        <span
          v-if="item.href === '/changelog' && changelogNonVu"
          class="nav-badge--point nav-badge--point-pulsation"
          aria-label="Nouveautés non lues"
        ></span>
      </a>
      <hr />
      <form action="/api/auth/logout" method="post" @submit.prevent="deconnecter">
        <button type="submit" class="bouton-deconnexion">Déconnexion</button>
      </form>
    </div>
  </nav>
</template>

<script setup>
defineOptions({ name: 'SidebarNav' })

import { useNavigation } from '../../composables/useNavigation.js'
import { useDeconnexion } from '../../composables/useDeconnexion.js'

const props = defineProps({
  navPrincipal: { type: Array, default: () => [] },
  navSecondaire: { type: Array, default: () => [] },
  navFooter: { type: Array, default: () => [] },
  nbNouveau: { type: Number, default: 0 },
  changelogNonVu: { type: Boolean, default: false },
  estAdmin: { type: Boolean, default: false },
})

const { naviguer, estActif } = useNavigation()
const { deconnecter } = useDeconnexion()
</script>
