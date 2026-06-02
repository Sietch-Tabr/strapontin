# CLAUDE.md — strapontin (strapontin.kwaite.fr)

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
    ├── provision.sh          ← provisionnement LXC dev|prod (Proxmox)
    ├── update.sh             ← restart services + healthcheck ; appelé par Woodpecker
    ├── backup.sh             ← dump PostgreSQL + .env + logs → NAS (/mnt/backup/)
    ├── backup-local.sh       ← dump PostgreSQL + .env + logs → local (/opt/strapontin/backup/, rétention 7j)
    ├── woodpecker-backup.sh  ← appelé par le step backup Woodpecker (copie + exécute backup-local.sh)
    ├── woodpecker-deploy.sh  ← appelé par le step deploy Woodpecker
    └── restore.sh            ← restauration depuis archive tar.gz (NAS ou local)
```

## Architecture module web — couche service/repository

Pattern 3-couches (depuis v2.1.0, ticket #433) :

```
XxxController (HTTP pur) → XxxService (@Transactional, logique métier) → XxxRepository (SQL pur)
```

- **Repositories** (`@Repository`, package-private) : SQL pur, `NamedParameterJdbcTemplate`
- **Services** (`@Service`) : logique métier, `@Transactional` sur les méthodes multi-JDBC
- **Records** : top-level dans le package domaine (plus d'inner classes dans les contrôleurs)
- `GlobalExceptionHandler` (`@RestControllerAdvice`) : `NoResourceFoundException` → 404, `HttpRequestMethodNotSupportedException` → 405, `AuthenticationException` → 401, `DuplicateKeyException` → 409, `DataAccessException` → 500
- `ClientController` (`/api/client/erreur`) : remontée des erreurs JS des navigateurs vers `logs_admin`, source `client-js`
- Référence : `PersisterConcerts` + `RecalculerMatchesService` dans `java/worker/`

## Stack technique (v2.1.2)

- **Spring Boot 4.0.6** + Thymeleaf — UI SSR + REST API (port 8080)
- **Spring Security 7** — auth session, form login, TOTP 2FA optionnel (bucket4j rate-limiting)
- **Spring Data JDBC** + PostgreSQL 18 — données persistantes
- **Quartz** — jobs background (fetch concerts)
- **Flyway 12** — migrations DB (`java/web/src/main/resources/db/migration/`)
- **Resend** — notifications email (tier gratuit 3 000/mois)
- **web-push (VAPID)** — notifications push PWA
- **HTMX 2.0.6** — navigation SPA-like (`hx-boost`) + bannière système (poll 60s), servi via webjar
- **Maven** — build (`mvn -f java/pom.xml -pl web,worker -am package`)

## Workflow de développement

### LXC

| Environnement | VMID | IP            | Notes                                           |
| ------------- | ---- | ------------- | ----------------------------------------------- |
| dev           | 114  | 192.168.20.78 | déploiement manuel via `/deploy-dev`            |
| prod          | 113  | 192.168.20.79 | production — Woodpecker déploie sur push `main` |

```bash
# Logs LXC dev (depuis le LXC)
journalctl -fu strapontin-web
journalctl -fu strapontin-worker
systemctl restart strapontin-web strapontin-worker

# Logs prod (depuis Mac)
ssh root@192.168.20.79 "journalctl -u strapontin-web -n 50 --no-pager"
ssh root@192.168.20.79 "journalctl -u strapontin-worker -n 50 --no-pager"
```

### Build et déploiement LXC dev

Utiliser le skill `/deploy-dev` (build Maven avec tests + scp + restart + tail logs).

Commandes manuelles si besoin :

```bash
mvn -f java/pom.xml -pl web,worker -am package
scp "$(ls -t java/web/target/web-*.jar | grep -v '.original' | head -1)" root@192.168.20.78:/opt/strapontin/web.jar.new
scp "$(ls -t java/worker/target/worker-*.jar | grep -v '.original' | head -1)" root@192.168.20.78:/opt/strapontin/worker.jar.new
ssh root@192.168.20.78 "/opt/strapontin/scripts/update.sh"
```

Accès app : `http://192.168.20.78:8080` — config locale dans `CLAUDE.local.md` (tunnel SSH, URL sandbox).

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

### Workflow Git

- `main` : branche principale — Woodpecker déploie sur LXC 113 prod à chaque push (**ne jamais pousser manuellement sans build valide**)
- Features : branche `feature/xxx` → PR → merge dans `main`

### Pipeline Woodpecker (.woodpecker.yaml)

Pipeline Maven active : **test → build → backup → deploy**, tous restreints à `branch: main`. Secrets requis : `ssh_key`, `lxc_host`. Le step `backup` exécute `woodpecker-backup.sh` (backup local sur le LXC prod avant déploiement).

> **Woodpecker — repo trusted** : le repo doit être marqué "trusted" (Settings → repo → cocher "Trusted") pour autoriser les volumes Maven dans le pipeline.

## Variables d'environnement — java/web/.env

