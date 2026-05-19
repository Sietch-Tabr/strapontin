# CLAUDE.md — strapontin (strapontin.kwaite.fr)

## Lancer l'application (v2 — LXC dev)

Accès : `http://192.168.20.78:8080` (Java + Thymeleaf, Phase 8+)

```bash
journalctl -fu strapontin-web
journalctl -fu strapontin-worker
systemctl restart strapontin-web strapontin-worker
```

## Architecture — monorepo Maven

```
strapontin/
├── java/
│   ├── pom.xml       ← parent BOM (Java 25, Spring Boot 4.0.6)
│   ├── core/         ← normalisation, dédup, types partagés
│   ├── sources/      ← clients TM, Eventim, Deezer
│   ├── web/          ← Spring Boot (REST API + Thymeleaf UI, port 8080)
│   └── worker/       ← Spring Boot + Quartz (port 8081)
└── scripts/
    ├── provision.sh       ← provisionnement LXC dev|prod (Proxmox) ; copie update.sh via pct push
    ├── update.sh          ← restart services + healthcheck ; appelé par Woodpecker (prod) ou manuellement (dev)
    └── migrate_data.sql   ← migration données prod (dblink LXC 115 → 113, one-shot)
```

## Stack technique (v2.0.0)

- **Spring Boot 4.0.6** + Thymeleaf — UI SSR + REST API (port 8080)
- **Spring Security 7** — auth session, form login, TOTP 2FA optionnel (bucket4j rate-limiting)
- **Spring Data JDBC** + PostgreSQL 18 — données persistantes
- **Quartz** — jobs background (fetch concerts)
- **Flyway 12** — migrations DB (`java/web/src/main/resources/db/migration/`)
- **Resend** — notifications email (tier gratuit 3 000/mois)
- **web-push (VAPID)** — notifications push PWA
- **Maven** — build (`mvn -f java/pom.xml -pl web,worker -am package`)

## Workflow de développement

### LXC dev (VMID 114, 192.168.20.78) / prod v1 (VMID 113, 192.168.20.79)

```bash
ssh root@192.168.20.78                                                                 # dev
ssh root@192.168.1.99 "pct exec 113 -- journalctl -u strapontin-web --no-pager -n 50" # prod v1
```

### Build et déploiement LXC dev

