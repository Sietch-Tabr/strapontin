import { ref } from "vue";

export function useDialog() {
  const dialogRef = ref(null);
  const ouvrir = () => dialogRef.value?.showModal();
  const fermer = () => dialogRef.value?.close();
  return { dialogRef, ouvrir, fermer };
}
