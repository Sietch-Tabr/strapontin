import { ref } from "vue";

const toasts = ref([]);
let compteur = 0;

function delaiParType(type) {
  if (type === "erreur") return 0;
  if (type === "avertissement") return 10000;
  return 5000;
}

export function afficherToast(message, type = "info") {
  const id = ++compteur;
  const indice = toasts.value.length;
  toasts.value.push({ id, message, type, indice });

  const delai = delaiParType(type);
  if (delai > 0) {
    setTimeout(() => fermerToast(id), delai);
  }
  return id;
}

function fermerToast(id) {
  const index = toasts.value.findIndex((t) => t.id === id);
  if (index !== -1) {
    toasts.value.splice(index, 1);
    // Recalculer les indices
    toasts.value.forEach((t, i) => {
      t.indice = i;
    });
  }
}

export function useToasts() {
  return { toasts, afficherToast, fermerToast };
}
