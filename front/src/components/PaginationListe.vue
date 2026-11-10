<template>
  <div v-if="totalPages > 1" class="pagination">
    <template v-if="admin">
      <button
        type="button"
        class="bouton bouton-secondaire bouton-petit"
        :disabled="page === 0"
        @click="emit('changer', page - 1)"
      >
        ← Précédent
      </button>
      <span
        >Page {{ page + 1 }} / {{ totalPages }} ({{ total }}
        {{ libelleTotal }})</span
      >
      <button
        type="button"
        class="bouton bouton-secondaire bouton-petit"
        :disabled="page >= totalPages - 1"
        @click="emit('changer', page + 1)"
      >
        Suivant →
      </button>
    </template>
    <template v-else>
      <button
        v-if="page > 0"
        type="button"
        class="bouton bouton-secondaire"
        @click="emit('changer', page - 1)"
      >
        Précédent
      </button>
      <span>Page {{ page + 1 }} / {{ totalPages }}</span>
      <button
        v-if="page + 1 < totalPages"
        type="button"
        class="bouton bouton-secondaire"
        @click="emit('changer', page + 1)"
      >
        Suivant
      </button>
    </template>
  </div>
</template>

<script setup>
defineOptions({ name: "PaginationListe" });

// Pagination partagée par les listes de l'administration (flèches, boutons désactivés aux
// extrémités, total) et par les pages publiques (boutons masqués aux extrémités).
defineProps({
  page: { type: Number, required: true },
  totalPages: { type: Number, required: true },
  admin: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  libelleTotal: { type: String, default: "" },
});
const emit = defineEmits(["changer"]);
</script>
