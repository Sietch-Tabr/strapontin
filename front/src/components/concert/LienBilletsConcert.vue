<template>
  <a
    v-if="url"
    :href="url"
    target="_blank"
    rel="noopener noreferrer"
    class="bouton bouton-secondaire bouton-petit"
    aria-label="Acheter des billets (ouvre dans un nouvel onglet)"
  >
    <svg class="icone" aria-hidden="true">
      <use href="/icons/lucide-sprite.svg#icon-ticket" />
    </svg>
  </a>
</template>

<script setup>
defineOptions({ name: "LienBilletsConcert" });

// Lien vers la billetterie d'un concert, commun aux listes Découverte et Spectacles.
defineProps({
  url: { type: String, default: "" },
});
</script>
