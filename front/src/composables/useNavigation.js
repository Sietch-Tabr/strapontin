import { nextTick } from "vue";
import { useRouter } from "vue-router";

export function useNavigation() {
  const router = useRouter();

  function naviguer(href) {
    if (router.resolve(href).matched.length === 0) {
      window.location.href = href;
      return;
    }
    if (!document.startViewTransition) {
      router.push(href);
      return;
    }
    document.startViewTransition(async () => {
      await router.push(href);
      await nextTick();
    });
  }

  return { naviguer };
}
