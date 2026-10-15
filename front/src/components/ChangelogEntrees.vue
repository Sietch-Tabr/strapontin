<template>
  <div class="changelog-entrees">
    <div v-for="(cat, i) in categories" :key="i">
      <p v-if="cat.categorie" class="changelog-categorie">
        {{ cat.categorie }}
      </p>
      <ul v-if="cat.items">
        <li v-for="(item, j) in cat.items" :key="j">{{ item }}</li>
      </ul>
      <p v-else>{{ cat.texte }}</p>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: "ChangelogEntrees" });

defineProps({
  categories: { type: Array, required: true },
});
</script>

<style scoped>
.changelog-entrees {
  display: flex;
  flex-direction: column;
  gap: var(--espace-8);
}

.changelog-categorie {
  font-size: var(--texte-m);
  font-weight: 500;
  margin-bottom: var(--espace-3);
}

.changelog-entrees ul {
  list-style: disc;
  padding-left: var(--espace-10);
  font-size: var(--texte-m);
  color: var(--fg-muted);
  display: flex;
  flex-direction: column;
  gap: var(--espace-2);
}
</style>
