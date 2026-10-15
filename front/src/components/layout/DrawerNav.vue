<template>
  <dialog
    id="dialog-nav-plus"
    ref="dialogRef"
    aria-label="Menu supplémentaire"
    aria-modal="true"
    @close="$emit('fermer')"
  >
    <div ref="handleRef" class="dialog-nav-poignee" aria-hidden="true"></div>
    <div class="dialog-nav-contenu">
      <a
        v-for="item in navSecondaire"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        :aria-current="estActif(item.href) ? 'page' : undefined"
        @click.prevent="naviguerEtFermer(item.href)"
      >
        <svg class="icone" aria-hidden="true">
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.label }}</span>
      </a>
      <hr class="dialog-nav-sep" />
      <a
        v-if="estAdmin"
        href="/admin"
        :class="{ actif: estActif('/admin') }"
        :aria-current="estActif('/admin') ? 'page' : undefined"
        @click.prevent="naviguerEtFermer('/admin')"
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
        @click.prevent="naviguerEtFermer(item.href)"
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
      <hr class="dialog-nav-sep" />
      <form
        action="/api/auth/logout"
        method="post"
        @submit.prevent="deconnecter"
      >
        <button type="submit" class="bouton bouton-texte bouton-petit">
          Déconnexion
        </button>
      </form>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: "DrawerNav" });

import { ref, watch, onMounted, onBeforeUnmount } from "vue";
import { useNavigation } from "../../composables/useNavigation.js";
import { useDeconnexion } from "../../composables/useDeconnexion.js";

const props = defineProps({
  ouvert: { type: Boolean, default: false },
  navSecondaire: { type: Array, default: () => [] },
  navFooter: { type: Array, default: () => [] },
  changelogNonVu: { type: Boolean, default: false },
  estAdmin: { type: Boolean, default: false },
});

defineEmits(["fermer"]);

const dialogRef = ref(null);
const handleRef = ref(null);
const { naviguer, estActif } = useNavigation();
const { deconnecter } = useDeconnexion();

watch(
  () => props.ouvert,
  (valeur) => {
    if (!dialogRef.value) return;
    if (valeur) {
      dialogRef.value.showModal();
      enregistrerSwipe();
    } else {
      dialogRef.value.close();
      retirerSwipe();
    }
  },
);

// Fermeture synchrone, avant naviguer() : un close() différé par setTimeout(0) s'exécute
// pendant la capture d'état de document.startViewTransition (utilisé par naviguer() sur les
// navigateurs compatibles), ce qui fait avorter silencieusement la transition — router.push()
// ne résout alors jamais et la navigation reste bloquée sur la page courante.
function naviguerEtFermer(href) {
  dialogRef.value?.close();
  naviguer(href);
}

// Un clic sur le fond (hors du contenu) ferme le menu ; Échap le ferme nativement. Écouteur
// posé par script : le dialogue n'est pas un élément interactif au sens du gabarit.
function clicBackdrop(e) {
  if (e.target === dialogRef.value) {
    dialogRef.value.close();
  }
}
onMounted(() => dialogRef.value?.addEventListener("click", clicBackdrop));
onBeforeUnmount(() =>
  dialogRef.value?.removeEventListener("click", clicBackdrop),
);

let touchDepart = 0;
let swipeAbortController = null;

// Écouteurs posés sur la poignée uniquement, pas sur le dialog entier : un touchend sur un
// lien (bulle jusqu'au dialog) pouvait sinon être interprété comme un swipe et fermer le menu
// pendant le clic, rendant la navigation vers ce lien intermittente.
//
// AbortController plutôt que { once: true } seul : un drawer ouvert puis refermé sans swipe
// abouti (backdrop, navigation) ne déclenche jamais les écouteurs "once" — ils s'accumulaient
// sur la poignée à chaque ouverture. retirerSwipe() les annule explicitement à la fermeture,
// quel que soit son déclencheur.
function enregistrerSwipe() {
  const poignee = handleRef.value;
  if (!poignee) return;

  swipeAbortController = new AbortController();
  const { signal } = swipeAbortController;

  poignee.addEventListener(
    "touchstart",
    (e) => {
      touchDepart = e.touches[0].clientY;
    },
    { passive: true, signal },
  );
  poignee.addEventListener(
    "touchend",
    (e) => {
      if (e.changedTouches[0].clientY - touchDepart > 80)
        dialogRef.value?.close();
    },
    { passive: true, signal },
  );
}

function retirerSwipe() {
  swipeAbortController?.abort();
  swipeAbortController = null;
}
</script>

<style scoped>
.dialog-nav-poignee {
  width: 2.5rem;
  height: 0.25rem;
  background: var(--border);
  border-radius: var(--radius-pill);
  margin: var(--espace-6) auto var(--espace-2);
}

.dialog-nav-contenu {
  padding: var(--espace-2) var(--espace-4) var(--espace-12);
  display: flex;
  flex-direction: column;
  gap: var(--espace-1);
}

.dialog-nav-contenu a {
  display: flex;
  align-items: center;
  gap: var(--espace-5);
  padding: var(--espace-6) var(--espace-8);
  min-height: 44px;
  border-radius: var(--radius);
  color: var(--fg-muted);
  font-size: var(--texte-l);
  font-weight: 500;
  position: relative;
  transition:
    background 0.15s,
    color 0.15s;
}

.dialog-nav-sep {
  margin: var(--espace-2) 0;
  border-color: var(--border);
}
</style>
