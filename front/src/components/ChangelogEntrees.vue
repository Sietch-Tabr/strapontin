<template>
  <div class="changelog-entrees">
    <div v-for="(cat, i) in categories" :key="i">
      <p v-if="cat.categorie" class="changelog-categorie">{{ cat.categorie }}</p>
      <ul v-if="cat.items">
        <li v-for="(item, j) in cat.items" :key="j">{{ item }}</li>
      </ul>
      <p v-else>{{ cat.texte }}</p>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ChangelogEntrees' })

defineProps({
  categories: { type: Array, required: true },
})
</script>

<style scoped>
  .changelog-entrees {
    display: flex;
    flex-direction: column;
    gap: 1rem;
  }
  .changelog-categorie {
    font-size: 0.875rem;
    font-weight: 500;
    margin-bottom: 0.375rem;
  }
  .changelog-entrees ul {
    list-style: disc;
    padding-left: 1.25rem;
    font-size: 0.875rem;
    color: var(--fg-muted);
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
  }
</style>
