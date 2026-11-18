// Point d'accès unique du front à l'API : tout appel passe par appelApi, toute lecture de corps
// par lireJson (règles ESLint no-restricted-globals / no-restricted-syntax).

// En-tête exigé par le serveur sur toute requête modifiante : un en-tête personnalisé impose un
// preflight CORS qu'aucune autre origine, même un sous-domaine voisin, ne peut satisfaire.
export const ENTETE_APPLICATION = "X-Requested-With";
export const VALEUR_ENTETE_APPLICATION = "strapontin";

import { creerCacheHorsLigne, estCachable } from "./cacheHorsLigne.js";
import { ENTETE_MODE_AFFICHAGE, lireModeAffichage } from "./modeAffichage.js";

// Un GET sans copie gardée patiente longtemps (une recherche interroge plusieurs services en
// parallèle) ; avec une copie, on bascule vite sur elle plutôt que de laisser une page vide.
const DELAI_GET_MS = 20_000;
const DELAI_GET_SANS_COPIE_MS = 15_000;
const DELAI_GET_AVEC_COPIE_MS = 5_000;
const DELAI_SONDE_MS = 5_000;
const SEUIL_LENTEUR_MS = 3_000;
// Réponses d'un serveur ou d'un proxy qui redémarre : traitées comme une connexion absente.
const STATUTS_INDISPONIBLE = new Set([502, 503, 504]);
export const ENTETE_DONNEES_EN_CACHE = "X-Donnees-En-Cache";

function stockageNavigateur() {
  try {
    return globalThis.localStorage;
  } catch {
    // Accès refusé (navigation privée, données bloquées) : l'application fonctionne sans copie.
    return undefined;
  }
}

let cache = creerCacheHorsLigne({ stockage: stockageNavigateur() });
let suivi = {
  lenteur() {},
  donneesEnCache() {},
  sansDonnees() {},
  injoignable() {},
};
let appelsLents = 0;
// Vrai depuis le dernier appel qui n'a obtenu aucune réponse de l'application (réseau coupé, délai
// dépassé, 502/503/504 d'un proxy dont l'application ne répond plus) ; faux dès qu'une réponse arrive.
let injoignable = false;
// Vrai dès qu'une liste de l'utilisateur n'a pu être lue et qu'aucune copie n'existe : plus aucun
// appel de liste n'attend alors le serveur, jusqu'au rechargement de la page.
let sansDonnees = false;

function marquerSansDonnees() {
  if (sansDonnees) return;
  sansDonnees = true;
  suivi.sansDonnees(true);
}

function horsLigne() {
  return globalThis.navigator?.onLine === false;
}

/// Branche l'interface sur l'état de la connexion.
/// @param nouveau `{lenteur(bool), donneesEnCache(instantMs)}`
export function brancherSuiviConnexion(nouveau) {
  suivi = { ...suivi, ...nouveau };
}

/// Remet à zéro l'état « sans données » (tests).
export function reinitialiserEtatSansDonnees() {
  sansDonnees = false;
  injoignable = false;
}

function poserInjoignable(valeur) {
  if (injoignable === valeur) return;
  injoignable = valeur;
  suivi.injoignable(valeur);
}

/// Une réponse a été reçue : l'application répond, sauf si c'est celle d'un proxy qui n'en a plus.
function signalerReponse(statut) {
  poserInjoignable(STATUTS_INDISPONIBLE.has(statut));
}

/// Un appel a échoué sans réponse ; l'annulation par l'appelant n'apprend rien sur le serveur.
function signalerEchec(erreur) {
  if (erreur?.name !== "AbortError") poserInjoignable(true);
}

/// Remplace le cache (tests).
export function remplacerCacheHorsLigne(autre) {
  cache = autre;
}

/// Efface les données gardées pour le hors ligne (déconnexion, session expirée, autre compte).
export function purgerCacheHorsLigne() {
  cache.purger();
}

// Un délai dépassé se présente comme une erreur réseau (TypeError), jamais comme un AbortError :
// les appelants réservent ce nom à leur propre annulation.
async function fetchAvecDelai(url, init, delaiMs) {
  const controleur = new AbortController();
  let delaiDepasse = false;
  const minuterie = setTimeout(() => {
    delaiDepasse = true;
    controleur.abort();
  }, delaiMs);
  const relayer = () => controleur.abort();
  if (init.signal?.aborted) controleur.abort();
  else init.signal?.addEventListener("abort", relayer, { once: true });
  try {
    return await fetch(url, { ...init, signal: controleur.signal });
  } catch (erreur) {
    if (delaiDepasse) {
      throw new TypeError("Délai d'attente dépassé", { cause: erreur });
    }
    throw erreur;
  } finally {
    clearTimeout(minuterie);
    init.signal?.removeEventListener("abort", relayer);
  }
}

