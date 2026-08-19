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
