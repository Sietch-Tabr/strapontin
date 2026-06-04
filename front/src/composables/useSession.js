import { ref, readonly } from "vue";
import { useRouter } from "vue-router";

const session = ref(null);
const chargement = ref(false);

export function useSession() {
  const router = useRouter();

  async function chargerSession() {
    if (chargement.value) return;
    chargement.value = true;
    try {
      const rep = await fetch("/api/session");
      if (rep.status === 401) {
        session.value = null;
        router.push("/login");
        return;
      }
      session.value = await rep.json();
    } catch (_) {
      session.value = null;
    } finally {
      chargement.value = false;
    }
  }

  return {
    session: readonly(session),
    chargement: readonly(chargement),
    chargerSession,
  };
}