```bash
# Build (Mac — Maven requis, pas sur le LXC)
mvn -f java/pom.xml -pl web,worker -am package

# Dev : toujours manuel (Woodpecker cible uniquement le LXC prod)
# Woodpecker prod : build sur agent → scp JARs → SSH update.sh (LXC = JRE uniquement, pas de Maven)

scp java/web/target/web-2.0.0-SNAPSHOT.jar root@192.168.20.78:/opt/strapontin/web.jar
scp java/worker/target/worker-2.0.0-SNAPSHOT.jar root@192.168.20.78:/opt/strapontin/worker.jar
ssh root@192.168.20.78 "/opt/strapontin/update.sh"
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
Labels : `Bug`, `Feature`, `Question`, `Task`, `Duplicate`, `Invalid`.

### Workflow Git (v2)

- `main` : prod v1 Node.js (Woodpecker déploie sur push — **ne jamais pousser manuellement**)
- `v2` : intégration — CI uniquement, pas de deploy auto
- Features mergées dans `v2`, `v2` → `main` au jour J uniquement (Phase 9)

### Pipeline Woodpecker (.woodpecker.yaml)

Pipeline Maven actif : test → build → deploy sur push `v2`. Secrets requis : `ssh_key`, `lxc_java_host`.

## Variables d'environnement — java/web/.env

```
DATABASE_URL=jdbc:postgresql://localhost/strapontin
APP_CONFIG_SECRET=<valeur de BETTER_AUTH_SECRET LXC 113>
```

## Configuration applicative (`app_config`)

Chiffrement AES-256-GCM, clé = `APP_CONFIG_SECRET`.
⚠️ Changer `APP_CONFIG_SECRET` → toutes les valeurs `app_config` illisibles.
⚠️ Changer les clés VAPID → supprimer `push_abonnements` d'abord.
Clés applicatives (`TICKETMASTER_API_KEY`, `RESEND_API_KEY`, `CONTACT_EMAIL`, `PUBLIC_BASE_URL`, `VAPID_*`) configurables depuis `/admin`.

## Sources de données

| Source                    | Statut                                                                        |
| ------------------------- | ----------------------------------------------------------------------------- |
| Ticketmaster Discovery v2 | Core                                                                          |
| Eventim France            | Core                                                                          |
| Deezer Search API         | Autocomplete artiste (`/artistes`) — sans clé. Remplace MusicBrainz (bloqué). |
| Bandsintown               | Abandonné — HTTP 401 depuis 2026-04-10 (#65)                                  |
| MusicBrainz               | Bloqué — IP SFR blacklistée par Cloudflare (#293)                             |

## Jobs background (Quartz)

Paradigme : **pré-fetch en arrière-plan, jamais d'appel API depuis le browser**.

| Job                     | Déclenchement           | Description                                  |
| ----------------------- | ----------------------- | -------------------------------------------- |
| `FetchArtisteJob`       | Ajout artiste + cron 2h | Fetch TM + Eventim par nom artiste           |
| `FetchArtistesBatchJob` | Cron 2h                 | Fetch TM en lots de 20 attractionIds         |
| `FetchRegionJob`        | Cron 2h + manuel        | Fetch TM + Eventim par département           |
| `FetchSalleJob`         | Ajout salle + cron 2h   | Fetch TM par `venueId`                       |
| `RecalculerMatchesJob`  | Après fetch             | Recalcule `user_matches` pour tous les users |
| `PurgerConcertsJob`     | Cron quotidien (minuit) | Supprime concerts passés depuis > 7 jours    |

Rate limiting TM : max 2 jobs simultanés (3 → HTTP 429). Quota TM persisté dans `app_config` (`TM_QUOTA_JSON`).

## Authentification

Invite-only. Login par identifiant (insensible à la casse). Rôles : `user` | `admin`.
2FA TOTP optionnel pour tous les utilisateurs — enrollment via `/parametres/totp`, challenge automatique à la connexion si activé. Cap : 500 artistes / 200 salles par user.
Spring Security form login → cookie `SESSION` (JDBC-backed, survit aux restarts).
`BetterAuthPasswordEncoder` : scrypt hérité de v1 → re-hash bcrypt transparent au premier login.
Détails : [wiki Authentification](https://codeberg.org/Kwaite/strapontin/wiki/Authentification)

## Dédoublonnage inter-sources

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, fuzzy Levenshtein normalisé ≥ 0.85.
→ Détails : `.claude/rules/dedup.md`

## UI / Design system (v2 — Thymeleaf)

- **Thymeleaf** + Layout Dialect — templates dans `java/web/src/main/resources/templates/`
- **CSS vanilla** — variables CSS pour thème clair/sombre, dans `static/css/`
- **PWA** : service worker JS vanilla + push. Cap 10 abonnements/user.
- **Badge "Nouveau"** : basé sur `user_matches.notifie_le IS NULL`
- **Thème** : cookie `theme` (1 an) + `user_config.theme` → pas de FOUC

## Schéma PostgreSQL

Migrations Flyway dans `java/web/src/main/resources/db/migration/` — ne jamais modifier à la main.
`users.id` : type `text` (UUID généré par Java).
Détails : [wiki Schéma](https://codeberg.org/Kwaite/strapontin/wiki/Schéma-de-base-de-données)

## Conventions Java

- Identifiants en français (`nombreUtilisateurs`, `calculerTotal`)
- Logging : SLF4J, jamais `System.out`
- **Tests Java** : `mvn -pl core,sources test` (unitaires). `mvn -pl worker test -am` requiert Docker (Testcontainers).
- **Tests E2E** : Playwright (`e2e/`) — 52 tests, nécessite LXC dev actif (port 8080)
  - Lancer : `cd e2e && npx playwright test`
  - Credentials : `E2E_IDENTIFIANT=kwaite E2E_MOT_DE_PASSE=... npx playwright test` (si session expirée)
  - Résultats : `e2e/test-results/results.json` (reporter JSON)
- Nommage tests : `<methode>_<scenario>_<resultatAttendu>`

## Outils Claude Code

- **code-review-graph** — MCP ; analyse d'impact et blast-radius sur les modifications
- **everything-search** — MCP recherche fichiers via Spotlight (évite le bruit `node_modules/`)

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
