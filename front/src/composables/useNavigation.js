import { nextTick } from "vue";
import { useRoute, useRouter } from "vue-router";

export function useNavigation() {
  const router = useRouter();
  const route = useRoute();

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

  function estActif(href) {
    if (href === "/") return route.path === "/";
    return route.path === href || route.path.startsWith(href + "/");
  }

  return { naviguer, estActif };
}
