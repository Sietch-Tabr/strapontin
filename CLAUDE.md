# CLAUDE.md — strapontin (strapontin.kwaite.fr)

## Architecture — monorepo Maven

```
strapontin/
├── java/
│   ├── pom.xml       ← parent BOM (Java 25, Spring Boot 4.0.6)
│   ├── core/         ← normalisation, dédup, types partagés
│   ├── sources/      ← clients TM, Eventim, Deezer
│   ├── web/          ← Spring Boot REST API + Vue SPA (port 8080)
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
- `SourcesController` / `SourcesService` (`/api/admin/sources`, `/api/admin/salles/{uuid}/digitick-slug|tester-digitick|fetch`) : onglet Sources admin — statuts des sources + config slugs Digitick
- `VapidService` : génération clés VAPID EC P-256 (extrait d'`AdminService` en v2.2.0)
- `BanniereService` / `WorkerProxyService` : extraits d'`AdminService` en v2.2.3 (bannière système ; proxy HTTP worker via le `RestClient` partagé). `GenerateurSecrets` (package `securite`) : tokens/hash extraits d'`AdminService`
- `FiltreConcertsBuilder` (package `concert`) : fragments WHERE/ORDER BY/pagination communs à `DecouverteService` et `SpectacleService`
- `SourceConcerts` / `SourceAvecFetchLot` (module `sources`, v2.3.0) : `WorkerService` source-agnostique — ajouter une source = créer une classe + bean Spring. Identifiants salle préfixés par source (`"tm:venueId"`). Voir wiki Architecture.
- `core` : `QuotaTM` (type partagé) et `UtilitairesChaine.echapperJson` mutualisés en v2.2.3
- `SpectacleService` : COUNT et filtres disponibles calculés en parallèle via `CompletableFuture` (threads virtuels)
- `RecalculerMatchesService` : recalcul parallèle par utilisateur via `Executors.newVirtualThreadPerTaskExecutor()`, 1 transaction par user (`TransactionTemplate`)
- `WebConfiguration` : beans `ObjectMapper` + `RestClient` (timeout 5 s / 10 s)
- Référence : `PersisterConcerts` + `RecalculerMatchesService` dans `java/worker/`

## Stack technique (v2.2.0)

- **Spring Boot 4.0.6** + **Vue 3 + Vite** (`front/` → `static/`) — port 8080. Thymeleaf et HTMX supprimés.
- **Spring Security 7** — auth session, TOTP 2FA optionnel (bucket4j rate-limiting) ; `anyRequest().authenticated()` — non-API sans session → redirect `/login`, API → 401 JSON
- **Spring Data JDBC** + PostgreSQL 18 — données persistantes
- **Quartz** — jobs background (fetch concerts)
- **Flyway 12** — migrations DB (`java/web/src/main/resources/db/migration/`)
- **Resend** — notifications email (tier gratuit 3 000/mois) ; gabarits HTML via `EmailTemplates.java` (text blocks Java)
- **web-push (VAPID)** — notifications push PWA
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
BACKUP_INTERNAL_SECRET=<secret Bearer protégeant POST /api/interne/backup-statut>
```

`BACKUP_INTERNAL_SECRET` (v2.2.2) : généré par `provision.sh`. `backup-local.sh` le lit dans `.env` et l'envoie via `Authorization: Bearer`. Absent → l'endpoint répond 403 (notifications de backup perdues, mais le dump continue).

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

| Job                     | Déclenchement           | Description                                                               |
| ----------------------- | ----------------------- | ------------------------------------------------------------------------- |
| `FetchArtisteJob`       | Ajout artiste + cron 2h | Fetch TM + Eventim par nom artiste                                        |
| `FetchArtistesBatchJob` | Cron 2h                 | Fetch TM en lots de 20 attractionIds                                      |
| `FetchRegionJob`        | Cron 2h + manuel        | Fetch TM + Eventim par département                                        |
| `FetchSalleJob`         | Ajout salle + cron 2h   | Fetch par identifiants préfixés (`CLE_IDENTIFIANTS`, ex. `"tm:venueId"`)  |
| `RecalculerMatchesJob`  | Après fetch             | Recalcule `user_matches` pour tous les users                              |
| `PurgerConcertsJob`     | Cron quotidien (minuit) | Supprime concerts passés depuis > 7 jours                                 |
| `PurgerLogsAdminJob`    | Cron quotidien (0h15)   | Supprime les `logs_admin` > 30 jours et `fetch_jobs_historique` > 7 jours |
| `RevoquerAdminsJob`     | Cron horaire (H:00)     | Applique les révocations admin dont la date est dépassée                  |

