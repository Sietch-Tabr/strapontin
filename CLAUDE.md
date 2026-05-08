# CLAUDE.md — strapontin (strapontin.kwaite.fr)

## Lancer l'application (développement)

L'app démarre automatiquement au démarrage du LXC dev (`pct start 114`).
Accès : `http://192.168.20.78:3000`

```bash
# Logs en direct
journalctl -fu strapontin-dev-web
journalctl -fu strapontin-dev-worker

# Redémarrer (ex. après git pull + pnpm install)
systemctl restart strapontin-dev-web strapontin-dev-worker
```

## Architecture — monorepo pnpm workspaces

```
strapontin/
├── apps/
│   ├── web/              ← SvelteKit (UI + API routes)
│   └── worker/           ← BullMQ worker (fetch + normalisation)
├── packages/
│   ├── core/             ← Dédoublonnage, normalisation, filtres (logique pure)
│   └── sources/          ← Clients API : TM, Eventim
├── scripts/
│   ├── provision.sh      ← Création + provisioning LXC prod ou dev (`--env prod|dev`)
│   ├── deploy.sh         ← Premier déploiement (clone + build + migrations + services)
│   ├── backup.sh         ← pg_dump + .env → /mnt/backup/ (NAS monté)
│   ├── update.sh         ← backup → pull → build → migrate → restart → healthcheck
│   └── update-dev.sh     ← déploie la branche courante sur le LXC dev (Mac → SSH)
└── archive/
    └── concert-finder-cli/  ← App Python CLI d'origine (référence)
```

## Stack technique

- **SvelteKit** + TypeScript — frontend SSR + API routes
- **Drizzle ORM** + PostgreSQL 18 — données persistantes
- **BullMQ** + Redis 8 — jobs background (fetch concerts toutes les 2h)
- **Better-Auth** — auth username/password, invite-only (plugin `username` activé)
- **Resend** — notifications email (tier gratuit 3 000/mois)
- **pnpm workspaces** — monorepo sans Turborepo

## Workflow de développement

### Dev quotidien (LXC dev)

LXC dev : VMID 114, `192.168.20.78`, VLAN 20 — même stack que prod (PG 18, Redis 8).
Les services `strapontin-dev-web` et `strapontin-dev-worker` démarrent automatiquement avec le LXC.

```bash
# Démarrer / arrêter le LXC (depuis le host Proxmox)
pct start 114
pct stop 114

# Connexion SSH dev (accès direct autorisé)
ssh root@192.168.20.78

# Prod : accès direct Mac bloqué intentionnellement — passer par Proxmox
ssh root@192.168.1.99 "pct exec 113 -- journalctl -u strapontin-web --no-pager -n 50"

# Redémarrer les services (après git pull + pnpm install)
systemctl restart strapontin-dev-web strapontin-dev-worker
```

### Gestion des issues (Codeberg)

CLI : `tea` (pas `gh` — Codeberg est Gitea, incompatible avec `gh`).

```bash
# Lister les issues
tea issues -r Kwaite/strapontin -f "index,title,body,comments" -o json

# Créer une PR (toujours mettre Closes #N dans le corps, pas seulement le titre)
tea pr create -r Kwaite/strapontin --title "..." --description "$(cat <<'EOF'
Closes #N

## Résumé
...
EOF
)"
```

`tea issue view N` n'affiche pas `body` par défaut — toujours passer `-f "index,title,body"`.
Jalons : **v1.2.3**, **v1.3.0**, **v1.4.0** (actifs). Labels : `bug`, `enhancement`, `question`.

### Workflow Git

```
git checkout -b feature/nom-court
     ↓
  développement + validation locale
     ↓
  git push feature/nom-court  →  Woodpecker ignore (branches feature)
     ↓
  PR feature/xxx → main  →  review + merge
     ↓
  push main  →  Woodpecker → update.sh sur LXC prod
```

