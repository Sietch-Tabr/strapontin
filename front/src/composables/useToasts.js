import { ref } from "vue";
import { etatConnexion } from "./useEtatConnexion.js";

const toasts = ref([]);
let compteur = 0;

function delaiParType(type) {
  if (type === "erreur") return 0;
  if (type === "avertissement") return 10000;
  return 5000;
}

// Sans données lisibles, l'écran hors ligne dit déjà l'essentiel : aucune erreur de chargement ne
// s'y ajoute. `toujours` garde un message qui n'est pas une erreur de chargement (la perte de
// connexion elle-même).
export function afficherToast(
  message,
  type = "info",
  { toujours = false } = {},
) {
  if (type === "erreur" && etatConnexion.sansDonnees && !toujours) return null;
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
