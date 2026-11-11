<template>
  <label :for="id" class="lecteur-ecran">{{ libelle }}</label>
  <select :id="id" v-model="valeur" @change="emit('change')">
    <option value="">{{ libelleTous }}</option>
    <option v-for="option in options" :key="option" :value="option">
      {{ option }}
    </option>
  </select>
</template>

<script setup>
defineOptions({ name: "FiltreListeConcerts" });

// Liste déroulante d'un filtre de concerts (mois, genre, classification) : libellé réservé aux
// lecteurs d'écran, option « tous » et valeurs proposées par la réponse du serveur.
defineProps({
  id: { type: String, required: true },
  libelle: { type: String, required: true },
  libelleTous: { type: String, required: true },
  options: { type: Array, default: () => [] },
});
const valeur = defineModel({ type: String, default: "" });
const emit = defineEmits(["change"]);
</script>