- **Toujours travailler sur une branche feature** — jamais de commit direct sur `main`
- Nommage : `feature/`, `fix/`, `chore/`

### Pipeline Woodpecker (.woodpecker.yaml)

Trois steps : `test` (toutes les branches), `typecheck` (toutes les branches), puis `deploy` (main uniquement).

- **`test`** (`node:24`) : `pnpm -r test` — unitaires core + sources + worker (les tests d'intégration et E2E sont exclus du CI, ils nécessitent le LXC dev).
- **`typecheck`** (`node:24`) : crée un `.env` stub, exécute `svelte-check` sur web, `tsc --noEmit` sur worker, et `pnpm audit --audit-level=high`.
- **`deploy`** (`alpine`) : SSH vers le LXC prod, exécute `update.sh`. Ne tourne que sur `main`.

Secrets : `ssh_key` (clé privée générée par `provision.sh --env prod`), `lxc_host` (IP du LXC prod).

**Piège pnpm store** : les steps CI passent `--store-dir ./.pnpm-store` en flag CLI — le store est dans le workspace, partagé entre les steps. `PNPM_STORE_DIR` (env var) n'est pas fiable sur pnpm@11. `package-import-method=copy` supprimé du `.npmrc` (causait une race condition ENOENT). `typecheck` a `depends_on: [test]` pour sérialiser les deux steps — sans cela, les installs parallèles sur le même store causent une race condition ENOENT. `.pnpm-store/` est dans `.gitignore`.

**pnpm 11 — pièges** : `packageManager: "pnpm@11.0.8"` dans `package.json` épingle la version pour corepack (sinon `ERR_PNPM_LOCKFILE_CONFIG_MISMATCH`). `pnpm-workspace.yaml` : `allowBuilds: true` pour `esbuild` et `msgpackr-extract` (sinon `ERR_PNPM_IGNORED_BUILDS`). `confirmModulesPurge: false` dans `pnpm-workspace.yaml` — pnpm 11 demande confirmation TTY avant de purger `node_modules` ; sans TTY (deploy SSH), il aborte (sinon `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY`).

## Scripts d'exploitation

`update.sh` — ordre strict :

1. `backup.sh`
2. `git fetch origin && git reset --hard origin/main` (mémorise `PREV_COMMIT`)
3. `pnpm install`
4. `set -a; source .env; set +a && pnpm build` — sourcer `.env` avant build (Better Auth init au démarrage)
5. `drizzle-kit migrate`
6. `systemctl restart strapontin-web strapontin-worker`
7. healthcheck : `curl -f http://localhost:3000/health` (10 retries × 3s)

Rollback automatique sur `trap ERR` (étapes 3–5 : install, build, migrate) et sur healthcheck expiré. ⚠️ Si la migration (étape 5) échoue après avoir démarré, le schéma DB peut être partiellement modifié — le rollback restaure uniquement le code.

`system-update.sh` — à exécuter depuis le **host Proxmox** (pas depuis le LXC ni le Mac) :

```bash
ssh root@192.168.1.99 "bash -s" < scripts/system-update.sh
```

**Ne pas intégrer dans `update.sh`** — un `apt upgrade` pendant un déploiement risque de casser PostgreSQL ou Redis.

## Infrastructure

- LXC prod `strapontin` — VMID 113, `192.168.20.79`, VLAN 20, `nesting=1` obligatoire
- LXC dev `strapontin-dev` — VMID 114, `192.168.20.78`, VLAN 20
- Nginx Proxy Manager (LXC 100) → `strapontin.kwaite.fr` → `192.168.20.79:3000`
  - Piège : coller la config NPM via un outil externe encode les `\n` littéralement → nginx casse silencieusement → voir [wiki Déploiement](https://codeberg.org/Kwaite/strapontin/wiki/Déploiement)
- Provisioning : `provision.sh --env prod|dev` (depuis host Proxmox 192.168.1.99)

Détails complets : [wiki Déploiement](https://codeberg.org/Kwaite/strapontin/wiki/Déploiement) · [wiki Exploitation](https://codeberg.org/Kwaite/strapontin/wiki/Exploitation)

**Headers de sécurité — responsabilités** : NPM pose HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Permissions-Policy. SvelteKit (`hooks.server.ts`) pose X-Robots-Tag, COOP, CORP. Le CSP est géré par `svelte.config.js` avec `mode: "nonce"` — `script-src` nonce uniquement, sans `unsafe-inline`. Détails : [wiki Déploiement](https://codeberg.org/Kwaite/strapontin/wiki/Déploiement#responsabilit%C3%A9-des-headers-de-s%C3%A9curit%C3%A9).

**Blocage accès direct IP** : `hooks.server.ts` utilise `estAdresseIP()` depuis `$lib/server/reseau.ts` — couvre IPv4 (`192.168.x.x`), IPv6 bracketé (`[::1]:3000`) et IPv6 non bracketé (`::1`). Autorise les noms de domaine et `localhost`.

## Variables d'environnement (.env)

`.env` sur le serveur : `DATABASE_URL`, `REDIS_URL`, `BETTER_AUTH_SECRET`, `BETTER_AUTH_URL` (URL publique de l'app — évite le warning Better Auth au démarrage et fixe les liens de reset mot de passe).
Les clés applicatives (`TICKETMASTER_API_KEY`, `RESEND_API_KEY`, `CONTACT_EMAIL`, `PUBLIC_BASE_URL`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`) sont dans `app_config` (DB), configurables depuis `/admin`.

## Sources de données

Détails : [wiki Sources de données](https://codeberg.org/Kwaite/strapontin/wiki/Sources-de-données)

| Source                    | Statut                                       |
| ------------------------- | -------------------------------------------- |
| Ticketmaster Discovery v2 | Core                                         |
| Eventim France            | Core                                         |
| Bandsintown               | Abandonné — HTTP 401 depuis 2026-04-10 (#65) |
| Fnac Spectacles           | Abandonné — Cloudflare JS challenge          |
| Shotgun.live              | Abandonné — hors scope (#19)                 |
| Dice.fm                   | Abandonné — hors scope (#19)                 |

## Pattern workers (BullMQ)

Paradigme fondamental : **pré-fetch en arrière-plan, jamais d'appel API depuis le browser**.

### Jobs implémentés

| Type                   | Déclenchement             | Description                                                                                                     |
| ---------------------- | ------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `fetch:artiste`        | Ajout artiste + cron 2h   | Fetch TM + Eventim par nom artiste                                                                              |
| `fetch:artistes:batch` | Cron 2h                   | Fetch TM en lots de 20 attractionIds — 200 concerts/lot, paginé jusqu'à 1 400 concerts                          |
| `fetch:region`         | Cron 2h + action manuelle | Fetch TM + Eventim par département — 200 concerts/dept, paginé jusqu'à 1 400 concerts. Timestamp Redis par dept |
| `fetch:salle`          | Ajout salle + cron 2h     | Fetch TM par `venueId` (stocké dans `user_salles.tmVenueId`)                                                    |
| `recalculer:matches`   | Après fetch               | Recalcule `user_matches` pour tous les users (artistes + salles, filtre géo si depts configurés)                |
| `purger:concerts`      | Cron quotidien (minuit)   | Supprime les concerts dont la date est passée depuis > 7 jours                                                  |

Classifications TM (`fetchArtiste`, `fetchArtistesBatch`, `fetchRegion`) : `music`, `opera`, `musical` — `theatre` exclu (spectacles sans `_embedded.attractions`, inflatent les totaux sans produire de concerts utilisables).

### Pièges BullMQ

- **BullMQ v5 interdit les `:` dans les `jobId`** — utiliser `__` comme séparateur (`artiste__norm`, `artistes__batch`, `region__all`, `salle__venueId`, `matches__all`, `purge__YYYY-MM-DD`). Les `:` dans le _type de job_ (premier arg de `queue.add`) sont acceptés.
- **`removeOnFail: { count: N }` et `removeOnComplete: { count: N }` bloquent le re-queuing des jobs à `jobId` fixe** (BullMQ ≥ 5.76) — le hash Redis persiste et tout `queue.add` ultérieur avec le même `jobId` est ignoré silencieusement. Utiliser `removeOnFail: true` et `removeOnComplete: true` pour les jobs cron à `jobId` fixe.
- **Concurrence** : `{ concurrency: 2 }` dans le constructeur `Worker` — concurrency: 3 provoquait des 429 TM lors de la collision fetch:region + fetch:salle (burst > 5 req/s malgré les délais internes de 250 ms) (#256).
- **Priorisation quota TM** : `SEUIL_QUOTA_CRON = 500` — si `dispo < 500`, les jobs cron (`fetch:artistes:batch`, `fetch:region`) sont suspendus à la planification (`cron.ts`). `SEUIL_QUOTA_ABORT = 50` — tous les jobs TM (y compris `fetch:artiste`, `fetch:salle`) sont annulés à l'exécution. `SEUIL_QUOTA_WARN = 200` — log warn sans annulation (#267).
- **Rate limit burst** : TM applique une limite de 5 req/s indépendamment du quota journalier. `fetchSalle` ajoute 200ms après chaque appel ; `fetchToutesPages` ajoute 250ms entre pages (#240).
- **`artiste_norm` vide** : `persisterConcerts` rejette les concerts dont `normaliserTexte(artiste)` produit une chaîne vide (scripts non-latins comme l'arabe, séparateurs `– – –`) — ils sont loggés `warn` et non insérés (#242, #243).
- **Retry strategy** : jobs à `jobId` fixe (cron/admin) → pas de retry (re-planifiés toutes les 2h de toute façon). Jobs sans `jobId` fixe (actions utilisateur : `fetch:artiste`, `fetch:salle`, `recalculer:matches`) → `attempts: 3, backoff: exponential 5s` (#255).

### Redis — configuration

- `maxmemory 128mb` + `maxmemory-policy allkeys-lru` — configuré via `provision.sh`
- Historique quota TM : liste `strapontin:tm:quota:historique` (LTRIM à 100 entrées) — écrite à chaque `sauvegarderQuotaTM()` en plus de `strapontin:tm:quota`.

### Configuration applicative (`app_config`)

**Chiffrement** : AES-256-GCM. Clé dérivée de `BETTER_AUTH_SECRET` via `scryptSync`.
⚠️ `BETTER_AUTH_SECRET` est obligatoire au démarrage — fail-fast dans `config.ts` des deux apps.
⚠️ Si `BETTER_AUTH_SECRET` change en prod, toutes les valeurs `app_config` deviennent illisibles — vider la table et re-saisir depuis l'IHM.
⚠️ Ne jamais changer les clés VAPID sans supprimer les abonnements push (`DELETE FROM push_abonnements`) — les anciens endpoints deviennent invalides.

`CONTACT_EMAIL` et `PUBLIC_BASE_URL` sont affichées en clair dans `/admin` ; les autres restent masquées.

Pattern lecture : `lireConfig(cle)` / `sauvegarderConfig(cle, valeur)` / `lireConfigsPresentes()` dans `apps/web/src/lib/server/config.ts` (et `apps/worker/src/config.ts`).

## Authentification

Détails complets : [wiki Authentification](https://codeberg.org/Kwaite/strapontin/wiki/Authentification)

Invite-only : l'admin crée le compte avec l'email uniquement. Le système génère username temporaire (`u_` + 8 chars) et mot de passe temporaire (16 chars base64url).

À la première connexion, `must_change_credentials = true` → redirection vers `/first-login`. Login par **identifiant** (insensible à la casse). Rôles : `user` | `admin`.

**Politique mot de passe** : 12 caractères minimum (NIST SP 800-63B).

**Logs auth** : after hook Better Auth sur `/sign-in/username` (`createAuthMiddleware` dans `auth.ts`) — `info` connexion réussie (`identifiant`, `ip`, `userId`), `warn` échec (`identifiant`, `ip`).

**`baseURL` Better Auth** : lu depuis `process.env.BETTER_AUTH_URL` dans `auth.ts`. À définir dans `.env` sur chaque serveur (prod : `https://strapontin.kwaite.fr`, dev : `http://192.168.20.78:3000`). Fixe les liens de reset mot de passe et supprime le warning au démarrage.

**`PUBLIC_BASE_URL` — validation** : `/setup` accepte `http://` et `https://` (IP ou domaine). `/admin` exige `https://` pour les domaines publics, accepte `http://` pour les IPs et `localhost`.

**Rate limiting login** : 5 tentatives max / 15 min sur `/sign-in/username` et `/two-factor/verify-totp`. Storage mémoire.

**Cap par utilisateur** : 500 artistes max, 200 salles max.

Plugin `username` : colonnes `username` + `display_username` sur `users`.
Annotation `@__NO_SIDE_EFFECTS__` strippée dans `vite.config.ts` → évite une erreur Rollup avec adapter-node.

**Double authentification TOTP (2FA)** : plugin `twoFactor` dans `auth.ts` (`issuer: "Strapontin"`). Activer/désactiver dans `/parametres#deux-facteurs`. Guard dans `hooks.server.ts` redirige tout admin sans 2FA vers `/parametres#deux-facteurs`. Flux login : si `twoFactorRedirect: true` dans la réponse, afficher le formulaire TOTP puis appeler `authClient.twoFactor.verifyTotp({ code })`. Les actions serveur utilisent `auth.handler` avec une requête synthétique (le plugin ne s'expose pas via `auth.api`). Colonne `two_factor_enabled` sur `users` + table `two_factor` (migration `0003_two_factor.sql`).

## Dédoublonnage inter-sources (packages/core)

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, puis fuzzy sur `(date, ville_norm)` avec similarité artiste ≥ 0.85. Index secondaire `(date, salle_norm)` pour les salles où TM et Eventim donnent des villes différentes.

### Particularité TM — casse des noms de salles

TM retourne les noms en TOUT MAJUSCULE pour les événements France.

- **Couche 1 — dédup** (`fusionner`) : quand TM et Eventim couvrent le même concert, préférer la valeur avec le plus de diacritiques (Eventim a la bonne casse).
- **Couche 2 — source TM** (`normaliserCasse`) : title-case français sur les noms tout-majuscule. La lettre après apostrophe est capitalisée (`L'OLYMPIA` → `L'Olympia`). Les accents ne sont pas restaurés.
- **Couche 3 — resolver** (`resoudreNomCanonique`) : MusicBrainz (score ≥ 90 **et** similarité Levenshtein ≥ 0.6) puis Deezer en fallback. Cache Redis TTL 7j. Délai 1,1 s entre requêtes MB. Timeout 8 s.

### Particularité TM — nom d'artiste vs titre d'événement

`mapEvenement()` résout dans l'ordre : `attractions[0].name` → `artisteFallback` (si le titre contient le nom) → `null` (pas d'heuristique `split(" - ")`).

### Persistance — `persisterConcerts`

`reconcilierSalles()` réécrit `salle_norm` des concerts entrants dont la salle est sémantiquement identique à un existant (similarité ≥ 0.5 ou containment, longueur ≥ 4).
Clé upsert : `(artiste_norm, date, salle_norm)`. En conflit : diacritiques préférées pour l'artiste, casse mixte préférée pour la salle.
Colonne `statut` (`text`, nullable) : valeur brute TM (`onsale`, `offsale`, `cancelled`, `postponed`, `rescheduled`), `NULL` pour Eventim. Le worker l'écrit en upsert ; le digest ne notifie pas les statuts `cancelled/offsale/postponed/rescheduled` (seuls `onsale` et `NULL` déclenchent une notification).

## UI / Design system

- **shadcn-svelte** (Svelte 5 + Tailwind v4) — composants dans le repo, pas de mise à jour externe
- **Thème** : `auto`/`clair`/`sombre` — cookie (1 an) + DB → pas de FOUC. Script inline dans `app.html` pour l'auto-détection.
- **PWA** : `static/manifest.json`. Service worker : cache assets + gère push + `notificationclick` ouvre `/spectacles`.
- **Push** : cap 10 abonnements/user. `GET /api/push` intentionnellement public (service worker récupère la clé VAPID sans session). Nettoyage auto des abonnements révoqués (404/410).
- **Badge "Nouveau"** : basé sur `user_matches.notifie_le IS NULL` (pas de seuil temporel). Disparaît dès que le digest a notifié l'utilisateur. Les concerts `disponible=false` ont `notifie_le` posé immédiatement dans `recalculerMatches()` → jamais de badge "Nouveau" pour eux.

## Schéma PostgreSQL

Migration `0000_initial.sql` — schéma complet v1.1 fusionné. Nouvelles migrations à partir de `0001`.
`0001_silent_sphinx.sql` : `ALTER TABLE concerts ADD COLUMN statut text` + `SET DEFAULT now()` sur `users.created_at/updated_at` (v1.2.1).
Tables : `users`, `sessions`, `accounts`, `verifications` (Better Auth) + `concerts`, `user_artistes`, `user_salles`, `user_config`, `user_matches`, `fetch_jobs`, `push_abonnements`, `app_config`.
`users.id` est de type `text` (Better Auth génère ses propres IDs).

Détails : [wiki Schéma de base de données](https://codeberg.org/Kwaite/strapontin/wiki/Schéma-de-base-de-données)

## Conventions

- TypeScript strict, pas de `any`
- Noms de variables en français
- Logging : pino 10, lazy `%s` (pas de template literals dans les appels logger), pas de `console.log`
- **tsconfig packages Node** (worker, sources) : `"types": ["node"]` — requis depuis TypeScript 6
- **`<button>` hors formulaire** : toujours `type="button"` — le défaut HTML `submit` déclenche le formulaire parent sur mobile
- **Tests** — trois niveaux :
  - Unitaires (`pnpm -r test`) : core, sources, worker — mocks DB. Tournent en CI.
  - Fonctionnels SvelteKit (`pnpm --filter web test:fonctionnel`) : `load`/`actions` sans navigateur, mocks Drizzle.
  - Intégration worker (`pnpm --filter worker test:integration`) : PG + Redis réels, tunnel SSH requis (`ssh -L 15432:127.0.0.1:5432 -L 16379:127.0.0.1:6379 root@192.168.20.78`).
  - E2E Playwright (`E2E_BASE_URL=http://192.168.20.78:3000 pnpm --filter web test:e2e`) : Chromium sur app déployée, LXC dev requis.
  - Nommage : `<fonction>_<scenario>_<attendu>`. Piège Better Auth depuis Node.js : header `Origin` obligatoire (sinon 403).

## Roadmap — jalons

- **v1.0–v1.2.3** — livrés (2026-04-18 → 2026-05-08) : LXC dev, notifications push, PWA, RGPD, Better Auth, observabilité, Node 24, pnpm 11
- **v1.3.0** — en cours : #245 (notifs offsale), #246 (navigation push), #267 (quota), #247 (update.sh), #251 (IPv6), #248 (aide), #249 (repo public), #252 (logs admin), #253 (onglets admin), #250 (2FA TOTP)
- **v1.4.0** — à planifier
