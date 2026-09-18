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
          >{{ nbNouveau > 99 ? "99+" : nbNouveau }}</span
        >
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
          :class="{
            'icone--nouveaute-actif':
              item.href === '/changelog' && changelogNonVu,
          }"
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
      <form
        action="/api/auth/logout"
        method="post"
        @submit.prevent="deconnecter"
      >
        <button type="submit" class="bouton-deconnexion">Déconnexion</button>
      </form>
    </div>
  </nav>
</template>

<script setup>
defineOptions({ name: "SidebarNav" });

import { useNavigation } from "../../composables/useNavigation.js";
import { useDeconnexion } from "../../composables/useDeconnexion.js";

defineProps({
  navPrincipal: { type: Array, default: () => [] },
  navSecondaire: { type: Array, default: () => [] },
  navFooter: { type: Array, default: () => [] },
  nbNouveau: { type: Number, default: 0 },
  changelogNonVu: { type: Boolean, default: false },
  estAdmin: { type: Boolean, default: false },
});

const { naviguer, estActif } = useNavigation();
const { deconnecter } = useDeconnexion();
</script>

<style scoped>
.barre-laterale {
  width: var(--sidebar-w);
  background: var(--bg);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: 10;
}

.barre-laterale-logo {
  padding: 1.25rem 1rem;
  font-size: 1.125rem;
  font-weight: 700;
  border-bottom: 1px solid var(--border);
}

.barre-laterale-nav {
  flex: 1;
  padding: 0.5rem;
  overflow-y: auto;
}

.barre-laterale-nav a {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius);
  color: var(--fg-muted);
  font-size: 0.875rem;
  font-weight: 500;
  position: relative;
  transition:
    background 0.15s,
    color 0.15s;
}

.barre-laterale-nav hr {
  margin: 0.5rem 0;
  border-color: var(--border);
}

.barre-laterale-pied {
  padding: 0.5rem;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.barre-laterale-pied hr {
  margin: 0.25rem 0;
  border-color: var(--border);
}

.barre-laterale-pied a {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius);
  color: var(--fg-muted);
  font-size: 0.875rem;
  font-weight: 500;
  position: relative;
  transition:
    background 0.15s,
    color 0.15s;
}

.nav-badge--barre-laterale {
  top: 50%;
  right: 0.5rem;
  transform: translateY(-50%);
}
</style>
