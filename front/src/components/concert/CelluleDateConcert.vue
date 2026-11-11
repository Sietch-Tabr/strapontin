<template>
  <td data-label="Date">
    <time class="concert-date" :datetime="date">{{
      formaterAvecHeure(date, heure)
    }}</time>
  </td>
</template>

<script setup>
import { formaterAvecHeure } from "../../composables/useDates.js";
defineOptions({ name: "CelluleDateConcert" });

// Cellule de date et d'heure d'une ligne de concert, commune aux listes Découverte et Spectacles.
defineProps({
  date: { type: String, required: true },
  heure: { type: String, default: null },
});
</script>
