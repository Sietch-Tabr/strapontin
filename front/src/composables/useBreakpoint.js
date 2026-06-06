import { ref, onMounted, onUnmounted } from "vue";

export function useEstMobile(seuil = 1024) {
  const mq =
    typeof window !== "undefined"
      ? window.matchMedia(`(max-width: ${seuil}px)`)
      : null;

  const estMobile = ref(mq?.matches ?? false);

  const mettreAJour = (e) => {
    estMobile.value = e.matches;
  };

  onMounted(() => mq?.addEventListener("change", mettreAJour));
  onUnmounted(() => mq?.removeEventListener("change", mettreAJour));

  return estMobile;
}
