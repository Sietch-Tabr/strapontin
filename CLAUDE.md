# CLAUDE.md — strapontin (strapontin.kwaite.fr)

## Lancer l'application (développement)

L'app démarre automatiquement au démarrage du LXC dev (`pct start 114`).
Accès : `http://192.168.20.78:3000`

```bash
journalctl -fu strapontin-dev-web
journalctl -fu strapontin-dev-worker
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
└── scripts/
    ├── update.sh         ← backup → pull → build → migrate → restart → healthcheck
    └── update-dev.sh     ← déploie la branche courante sur le LXC dev (Mac → SSH)
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

LXC dev : VMID 114, `192.168.20.78`, VLAN 20. LXC prod : VMID 113, `192.168.20.79`.

```bash
ssh root@192.168.20.78                                          # dev (accès direct)
ssh root@192.168.1.99 "pct exec 113 -- journalctl -u strapontin-web --no-pager -n 50"  # prod
```

### Gestion des issues (Codeberg)

CLI : `tea` (pas `gh` — Codeberg est Gitea, incompatible avec `gh`).

```bash
tea issues -r Kwaite/strapontin -f "index,title,body,comments" -o json

tea pr create -r Kwaite/strapontin --title "..." --description "$(cat <<'EOF'
Closes #N

## Résumé
...
EOF
)"
```

`tea issue view N` → toujours passer `-f "index,title,body"` (body absent par défaut).
Jalons actifs : **v1.2.3**, **v1.3.0**, **v1.4.0**. Labels : `bug`, `enhancement`, `question`.

### Workflow Git

- **Toujours sur une branche feature** — jamais de commit direct sur `main`
- Nommage : `feature/`, `fix/`, `chore/`
- `push main` → Woodpecker → `update.sh` sur LXC prod

### Pipeline Woodpecker (.woodpecker.yaml)

Trois steps : `test` → `typecheck` (`depends_on: [test]`) → `deploy` (main uniquement).

- `test` : `pnpm -r test` (unitaires, mocks DB)
- `typecheck` : svelte-check + `tsc --noEmit` + `pnpm audit --audit-level=high`

**Piège pnpm store** : `--store-dir ./.pnpm-store` en flag CLI — `PNPM_STORE_DIR` non fiable sur pnpm@11. `depends_on: [test]` sérialise les steps pour éviter race condition ENOENT sur le store partagé.

**pnpm 11** : `packageManager: "pnpm@11.0.8"` dans `package.json` (corepack). `allowBuilds: true` dans `pnpm-workspace.yaml` (esbuild, msgpackr-extract). `confirmModulesPurge: false` (sinon abort sans TTY en deploy SSH).

## Scripts d'exploitation

`update.sh` (prod) : backup → pull → install → build → migrate → restart → healthcheck (10×3s). Rollback auto sur `trap ERR` étapes 3–5.
`system-update.sh` : depuis host Proxmox uniquement (`ssh root@192.168.1.99 "bash -s" < scripts/system-update.sh`).
Détails : [wiki Exploitation](https://codeberg.org/Kwaite/strapontin/wiki/Exploitation)

## Variables d'environnement (.env)

`.env` serveur : `DATABASE_URL`, `REDIS_URL`, `BETTER_AUTH_SECRET`, `BETTER_AUTH_URL`.
Clés applicatives (`TICKETMASTER_API_KEY`, `RESEND_API_KEY`, `CONTACT_EMAIL`, `PUBLIC_BASE_URL`, `VAPID_*`) dans `app_config` (DB), configurables depuis `/admin`.

## Configuration applicative (`app_config`)

Chiffrement AES-256-GCM, clé dérivée de `BETTER_AUTH_SECRET` via `scryptSync`.
⚠️ `BETTER_AUTH_SECRET` obligatoire au démarrage (fail-fast dans `config.ts` des deux apps).
⚠️ Changer `BETTER_AUTH_SECRET` en prod → toutes les valeurs `app_config` illisibles.
⚠️ Changer les clés VAPID → supprimer `push_abonnements` d'abord (anciens endpoints invalides).

## Sources de données

Détails : [wiki Sources de données](https://codeberg.org/Kwaite/strapontin/wiki/Sources-de-données)

| Source                    | Statut                                                                        |
| ------------------------- | ----------------------------------------------------------------------------- |
| Ticketmaster Discovery v2 | Core                                                                          |
| Eventim France            | Core                                                                          |
| Deezer Search API         | Autocomplete artiste (`/artistes`) — sans clé. Remplace MusicBrainz (bloqué). |
| Bandsintown               | Abandonné — HTTP 401 depuis 2026-04-10 (#65)                                  |
| MusicBrainz               | Bloqué — IP SFR blacklistée par Cloudflare (#293)                             |
| Fnac / Shotgun / Dice.fm  | Abandonnés                                                                    |

## Pattern workers (BullMQ)

Paradigme : **pré-fetch en arrière-plan, jamais d'appel API depuis le browser**.
→ Pièges détaillés, Redis, classifications TM : `.claude/rules/bullmq.md`

| Type                   | Déclenchement           | Description                                  |
| ---------------------- | ----------------------- | -------------------------------------------- |
| `fetch:artiste`        | Ajout artiste + cron 2h | Fetch TM + Eventim par nom artiste           |
| `fetch:artistes:batch` | Cron 2h                 | Fetch TM en lots de 20 attractionIds         |
| `fetch:region`         | Cron 2h + manuel        | Fetch TM + Eventim par département           |
| `fetch:salle`          | Ajout salle + cron 2h   | Fetch TM par `venueId`                       |
| `recalculer:matches`   | Après fetch             | Recalcule `user_matches` pour tous les users |
| `purger:concerts`      | Cron quotidien (minuit) | Supprime concerts passés depuis > 7 jours    |

## Authentification

Invite-only. Login par identifiant (insensible à la casse). Rôles : `user` | `admin`.
2FA TOTP obligatoire pour les admins. Cap : 500 artistes / 200 salles par user.
→ Invite flow, TOTP, rate limiting, logs auth : `.claude/rules/auth.md`
Détails : [wiki Authentification](https://codeberg.org/Kwaite/strapontin/wiki/Authentification)

## Dédoublonnage inter-sources

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, fuzzy ≥ 0.85.
→ Casse TM, resolver, `persisterConcerts` : `.claude/rules/dedup.md`

## UI / Design system

- **shadcn-svelte** (Svelte 5 + Tailwind v4) — composants dans le repo, pas de mise à jour externe
- **Thème** : `auto`/`clair`/`sombre` — cookie (1 an) + DB → pas de FOUC
- **PWA** : service worker + push. Cap 10 abonnements/user. `GET /api/push` intentionnellement public.
- **Badge "Nouveau"** : basé sur `user_matches.notifie_le IS NULL`
- **Headers sécurité** : NPM pose HSTS/X-Frame/CSP-base. SvelteKit pose COOP/CORP/X-Robots. CSP nonce dans `svelte.config.js` (`mode: "nonce"` — pas d'`unsafe-inline`).

## Schéma PostgreSQL

Migrations dans `packages/core/migrations/` — via `pnpm --filter @strapontin/core db:generate`, jamais à la main.
`users.id` est de type `text` (Better Auth génère ses propres IDs).
Détails : [wiki Schéma](https://codeberg.org/Kwaite/strapontin/wiki/Schéma-de-base-de-données)

## Conventions

- TypeScript strict, pas de `any`
- Noms de variables en français
- Logging : pino 10, lazy `%s` (pas de template literals dans les appels logger), pas de `console.log`
- **tsconfig packages Node** : `"types": ["node"]` — requis depuis TypeScript 6
- **`<button>` hors formulaire** : toujours `type="button"` (défaut HTML `submit` déclenche le formulaire parent sur mobile)
- **Tests** : unitaires (`pnpm -r test`), fonctionnels (`test:fonctionnel`), intégration (`test:integration`, tunnel SSH requis), E2E Playwright (`test:e2e`, LXC dev requis)
- **Nommage tests** : `<fonction>_<scenario>_<attendu>`. Header `Origin` obligatoire avec Better Auth depuis Node.js (sinon 403).

## Outils Claude Code

- **Understand-Anything** (`/understand`) — graphe de connaissance du codebase
- **code-review-graph** — MCP ; analyse d'impact et blast-radius sur les modifications
- **everything-search** — MCP recherche fichiers via Spotlight (évite le bruit `node_modules/`)

<!-- code-review-graph MCP tools -->

## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.** The graph is faster, cheaper (fewer tokens), and gives
you structural context (callers, dependents, test coverage) that file
scanning cannot.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes` or `query_graph` instead of Grep
- **Understanding impact**: `get_impact_radius` instead of manually tracing imports
- **Code review**: `detect_changes` + `get_review_context` instead of reading entire files
- **Finding relationships**: `query_graph` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview` + `list_communities`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

### Key Tools

| Tool                        | Use when                                               |
| --------------------------- | ------------------------------------------------------ |
| `detect_changes`            | Reviewing code changes — gives risk-scored analysis    |
| `get_review_context`        | Need source snippets for review — token-efficient      |
| `get_impact_radius`         | Understanding blast radius of a change                 |
| `get_affected_flows`        | Finding which execution paths are impacted             |
| `query_graph`               | Tracing callers, callees, imports, tests, dependencies |
| `semantic_search_nodes`     | Finding functions/classes by name or keyword           |
| `get_architecture_overview` | Understanding high-level codebase structure            |
| `refactor_tool`             | Planning renames, finding dead code                    |

### Workflow

1. The graph auto-updates on file changes (via hooks).
2. Use `detect_changes` for code review.
3. Use `get_affected_flows` to understand impact.
4. Use `query_graph` pattern="tests_for" to check coverage.
