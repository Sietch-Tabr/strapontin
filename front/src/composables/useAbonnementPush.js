import { appelApi, lireJson } from "../utils/api.js";

// Abonnement push de l'appareil courant, partagé par Paramètres et l'onboarding.

export function pushPrisEnCharge() {
  return "serviceWorker" in navigator && "PushManager" in window;
}

function urlBase64VersUint8Array(base64) {
  const pad = "=".repeat((4 - (base64.length % 4)) % 4);
  const b64 = (base64 + pad).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(b64);
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
}

/**
 * @returns {Promise<{disponible: boolean, clePublique: string|null}>} état VAPID du serveur
 */
export async function lireVapid(signal) {
  const rep = await appelApi("/api/push", { signal });
  if (!rep.ok) return { disponible: false, clePublique: null };
  const vapid = await lireJson(rep);
  return {
    disponible: vapid.disponible ?? false,
    clePublique: vapid.clePublique ?? null,
  };
}

/**
 * @returns {Promise<string[]|null>} endpoints des appareils abonnés du compte, null si inconnu
 */
export async function lireEndpointsServeur(signal) {
  const rep = await appelApi("/api/push/abonnements", { signal });
  if (!rep.ok) return null;
  const abonnements = await lireJson(rep);
  return Array.isArray(abonnements) ? abonnements.map((a) => a.endpoint) : null;
}

/**
 * Demande l'autorisation, abonne l'appareil et l'enregistre côté serveur.
 * @returns {Promise<{statut: "ok"|"refuse"|"erreur", endpoint?: string, message?: string}>}
 */
export async function abonnerAppareil(cleVapidPublique, signal) {
  const permission = await Notification.requestPermission();
  if (permission !== "granted") return { statut: "refuse", permission };

  const enregistrement = await navigator.serviceWorker.ready;
  const abonnement = await enregistrement.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64VersUint8Array(cleVapidPublique),
  });
  const rep = await appelApi("/api/push", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      endpoint: abonnement.endpoint,
      keys: {
        p256dh: new Uint8Array(abonnement.getKey("p256dh")).toBase64(),
        auth: new Uint8Array(abonnement.getKey("auth")).toBase64(),
      },
    }),
    signal,
  });
  if (!rep.ok) {
    await abonnement.unsubscribe();
    const corps = await lireJson(rep);
    return { statut: "erreur", message: corps.erreur };
  }
  document.cookie =
    "push_opt_in=1; path=/; max-age=" + 365 * 24 * 3600 + "; SameSite=Strict";
  return { statut: "ok", endpoint: abonnement.endpoint };
}
