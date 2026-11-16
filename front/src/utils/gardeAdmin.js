/**
 * Renvoie la destination d'un utilisateur sans droit d'administration qui
 * vise une route d'administration, sinon rien. Sans session connue, la route
 * est laissée passer : la garde de connexion s'en charge.
 *
 * @param {{ meta?: { admin?: boolean } }} destination route visée
 * @param {{ role?: string } | null} session session courante
 * @returns {string | undefined} chemin de repli ou rien
 */
export function repliSansDroitAdmin(destination, session) {
  if (!destination.meta?.admin || !session) return undefined;
  return session.role === "admin" ? undefined : "/";
}
