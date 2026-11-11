<template>
  <td data-label="Où" :class="{ 'cel-vide': !salle && !ville }">
    <span v-if="salle">{{ salle }}</span>
    <span v-if="salle && ville" class="concert-separateur"> · </span>
    <span v-if="ville" class="concert-ville">{{ ville }}</span>
  </td>
</template>

<script setup>
defineOptions({ name: "CelluleOuConcert" });

// Cellule « salle · ville » d'une ligne de concert, commune aux listes Découverte et Spectacles.
defineProps({
  salle: { type: String, default: "" },
  ville: { type: String, default: "" },
});
</script>