```
DATABASE_URL=jdbc:postgresql://localhost/strapontin
APP_CONFIG_SECRET=<clé AES-256-GCM de chiffrement de app_config>
```

## Configuration applicative (`app_config`)

Chiffrement AES-256-GCM, clé = `APP_CONFIG_SECRET`.
⚠️ Changer `APP_CONFIG_SECRET` → toutes les valeurs `app_config` illisibles.
⚠️ Changer les clés VAPID → supprimer `push_abonnements` d'abord.
Clés applicatives (`TICKETMASTER_API_KEY`, `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`, `CONTACT_EMAIL`, `PUBLIC_BASE_URL`, `VAPID_*`) configurables depuis `/admin`.
`RESEND_WEBHOOK_SECRET` : clé de signature Svix pour l'endpoint `POST /api/webhooks/resend` (bounces email). À copier depuis le dashboard Resend → Webhooks.

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
Spring Security form login → cookie `SESSION` (JDBC-backed, survit aux restarts). Mots de passe hashés en bcrypt.
Détails : [wiki Authentification](https://codeberg.org/Kwaite/strapontin/wiki/Authentification)

## Dédoublonnage inter-sources

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, fuzzy Levenshtein normalisé ≥ 0.85.
→ Détails : [wiki Architecture](https://codeberg.org/Kwaite/strapontin/wiki/Architecture)

## UI / Design system (v2 — Thymeleaf)

- **Thymeleaf** + Layout Dialect — templates dans `java/web/src/main/resources/templates/`
- **CSS vanilla** — variables CSS pour thème clair/sombre, dans `static/css/`
- **PWA** : service worker JS vanilla + push. Cap 10 abonnements/user.
- **Badge "Nouveau"** : basé sur `user_matches.vu_le IS NULL` — remis à `NULL` à la visite de `/`. Affiché sur la sidebar desktop et la bottom nav mobile. `notification_envoyee_le` est une colonne distincte pour le suivi des envois email/push.
- **Thème** : cookie `theme` (1 an) + `user_config.theme` → pas de FOUC
- **Navigation source de vérité** : `ModeleGlobalAdvice` (@ControllerAdvice) injecte `navPrincipal`, `navSecondaire`, `navFooter`, `pagesDrawer` dans tous les templates via `@ModelAttribute`
- **Fragment onglets** : `layout/fragments/onglets.html` — réutilisé par `admin.html` et `parametres.html`
- **`hx-boost="true"`** sur `<body>` + `hx-target="#contenu-principal"` + `hx-select` + `hx-swap="outerHTML"` — navigation via XHR, swap ciblé sur `<main id="contenu-principal">`. `HtmxModelAdvice` (@ControllerAdvice) injecte `reqHtmx` : sidebar/nav masquées côté serveur sur requêtes HTMX (payload -30 à -50 %). `initTout()` rebinde les handlers sur `DOMContentLoaded` et `htmx:afterSettle`
- **Toasts** : Popover API + `initToasts()` dans `app.js`. Timer par classe : 5 s (succes/info), 10 s (avertissement), pas d'auto-dismiss (erreur). `afficherToast(message, type)` crée un toast depuis JS (callbacks AJAX). Flash PRG → template → `initToasts()` pour les actions form.
- **Escape Thymeleaf** : apostrophe dans un paramètre de fragment `~{...}` → impossible avec chaîne littérale. Utiliser une variable modèle `${var}` ou éviter l'apostrophe (`'Sections administration'`). `\'`, `''` et `|...|` sont tous invalides dans ce contexte.

## Schéma PostgreSQL

Migrations Flyway dans `java/web/src/main/resources/db/migration/` — ne jamais modifier à la main.
`users.id` : type `text` (UUID généré par Java).
Détails : [wiki Schéma](https://codeberg.org/Kwaite/strapontin/wiki/Schéma-de-base-de-données)

## Conventions Java

- Identifiants en français (`nombreUtilisateurs`, `calculerTotal`)
- Logging : SLF4J, jamais `System.out`
- **Tests Java** : `mvn -pl core,sources,web test` (unitaires). `mvn -pl worker test -am` requiert Docker (Testcontainers).
- **Tests E2E** : Playwright (`e2e/`) — 60 tests (smoke tests + flux fonctionnels catalogue, admin), nécessite LXC dev actif (port 8080)
  - Lancer : `cd e2e && npx playwright test`
  - Credentials : `E2E_IDENTIFIANT=kwaite E2E_MOT_DE_PASSE=... npx playwright test` (si session expirée)
  - Résultats : `e2e/test-results/results.json` (reporter JSON)
- Nommage tests : `<methode>_<scenario>_<resultatAttendu>`

## Skills projet (`.claude/commands/`)

| Skill         | Usage                                                                 |
| ------------- | --------------------------------------------------------------------- |
| `/deploy-dev` | Build Maven (avec tests) + scp JARs + restart LXC dev + tail logs     |
| `/test`       | Tests unitaires Maven + E2E Playwright + résumé des résultats         |
| `/pre-pr`     | Wiki + CLAUDE.md + changelog + README + commit docs + `tea pr create` |

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
