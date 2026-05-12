---
paths:
  - "apps/web/src/lib/server/auth*"
  - "apps/web/src/routes/**/login*"
  - "apps/web/src/routes/**/first-login*"
  - "apps/web/src/routes/**/parametres*"
  - "apps/web/src/routes/**/sign*"
  - "apps/web/src/hooks*"
---

# Authentification — détails

## Invite flow

Admin crée le compte avec email uniquement → username temporaire (`u_` + 8 chars) + mot de passe temporaire (16 chars base64url).
Première connexion : `must_change_credentials = true` → `/first-login`. Login par **identifiant** (insensible à la casse).

## Politique

- Mot de passe : 12 caractères minimum (NIST SP 800-63B)
- Rate limiting : 5 tentatives / 15 min sur `/sign-in/username` et `/two-factor/verify-totp` (storage mémoire)
- `PUBLIC_BASE_URL` : `/setup` accepte `http://`+`https://`, `/admin` exige `https://` pour domaines publics

## TOTP (2FA)

Plugin `twoFactor` dans `auth.ts` (`issuer: "Strapontin"`). Guard dans `hooks.server.ts` → tout admin sans 2FA redirigé vers `/parametres#deux-facteurs`.
Flux : si `twoFactorRedirect: true` dans la réponse, afficher formulaire TOTP puis `authClient.twoFactor.verifyTotp({ code })`.
Actions serveur : `auth.handler` avec requête synthétique (le plugin ne s'expose pas via `auth.api`).
Migration : `0003_two_factor.sql`.

## Logs

After hook sur `/sign-in/username` (`createAuthMiddleware` dans `auth.ts`) — `info` succès (`identifiant`, `ip`, `userId`), `warn` échec.

## Détails techniques

Plugin `username` : colonnes `username` + `display_username` sur `users`.
`@__NO_SIDE_EFFECTS__` strippée dans `vite.config.ts` → évite erreur Rollup avec adapter-node.
`baseURL` Better Auth : `process.env.BETTER_AUTH_URL` dans `auth.ts`.
