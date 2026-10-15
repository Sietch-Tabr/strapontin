<template>
  <Transition name="banniere-glissement">
    <div
      v-if="visible && banniere"
      class="banniere-overlay"
      :class="'banniere-overlay--' + MODIFICATEURS[banniere.type]"
      :role="banniere.type === 'error' ? 'alert' : 'status'"
    >
      <svg class="banniere-icone icone" aria-hidden="true">
        <use :href="'/icons/lucide-sprite.svg#' + icone" />
      </svg>
      <span class="banniere-message">{{ banniere.message }}</span>
      <button
        type="button"
        class="bouton-icone banniere-fermer"
        aria-label="Fermer la bannière"
        @click="fermer"
      >
        <svg class="icone" aria-hidden="true">
          <use href="/icons/lucide-sprite.svg#icon-x" />
        </svg>
      </button>
      <div
        v-if="delai > 0"
        class="banniere-progression"
        :style="{ '--duree-banniere': delai + 'ms' }"
      ></div>
    </div>
  </Transition>
</template>

<script setup>
defineOptions({ name: "BanniereSysteme" });

import { computed } from "vue";
import { useBanniere } from "../../composables/useBanniere.js";

const { banniere, visible, fermer } = useBanniere();

const DELAIS = { info: 10000, warning: 20000 };
const delai = computed(() => DELAIS[banniere.value?.type] ?? 0);

const ICONES = {
  info: "icon-info",
  warning: "icon-triangle-alert",
  error: "icon-circle-x",
};
const icone = computed(() => ICONES[banniere.value?.type] ?? "icon-info");

// Types publiés par l'admin, modificateurs de style en français.
const MODIFICATEURS = {
  info: "info",
  warning: "avertissement",
  error: "erreur",
};
</script>

<style scoped>
.banniere-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.625rem 1.25rem;
  padding-top: max(0.625rem, env(safe-area-inset-top));
  font-size: 0.875rem;
  line-height: 1.5;
  border-bottom: 1px solid;
  border-radius: 0;
  overflow: hidden;
  box-shadow: var(--ombre-bandeau-haut);
}

@media (width >= 1025px) {
  .banniere-overlay {
    left: var(--sidebar-w);
    padding-top: 0.625rem;
    border-bottom-left-radius: var(--radius);
    border-bottom-right-radius: var(--radius);
    box-shadow: var(--ombre-bandeau-haut-large);
  }
}

.banniere-icone {
  width: 1rem;
  height: 1rem;
  flex-shrink: 0;
}

.banniere-message {
  flex: 1;
  white-space: pre-wrap;
}

.banniere-fermer {
  flex-shrink: 0;
}

.banniere-progression {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: currentcolor;
  opacity: 0.25;
  transform-origin: left;
  animation: reduire-progression var(--duree-banniere) linear forwards;
}

@media (prefers-reduced-motion: reduce) {
  .banniere-overlay,
  .banniere-progression {
    animation: none;
    transition: none;
  }
}
</style>
