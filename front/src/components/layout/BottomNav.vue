<template>
  <nav id="nav-bas" class="nav-bas" aria-label="Navigation principale">
    <div class="nav-bas-elements">
      <a
        v-for="item in navPrincipal"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        :aria-current="estActif(item.href) ? 'page' : undefined"
        @click.prevent="naviguer(item.href)"
      >
        <span v-if="item.href === '/'" class="nav-bas-badge-conteneur">
          <svg class="icone" aria-hidden="true">
            <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
          </svg>
          <span
            v-if="nbNouveau > 0"
            class="nav-badge"
            :aria-label="nbNouveau + ' nouveaux concerts'"
            >{{ nbNouveau > 99 ? "99+" : nbNouveau }}</span
          >
        </span>
        <svg v-else class="icone" aria-hidden="true">
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{
          item.href === "/spectacles" ? "Spectacles" : item.label
        }}</span>
      </a>
      <button
        id="btn-nav-plus"
        type="button"
        class="nav-bas-plus"
        :class="{ actif: estDansDrawer }"
        :aria-expanded="drawerOuvert"
        aria-controls="dialog-nav-plus"
        @click="$emit('ouvrirDrawer')"
      >
        <span class="nav-bas-badge-conteneur">
          <svg class="icone" aria-hidden="true">
            <use href="/icons/lucide-sprite.svg#icon-menu" />
          </svg>
          <span
            v-if="changelogNonVu"
            class="nav-badge--point nav-badge--point-pulsation"
            aria-label="Nouveautés non lues"
          ></span>
        </span>
        <span>Plus</span>
      </button>
    </div>
  </nav>
</template>

<script setup>
defineOptions({ name: "BottomNav" });

import { computed } from "vue";
import { useRoute } from "vue-router";
import { useNavigation } from "../../composables/useNavigation.js";

defineProps({
  navPrincipal: { type: Array, default: () => [] },
  nbNouveau: { type: Number, default: 0 },
  drawerOuvert: { type: Boolean, default: false },
  changelogNonVu: { type: Boolean, default: false },
});

defineEmits(["ouvrirDrawer"]);

const route = useRoute();
const { naviguer, estActif } = useNavigation();

const pagesDrawer = [
  "/artistes",
  "/salles",
  "/parametres",
  "/admin",
  "/aide",
  "/changelog",
];

const estDansDrawer = computed(() =>
  pagesDrawer.some((p) => route.path === p || route.path.startsWith(p + "/")),
);
</script>

<style scoped>
.nav-bas {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: calc(var(--nav-h) + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  background: var(--bg);
  border-top: 1px solid var(--border);
  z-index: 10;
  view-transition-name: barre-nav-mobile;
}

@media (width >= 1025px) {
  .nav-bas {
    height: var(--nav-h);
    padding-bottom: 0;
  }
}

.nav-bas-elements {
  display: flex;
  height: 100%;
}

.nav-bas-elements a,
.nav-bas-plus {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--espace-2);
  font-size: var(--texte-xs);
  color: var(--fg-muted);
  transition: color 0.15s;
  background: none;
  border: none;
  cursor: pointer;
  font-family: inherit;
  padding: 0;
}

.nav-bas-badge-conteneur {
  position: relative;
  display: inline-flex;
}

.nav-bas-elements a.actif,
.nav-bas-elements a:hover,
.nav-bas-plus.actif,
.nav-bas-plus:hover {
  color: var(--fg);
}

.nav-bas-elements a.actif {
  view-transition-name: nav-actif;
}
</style>
