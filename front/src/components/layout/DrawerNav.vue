<template>
  <dialog
    id="dialog-nav-plus"
    ref="dialogRef"
    aria-label="Menu supplémentaire"
    aria-modal="true"
    @click="clicBackdrop"
    @close="$emit('fermer')"
  >
    <div class="dialog-nav-handle" ref="handleRef" aria-hidden="true"></div>
    <div class="dialog-nav-contenu">
      <a
        v-for="item in navSecondaire"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
        :aria-current="estActif(item.href) ? 'page' : undefined"
        @click.prevent="naviguerEtFermer(item.href)"
      >
        <svg class="icon" aria-hidden="true">
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
        :aria-current="estActif(item.href) ? 'page' : undefined"
        @click.prevent="naviguerEtFermer(item.href)"
      >
        <svg
          class="icon"
          :class="{ 'icon--nouveaute-actif': item.href === '/changelog' && changelogNonVu }"
          aria-hidden="true"
        >
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.label }}</span>
        <span
          v-if="item.href === '/changelog' && changelogNonVu"
          class="nav-badge--point nav-badge--point-pulse"
          aria-label="Nouveautés non lues"
        ></span>
      </a>
      <hr class="dialog-nav-sep" />
      <form action="/logout" method="post" @submit.prevent="deconnecter">
        <button type="submit" class="btn-deconnexion">Déconnexion</button>
      </form>
    </div>
  </dialog>
</template>

<script setup>
defineOptions({ name: 'DrawerNav' })

import { ref, watch } from 'vue'
import { useNavigation } from '../../composables/useNavigation.js'

const props = defineProps({
  ouvert: { type: Boolean, default: false },
  navSecondaire: { type: Array, default: () => [] },
  navFooter: { type: Array, default: () => [] },
  changelogNonVu: { type: Boolean, default: false },
  estAdmin: { type: Boolean, default: false },
})

const emit = defineEmits(['fermer'])

const dialogRef = ref(null)
const handleRef = ref(null)
const { naviguer, estActif } = useNavigation()

watch(
  () => props.ouvert,
  (valeur) => {
    if (!dialogRef.value) return
    if (valeur) {
      dialogRef.value.showModal()
      enregistrerSwipe()
    } else {
      dialogRef.value.close()
    }
  },
)

// Fermeture synchrone, avant naviguer() : un close() différé par setTimeout(0) s'exécute
// pendant la capture d'état de document.startViewTransition (utilisé par naviguer() sur les
// navigateurs compatibles), ce qui fait avorter silencieusement la transition — router.push()
// ne résout alors jamais et la navigation reste bloquée sur la page courante.
function naviguerEtFermer(href) {
  dialogRef.value?.close()
  naviguer(href)
}

function clicBackdrop(e) {
  if (e.target === dialogRef.value) {
    dialogRef.value.close()
  }
}

async function deconnecter() {
  await fetch('/logout', { method: 'POST' })
  window.location.href = '/login'
}

let touchDepart = 0

// Écouteurs posés sur la poignée uniquement, pas sur le dialog entier : un touchend sur un
// lien (bulle jusqu'au dialog) pouvait sinon être interprété comme un swipe et fermer le menu
// pendant le clic, rendant la navigation vers ce lien intermittente.
function enregistrerSwipe() {
  const poignee = handleRef.value
  if (!poignee) return

  const onTouchStart = (e) => {
    touchDepart = e.touches[0].clientY
  }
  const onTouchEnd = (e) => {
    if (e.changedTouches[0].clientY - touchDepart > 80) dialogRef.value?.close()
  }

  poignee.addEventListener('touchstart', onTouchStart, { passive: true, once: true })
  poignee.addEventListener('touchend', onTouchEnd, { passive: true, once: true })
}
</script>
