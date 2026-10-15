<template>
  <div class="etat-chargement" role="status" aria-live="polite">
    <svg
      class="roue-chargement"
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      aria-hidden="true"
    >
      <path d="M21 12a9 9 0 1 1-6.219-8.56" />
    </svg>
    {{ texte }}
  </div>
</template>

<script setup>
defineOptions({ name: "EtatChargement" });

defineProps({
  texte: { type: String, default: "Chargement…" },
});
</script>

<style scoped>
.etat-chargement {
  display: flex;
  align-items: center;
  gap: var(--espace-4);
  color: var(--fg-muted);
  padding: var(--espace-8) 0;
}
</style>