function suivreLenteur() {
  let signale = false;
  const minuterie = setTimeout(() => {
    signale = true;
    appelsLents++;
    suivi.lenteur(true);
  }, SEUIL_LENTEUR_MS);
  return () => {
    clearTimeout(minuterie);
    if (signale && --appelsLents === 0) suivi.lenteur(false);
  };
}

function reponseDepuisCache(copie) {
  suivi.donneesEnCache(copie.enregistreLe);
  return new Response(copie.corps, {
    status: 200,
    headers: {
      "Content-Type": "application/json",
      [ENTETE_DONNEES_EN_CACHE]: String(copie.enregistreLe),
    },
  });
}

/// Appel à l'API avec l'en-tête applicatif. Ne rejette que si le serveur est injoignable
/// (réseau coupé, DNS, délai dépassé) : une réponse d'erreur du serveur, JSON ou non, est résolue
/// normalement et se lit par `ok`/`status`.
///
/// Un GET sur une liste de l'utilisateur garde sa dernière réponse réussie ; si le serveur ne
/// répond pas à temps, cette copie est rendue à la place (en-tête `X-Donnees-En-Cache`). Les
/// requêtes modifiantes ne sont jamais rejouées ni limitées dans le temps.
/// @param options options de `fetch`, plus `sansRepli` (jamais de copie : sonde de reconnexion)
///        et `delaiMs` (délai d'attente d'un GET)
export async function appelApi(url, options = {}) {
  const { sansRepli = false, arrierePlan = false, delaiMs, ...init } = options;
  const entetes = new Headers(init.headers);
  entetes.set(ENTETE_APPLICATION, VALEUR_ENTETE_APPLICATION);
  entetes.set(ENTETE_MODE_AFFICHAGE, lireModeAffichage());
  const methode = (init.method ?? "GET").toUpperCase();
  if (methode !== "GET") {
    return fetch(url, { ...init, headers: entetes }).then(
      (reponse) => {
        signalerReponse(reponse.status);
        return reponse;
      },
      (erreur) => {
        signalerEchec(erreur);
        throw erreur;
      },
    );
  }

  const cachable = !sansRepli && estCachable(url);
  const copie = cachable ? cache.lire(url) : null;
  const delai =
    delaiMs ??
    (!cachable
      ? DELAI_GET_MS
      : copie
        ? DELAI_GET_AVEC_COPIE_MS
        : DELAI_GET_SANS_COPIE_MS);
  if (cachable && !copie && (sansDonnees || horsLigne())) {
    marquerSansDonnees();
    throw new TypeError("Hors ligne");
  }
  // Un appel d'arrière-plan (sonde, rafraîchissement périodique) ne signale jamais de lenteur :
  // l'utilisateur ne l'a pas demandé.
  const finirSuivi = arrierePlan ? () => {} : suivreLenteur();
  try {
    const reponse = await fetchAvecDelai(
      url,
      { ...init, headers: entetes },
      delai,
    );
    signalerReponse(reponse.status);
    if (cachable) {
      if (reponse.status === 401) {
        cache.purger();
      } else if (reponse.ok) {
        reponse
          .clone()
          .text()
          .then((corps) => cache.ecrire(url, corps))
          .catch(() => {});
      } else if (STATUTS_INDISPONIBLE.has(reponse.status)) {
        if (copie) return reponseDepuisCache(copie);
        marquerSansDonnees();
      }
    }
    return reponse;
  } catch (erreur) {
    signalerEchec(erreur);
    if (erreur?.name !== "AbortError") {
      if (copie) return reponseDepuisCache(copie);
      if (cachable) marquerSansDonnees();
    }
    throw erreur;
  } finally {
    finirSuivi();
  }
}

/// Sonde de reconnexion : `true` dès que le serveur répond (même 401), sans jamais passer par
/// une copie gardée.
export async function serveurJoignable() {
  try {
    const reponse = await appelApi("/api/session", {
      sansRepli: true,
      arrierePlan: true,
      delaiMs: DELAI_SONDE_MS,
      cache: "no-store",
    });
    return reponse.status < 500;
  } catch {
    return false;
  }
}

export const MESSAGE_SERVEUR_INDISPONIBLE =
  "Le serveur est momentanément indisponible. Réessayez dans un instant.";

/// Corps JSON d'une réponse, objet vide si le corps est absent ou non JSON (réponse 204, page
/// d'erreur d'un proxy pendant un redémarrage) : jamais d'exception, qui serait sinon affichée
/// comme une erreur réseau. Une erreur serveur (5xx) sans message reçoit `erreur`, que les
/// appelants affichent avant leur message par défaut.
export async function lireJson(reponse) {
  const corps = await reponse.json().catch(() => ({}));
  if (
    reponse.status >= 500 &&
    corps !== null &&
    typeof corps === "object" &&
    !Array.isArray(corps) &&
    !corps.erreur
  ) {
    return { ...corps, erreur: MESSAGE_SERVEUR_INDISPONIBLE };
  }
  return corps;
}