Rate limiting TM : max 2 jobs simultanés (3 → HTTP 429). Quota TM persisté dans `app_config` (`TM_QUOTA_JSON`).
`FetchArtistesBatchJob`, `FetchRegionJob`, `RecalculerMatchesJob` annotés `@DisallowConcurrentExecution`. `WatchdogWorkerService` (`@Scheduled`, côté web) : alerte email si worker inactif > 3 h.

## Authentification

Invite-only. Login par identifiant (insensible à la casse). Rôles : `user` | `admin`.
2FA TOTP optionnel pour tous les utilisateurs — enrollment via `/parametres/totp`, challenge automatique à la connexion si activé. Cap : 500 artistes / 200 salles par user.
Algorithme TOTP conforme RFC 6238 (HMAC-SHA1, 30 s, ±1 fenêtre, saisie tolérante aux espaces). Réinitialisation 2FA par un admin depuis `/admin` (bouton « Réinit. 2FA », endpoint `POST /api/admin/utilisateurs/{id}/reset-totp`) pour un compte bloqué. Succès et échecs d'auth (login, rate-limit, TOTP) consignés dans `logs_admin` (source `auth`). Rate-limit login : 15 tentatives / 15 min par IP (bucket4j → 429).
Spring Security form login → cookie `SESSION` (JDBC-backed, survit aux restarts, `Max-Age=2592000`, `HttpOnly`, `SameSite=Strict` — configuré via `DefaultCookieSerializer`, pas via `application.properties`). Mots de passe hashés en bcrypt.
Détails : [wiki Authentification](https://codeberg.org/Kwaite/strapontin/wiki/Authentification)

## Dédoublonnage inter-sources

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, fuzzy Levenshtein normalisé ≥ 0.85.
Résolution UUID-first (v2.3.0, ticket #621) : `PersisterConcerts.resoudreIdentitesSources` consulte `salle_sources`/`artiste_sources` avant la réconciliation floue. Absorbe les renommages de salle. Pour les salles TM absentes de `salle_sources`, upsert automatique dans `salles` + `salle_sources` (ticket #633). Les passes de réconciliation propagent `salleUuid`/`artisteId` (ticket #632).
→ Détails : [wiki Architecture](https://codeberg.org/Kwaite/strapontin/wiki/Architecture)

## UI / Design system (v2.2.0 — Vue SPA)

- **Vue 3 + Vite** — SPA intégralement côté client. `front/src/pages/` (pages), `front/src/components/` (composants), `front/src/composables/` (logique réutilisable)
- **CSS vanilla** — variables CSS pour thème clair/sombre, dans `static/css/`
- **PWA** : service worker JS vanilla + push. Cap 10 abonnements/user. Mise à jour automatique : `controllerchange` → `window.location.reload()` dans `App.vue`. `/icons/` en network-first, `/images/` en stale-while-revalidate. Bumper `CACHE_NOM` dans `sw.js` pour forcer une nouvelle installation.
- **Badge "Nouveau"** : basé sur `user_matches.vu_le IS NULL` — remis à `NULL` à la visite de `/`. Affiché sur la sidebar desktop et la bottom nav mobile. `notification_envoyee_le` est une colonne distincte pour le suivi des envois email/push.
- **Thème** : cookie `theme` (1 an) + `user_config.theme` → pas de FOUC
- **Navigation** : Vue Router (`front/src/router/`) — sidebar desktop, bottom nav + drawer mobile. `useEstMobile()` (`front/src/composables/useBreakpoint.js`, seuil 1024 px) contrôle le rendu conditionnel via `v-if` — pas de duplication DOM ni de `display: none`.
- **Pages à onglets** (`admin`, `parametres`) : orchestrateur léger + sous-composants autonomes (`KeepAlive` + `v-if`). Chaque sous-composant fetch ses données dans `onMounted`. `<select v-model>` sur mobile (`v-if="estMobile"`), `<nav v-else>` sur desktop — rendu conditionnel via `useEstMobile()`. Onglet actif géré par Vue (`:class="{ actif }"`) — pas de sélecteurs CSS `:has()`.
  - Admin : `front/src/components/admin/` (AdminJobs, AdminSources, AdminLogs, AdminConfig, AdminBanniere, AdminUtilisateurs)
  - Paramètres : `front/src/components/parametres/` (ParametresPreferences, ParametresNotifications, ParametresCompte, ParametresDonnees)
- **Toasts** : Popover API, `afficherToast(message, type)` retourne un `id` pour fermeture ciblée. `App.vue` gère offline/online : toast persistant à la perte de connexion, fermé à la reconnexion.

## Schéma PostgreSQL

Migrations Flyway dans `java/web/src/main/resources/db/migration/` — ne jamais modifier à la main.
`users.id` : type `text` (UUID généré par Java).
**v2.3.0 (V23)** : `salles.salle_uuid uuid`, tables `artistes`, `salle_sources`, `artiste_sources`, colonnes `salle_uuid`/`artiste_id` sur `concerts`, `user_salles`, `user_artistes`. Migration additive.
**v2.3.0 (V24)** : nettoyage post-backfill #628 — `salles.salle_uuid` → `salles.id` (PK uuid), ancienne PK text → `salles.salle_cle` (UNIQUE) ; PK `user_salles` = `(user_id, salle_uuid)` ; PK `user_artistes` = `(user_id, artiste_id)` ; suppression `salle_id`/`tm_venue_id`/`artiste_normalise`/`attraction_id`/`concerts.salle_cle`.
Détails : [wiki Schéma](https://codeberg.org/Kwaite/strapontin/wiki/Schéma-de-base-de-données)

## Conventions Java

- Identifiants en français (`nombreUtilisateurs`, `calculerTotal`)
- Logging : SLF4J, jamais `System.out`
- **Tests Java** : `mvn -pl core,sources,web test` (unitaires). `mvn -pl worker test -am` requiert Docker (Testcontainers).
- **Tests E2E** : Playwright (`e2e/`), nécessite LXC dev actif (port 8080)
  - **Deux comptes dédiés** créés par `bash scripts/seed-e2e.sh root@192.168.20.78` : `e2eadmin` (admin) et `e2euser` (user). Idempotent. Le seed supprime et recrée les users (CASCADE DELETE) — après relance, supprimer `e2e/tests/e2e/.auth-*.json` pour forcer la reconnexion.
  - global-setup ouvre 2 sessions (`.auth-admin.json`, `.auth-user.json`) via `POST /api/auth/login`, puis réinitialise les buckets rate-limit (`DELETE /api/salles/rate-limits/signalement`, profil `dev` uniquement).
  - Tests "Scénario: API auth" et test signalement valide restreints à chromium pour ne pas épuiser le rate limiter bucket4j
  - `invitation_tokenValide` ne tourne que sur chromium — le test crée sa propre invitation via l'API admin (indépendant du seed)
  - Lancer : `cd e2e && npx playwright test` (mdp surchargeable via `E2E_ADMIN_MOT_DE_PASSE` / `E2E_USER_MOT_DE_PASSE`)
- Nommage tests : `<methode>_<scenario>_<resultatAttendu>`

## Skills projet (`.claude/commands/`)

| Skill         | Usage                                                                 |
| ------------- | --------------------------------------------------------------------- |
| `/deploy-dev` | Build Maven (avec tests) + scp JARs + restart LXC dev + tail logs     |
| `/test`       | Tests unitaires Maven + E2E Playwright + résumé des résultats         |
| `/pre-pr`     | Wiki + CLAUDE.md + changelog + README + commit docs + `tea pr create` |

## Migration Vue 2.2.0

Plan complet : [wiki Migration-Vue-2.2.0](https://codeberg.org/Kwaite/strapontin/wiki/Migration-Vue-2.2.0)

**État** : migration terminée (Epics A–F). Aucun ViewController ni dépendance Thymeleaf/HTMX dans le module web. `EmailTemplates.java` remplace les 7 gabarits HTML Thymeleaf pour les emails (text blocks Java, zéro dépendance).

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
