import { appelApi, lireJson } from "../utils/api.js";
import { canalJoignable } from "../utils/notifications.js";
import { lireEndpointsServeur } from "./useAbonnementPush.js";

/**
 * @returns {Promise<boolean|null>} vrai si une notification peut parvenir à l'utilisateur,
 *     null si l'état n'a pas pu être lu
 */
export async function notificationsJoignables(signal) {
  const [rep, endpoints] = await Promise.all([
    appelApi("/api/user/config", { signal }),
    lireEndpointsServeur(signal),
  ]);
  if (!rep.ok) return null;
  return canalJoignable(await lireJson(rep), endpoints?.length ?? 0);
}
