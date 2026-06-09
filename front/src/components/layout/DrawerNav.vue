<template>
  <dialog
    id="dialog-nav-plus"
    ref="dialogRef"
    aria-label="Menu supplémentaire"
    @click="clicBackdrop"
    @close="$emit('fermer')"
  >
    <div class="dialog-nav-handle" aria-hidden="true"></div>
    <div class="dialog-nav-contenu">
      <a
        v-for="item in navSecondaire"
        :key="item.href"
        :href="item.href"
        :class="{ actif: estActif(item.href) }"
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
        @click.prevent="naviguerEtFermer(item.href)"
      >
        <svg class="icon" aria-hidden="true">
          <use :href="'/icons/lucide-sprite.svg#' + item.icone" />
        </svg>
        <span>{{ item.label }}</span>
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
import { useRoute, useRouter } from 'vue-router'

const props = defineProps({
  ouvert: { type: Boolean, default: false },
  navSecondaire: { type: Array, default: () => [] },
  navFooter: { type: Array, default: () => [] },
  estAdmin: { type: Boolean, default: false },
})

const emit = defineEmits(['fermer'])

const dialogRef = ref(null)
const route = useRoute()
const router = useRouter()

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

function estActif(href) {
  if (href === '/') return route.path === '/'
  return route.path === href || route.path.startsWith(href + '/')
}

function naviguerEtFermer(href) {
  setTimeout(() => dialogRef.value?.close(), 0)
  if (router.resolve(href).matched.length === 0) {
    window.location.href = href
  } else {
    router.push(href)
  }
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

function enregistrerSwipe() {
  const el = dialogRef.value
  if (!el) return

  const onTouchStart = (e) => {
    touchDepart = e.touches[0].clientY
  }
  const onTouchEnd = (e) => {
    if (e.changedTouches[0].clientY - touchDepart > 80) el.close()
  }

  el.addEventListener('touchstart', onTouchStart, { passive: true, once: true })
  el.addEventListener('touchend', onTouchEnd, { passive: true, once: true })
}
</script>
