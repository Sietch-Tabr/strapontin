import { appelApi, lireJson } from "../utils/api.js";
import { ref, readonly } from "vue";
import { useRouter, useRoute } from "vue-router";

export const sessionRef = ref(null);
export const sessionInitialisee = ref(false);
// distingue "jamais confirmé (réseau down)" de "confirmé non authentifié (401)" — sans
// ça, un redémarrage PWA hors-ligne (shell servi depuis le cache SW, /api/session inatteignable)
// finissait dans le même état que session.value=null après un vrai 401, et le garde-fou du
// router ci-dessous redirigeait vers /login alors que le cookie de session reste valide.
export const sessionErreurReseau = ref(false);
const session = sessionRef;
const chargement = ref(false);

export function useSession() {
  const router = useRouter();
  const route = useRoute();

  async function chargerSession() {
    if (chargement.value) return;
    chargement.value = true;
    try {
      const rep = await appelApi("/api/session");
      if (rep.status === 401) {
        session.value = null;
        sessionErreurReseau.value = false;
        if (!route.meta.authPage) {
          router.push("/login");
        }
        return;
      }
      session.value = await lireJson(rep);
      sessionErreurReseau.value = false;
    } catch (_) {
      // une exception ici (réseau coupé, DNS, etc.) ne signifie pas que la session est
      // invalide côté serveur — contrairement à un 401 confirmé ci-dessus. Réinitialiser
      // session.value à null forçait une redirection /login au prochain changement de route SPA
      // alors que le cookie de session restait valide, déconnectant l'utilisateur sans raison
      // réelle sur une simple coupure réseau transitoire. On garde la dernière valeur connue.
      sessionErreurReseau.value = true;
    } finally {
      chargement.value = false;
      sessionInitialisee.value = true;
    }
  }

  return {
    session: readonly(session),
    chargement: readonly(chargement),
    chargerSession,
  };
}
