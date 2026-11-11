<template>
  <span v-if="statut === 'cancelled'" class="badge badge-annule">Annulé</span>
  <span
    v-if="statut === 'rescheduled' || statut === 'postponed'"
    class="badge badge-reporte"
    >Reporté</span
  >
  <span v-if="statut === 'offsale'" class="badge badge-complet">Complet</span>
</template>

<script setup>
defineOptions({ name: "BadgesStatutConcert" });

// Badges d'un concert annulé, reporté ou complet, communs aux listes Découverte et Spectacles.
defineProps({
  statut: { type: String, default: "" },
});
</script>
