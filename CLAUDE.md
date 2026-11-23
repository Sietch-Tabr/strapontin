# CLAUDE.md — strapontin (strapontin.kwaite.fr)

## Architecture — monorepo Maven

`java/{core,sources,web,worker,exploitation-commun,mise-a-jour,exploitation-controle}` (BOM parent `java/pom.xml`, Java 27, Spring Boot 4.2.0) — `core` : normalisation/dédup/types partagés ; `exploitation-commun` : bibliothèque des modules d'exploitation (versions, chemins, verrou, contrat de mise à jour) ; `mise-a-jour` : mise à jour depuis l'admin, root, sans Spring ; `exploitation-controle` : commandes d'exploitation, root ; `sources` : clients TM/Eventim/Deezer ; `web` : REST API + Vue SPA (8080) ; `worker` : Quartz (8081). `scripts/` : provisionnement/déploiement/sauvegarde — noms auto-descriptifs sauf `restore-test.sh` (test de restauration à la demande, jamais en CI, voir section Sauvegarde).

## Architecture module web — couche service/repository

Pattern 3-couches : `XxxController` (HTTP) → `XxxService` (`@Transactional`) → `XxxRepository` (SQL pur, `NamedParameterJdbcTemplate`, package-private) — non-universel, une vingtaine de services anciens/simples n'ont pas de repository dédié, mais c'est la référence pour tout nouveau service non trivial. `GlobalExceptionHandler` : `NoResourceFoundException`→404, `HttpRequestMethodNotSupportedException`→405, `AuthenticationException`→401, `DuplicateKeyException`→409, `DataAccessException`→500.

**Services notables** : `SourceConcerts`/`SourceAvecFetchLot` (module `sources`) rend `WorkerService` source-agnostique — ajouter une source = une classe + un bean Spring, identifiants salle préfixés (`"tm:venueId"`). `WorkerService`/`AdminService`/`EmailService` sont scindés par responsabilité (`WorkerStatsService`/`WorkerFetchService`/`WorkerPurgeService`/`WorkerRevocationService` ; `ConfigAdminService`/`JobsAdminService` ; `EmailService`/`EmailAlerteService`/`EmailSender`). `core.BilanFetch`/`sources.SourceAvecBilanFetch` : bilan de panne agrégé partagé par toutes les sources (escalade `logs_admin error` sur panne totale), plutôt qu'un mécanisme par source.

**Détail par package** (chargé automatiquement en travaillant dans le dossier) : persistance, résolution salle/artiste, fusions [`worker…/persister/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/persister/CLAUDE.md) ; santé des sources, référentiel, signalements [`worker…/sante/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/sante/CLAUDE.md) ; seuils de correspondance floue [`core…/core/CLAUDE.md`](java/core/src/main/java/fr/strapontin/core/CLAUDE.md) ; enrichissement Wikidata/OSM/Deezer [`worker…/enrichissement/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/enrichissement/CLAUDE.md) ; jobs Quartz [`worker…/worker/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/CLAUDE.md) ; Eventim par lieu/villes [`sources…/sources/CLAUDE.md`](java/sources/src/main/java/fr/strapontin/sources/CLAUDE.md) ; module web [`web…/web/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/CLAUDE.md) ; scripts de sauvegarde/restauration [`scripts/CLAUDE.md`](scripts/CLAUDE.md).

**Web** ([`web/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/CLAUDE.md)) : sécurité admin/sessions, pagination admin, alertes internes, VAPID, Bean Validation, piège `@ModelAttribute`/record primitif. ⚠️ Ne jamais appeler une méthode `REQUIRES_NEW`+`SERIALIZABLE` depuis une transaction déjà ouverte : épuisement garanti du pool HikariCP (incident réel). **Onboarding** (`ModaleEtapes.vue`) : [`.claude/rules/frontend-vue.md`](.claude/rules/frontend-vue.md).

**okhttp/Resend (KMP), Jackson 3 uniquement** : pièges de dépendances dans [`sources…/sources/CLAUDE.md`](java/sources/src/main/java/fr/strapontin/sources/CLAUDE.md) (revérifier `dependency:tree` à chaque montée de Resend).

Historique jalon par jalon : [wiki Roadmap](https://github.com/Sietch-Tabr/strapontin/wiki/Roadmap). Détails par domaine : [wiki Architecture](https://github.com/Sietch-Tabr/strapontin/wiki/Architecture), [wiki Sources-de-données](https://github.com/Sietch-Tabr/strapontin/wiki/Sources-de-données), [wiki Sécurité](https://github.com/Sietch-Tabr/strapontin/wiki/Sécurité).

## Stack technique

- **Spring Boot 4.2.0** + **Vue 3 + Vite 8** (`front/` → `static/`) — port 8080. Thymeleaf et HTMX supprimés.
- **Vitest 5** (`front/vitest.config.js`, #781) : tests unitaires des fonctions pures partagées entre composants (`front/src/utils/`), sans montage de composant ni jsdom.
- `npm run build`/`npm ci` n'émettent aucun avertissement (dette déclarée vide) : [`front/CLAUDE.md`](front/CLAUDE.md).
- **Spring Security 7** — auth session, TOTP 2FA optionnel (limitation de débit en fenêtre glissante, `securite.LimiteurDebit`) ; `anyRequest().authenticated()` — non-API sans session → redirect `/login`, API → 401 JSON
- **Spring Data JDBC** + PostgreSQL 19 — données persistantes. `statement_timeout=30s` / `idle_in_transaction_session_timeout=30s` (une transaction bloquante épuiserait sinon le pool HikariCP).
- **Quartz** — jobs background (fetch concerts) ; `threadPool.threadCount=10` (virtual threads)
- **Flyway 13** — migrations DB, un seul jeu (`java/web/src/main/resources/db/migration/`) appliqué aussi par les tests worker. Conventions : [`.claude/rules/sql-migrations.md`](.claude/rules/sql-migrations.md).
- **Resend** — notifications email (tier gratuit 3 000/mois) ; gabarits HTML via `EmailTemplates.java` (text blocks Java). Garde-fous de quota/anti-boucle : [`web/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/CLAUDE.md).
- **Web Push (VAPID)** — notifications push PWA, `com.interaso:webpush` (crypto du JDK, seule dépendance `kotlin-stdlib`) derrière `core.ClientWebPush` ; ni `web-push`, ni `httpclient` 4, ni `jose4j`
- **Maven** — build (`mvn -f java/pom.xml -pl web,worker,mise-a-jour,exploitation-controle -am package`). Version CI-friendly (#764) : `<revision>` dans `java/pom.xml` (parent), `${revision}` dans les 7 pom.xml enfants — un seul bump met à jour tout le monorepo.
- **Checkstyle/PMD** (Java) + **ESLint/stylelint** (front) — analyse statique bloquante (`failOnViolation=true`, CI et local identiques), sans baseline de dette. SpotBugs non activé (mis de côté au spike Java 25 initial, hors périmètre du mode bloquant).

## Workflow de développement

### LXC

| Environnement | VMID | IP            | Notes                                           |
| ------------- | ---- | ------------- | ----------------------------------------------- |
| dev           | 114  | 192.168.20.78 | déploiement manuel via `/deploy-dev`            |
| prod          | 113  | 192.168.20.79 | production — `/deploiement-prod` (JARs attestés de la CI GitHub) |

Logs : `journalctl -fu strapontin-web`/`strapontin-worker` (depuis le LXC), ou `ssh root@<ip> "journalctl -u strapontin-web -n 50 --no-pager"` depuis le Mac. Actuator : `:9090` (web) / `:9091` (worker) — **pas** `:8080`, le port applicatif/SPA. Accès app dev : `http://192.168.20.78:8080` — config locale (tunnel SSH, URL sandbox) dans `CLAUDE.local.md`.

### Build et déploiement LXC dev

Utiliser le skill `/deploy-dev` (build Maven avec tests + livraison des scripts et JARs + `exploitation/update.sh` + tail logs) — commandes manuelles dans le skill lui-même si besoin d'un fallback.

### Gestion des issues (GitHub) et Git

CLI : `gh`. `gh issue view N --comments` ; toujours `--limit` sur `gh issue list` (30 résultats par défaut). Étiquettes : `bug`, `enhancement` (ex-Feature et Task), `question`, `duplicate`, `invalid`. Jalons : `gh api repos/{owner}/{repo}/milestones`. PR : `Closes #N` **dans le corps** (`--body-file`), mot-clé répété devant chaque ticket (`Closes #A, closes #B`), actif seulement vers `main`.

`main` : la CI tourne à chaque push sur `main` ou sur un tag `v*`, jamais avant le merge (rejouer les commandes des jobs avant la PR) ; aucun déploiement sans run vert (`/deploiement-prod`). Features : branche → PR → merge. Jobs, release et règles du dépôt : [`.github/CLAUDE.md`](.github/CLAUDE.md).

## Sauvegarde et restauration

Arborescence des LXC, livraison des scripts, `backup.sh`/`restore.sh`/`restore-test.sh` : [`scripts/CLAUDE.md`](scripts/CLAUDE.md).

## Variables d'environnement — `/etc/opt/strapontin/strapontin.env`

Généré par `provision.sh` (commun à web et worker, root, `chmod 600`, lu par systemd) :

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/strapontin
SPRING_DATASOURCE_USERNAME=<rôle applicatif PostgreSQL>
SPRING_DATASOURCE_PASSWORD=<mot de passe du rôle applicatif>
APP_CONFIG_SECRET=<clé AES-256-GCM de chiffrement de app_config>
BACKUP_INTERNAL_SECRET=<secret Bearer protégeant POST /api/interne/backup-statut>
INTEGRITY_INTERNAL_SECRET=<secret Bearer protégeant POST /api/interne/integrite-statut>
UPDATE_INTERNAL_SECRET=<secret Bearer protégeant POST /api/interne/mise-a-jour-statut>
SPRING_PROFILES_ACTIVE=dev|prod
```

Mécanisme des secrets `*_INTERNAL_SECRET` (403 si absent, baseline AIDE) : [`scripts/CLAUDE.md`](scripts/CLAUDE.md).

**Rotation des secrets** : `scripts/rotation-secrets.sh` (`--db`/`--app-config`/`--backup`/`--integrite`/`--mise-a-jour`/`--tous`, sur le LXC), avec retour arrière. Détail du rechiffrement d'`APP_CONFIG_SECRET` : [`scripts/CLAUDE.md`](scripts/CLAUDE.md) ; procédure : [wiki Sécurité](https://github.com/Sietch-Tabr/strapontin/wiki/S%C3%A9curit%C3%A9).

## Configuration applicative (`app_config`)

Chiffrement AES-256-GCM (`core.ConfigCipher`, détail : [`core…/core/CLAUDE.md`](java/core/src/main/java/fr/strapontin/core/CLAUDE.md)).
⚠️ Changer `APP_CONFIG_SECRET` sans `scripts/rotation-secrets.sh --app-config` → toutes les valeurs `app_config` et secrets TOTP illisibles.
⚠️ Changer les clés VAPID → supprimer `push_abonnements` d'abord.
Clés applicatives (`TICKETMASTER_API_KEY`, `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`, `CONTACT_EMAIL`, `PUBLIC_BASE_URL`, `VAPID_*`) configurables depuis `/admin`.
`RESEND_WEBHOOK_SECRET` : clé de signature Svix pour l'endpoint `POST /api/webhooks/resend` (bounces email). À copier depuis le dashboard Resend → Webhooks.
`ResendWebhooksAdminService` (rejeu des webhooks Resend en échec) : [`web/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/CLAUDE.md).

## Sources de données

- **Ticketmaster Discovery v2**, **Eventim France** : sources principales (Eventim par lieu/villes : [`sources…/sources/CLAUDE.md`](java/sources/src/main/java/fr/strapontin/sources/CLAUDE.md)).
- **Sites de salles** (`SourceSiteSalle`) : Le Triton, EMB, Usine à Chapeaux, Allmol (Antilles, Guyane), un passage par jour, `robots.txt` respecté ([`sources…/sources/CLAUDE.md`](java/sources/src/main/java/fr/strapontin/sources/CLAUDE.md)).
- **Digitick** : retirée (v3.0.1, #1263), billetteries migrées vers Eventim ; valeur d'enum `digitick` conservée (non retirable en PostgreSQL).
- **Deezer Search API** : autocomplete artiste (`/artistes`), sans clé ; remplace MusicBrainz.
- **Wikidata (SPARQL) + OSM (Nominatim)** : enrichissement nocturne (noms canoniques, alias, Deezer ID) ; identifiant Wikidata d'une salle lu sur l'objet OSM que Nominatim trouve autour de sa position (`NominatimSalleSource`, 1 req/s) ; Overpass retiré en 3.0.2 (instance publique saturée) ; rotation hebdomadaire `jour_batch`.
- **Wikidata Search (REST) + Nominatim** : recherche interactive à l'ajout (web, `SalleService`), sans clé ; Nominatim : `User-Agent` obligatoire, 1 req/s cadencé via `CadenceurAppelsPartage` (partagé en base) côté worker (`sources.ClientNominatim`, dernier recours de `ResolveurSalleParConcert`) comme côté web (`SalleRechercheNominatimService`).
- **Écartées** : Bandsintown (401, #65), MusicBrainz (IP blacklistée par Cloudflare, #293), Dice (scraping interdit par les CGU).

## Jobs background (Quartz)

Paradigme : **pré-fetch en arrière-plan, jamais d'appel API depuis le browser**. Cron adaptatif (#721) : `0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?` — 11 cycles/jour. Liste des jobs, quotas TM, cadencement des appels externes, watchdogs : détail complet dans [`worker/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/CLAUDE.md).

## Authentification

Invite-only, TOTP 2FA optionnel, Spring Security session-based. Détail complet : [`web/securite/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/securite/CLAUDE.md).

## Dédoublonnage inter-sources

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, puis floue. Résolution UUID-first (`salle_sources`/`artiste_sources`) avant toute réconciliation floue ; un seul algorithme flou par entité (`core.CorrespondanceSalle`/`core.CorrespondanceArtiste`), partagé `sources`/`web`/`worker`. Point unique de résolution avant écriture : `PersisteurConcertsDb.resoudreOuCreerEvenement`. La résolution de salle filtre toujours par département ; tout rapprochement par position ne se fie qu'à une position fiable.

Ordre de traitement, garde-fous et incidents : [`worker…/persister/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/persister/CLAUDE.md). Seuils : [`core…/core/CLAUDE.md`](java/core/src/main/java/fr/strapontin/core/CLAUDE.md). Justification des seuils : [wiki Sources-de-données](https://github.com/Sietch-Tabr/strapontin/wiki/Sources-de-donn%C3%A9es).

## Modèle événement canonique

`evenements` (identité canonique) + `observations` (une ligne par `(evenement_id, source, url)`) ; `concerts` est une **vue** en lecture seule, toute écriture passe par `evenements`/`observations`. Détail et pattern de fusion : [`worker…/persister/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/persister/CLAUDE.md).

`signalements_matching` (point d'entrée unique des signalements de matching) et observabilité des jobs multi-lots : [`worker…/sante/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/sante/CLAUDE.md), [`worker…/worker/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/CLAUDE.md).

## Conventions détaillées (path-scoped rules)

UI/Vue, schéma SQL, style Java, tests E2E — scopées par type de fichier, chargées automatiquement en travaillant dessus plutôt que dupliquées ici : [`.claude/rules/frontend-vue.md`](.claude/rules/frontend-vue.md), [`.claude/rules/sql-migrations.md`](.claude/rules/sql-migrations.md), [`.claude/rules/backend-java.md`](.claude/rules/backend-java.md), [`.claude/rules/e2e-testing.md`](.claude/rules/e2e-testing.md).

Commandes de test (à connaître même hors édition d'un fichier) : `mvn -f java/pom.xml clean test -pl core,sources,web,worker,exploitation-commun,mise-a-jour,exploitation-controle -am` (Docker requis, Testcontainers) ; `cd front && npm run test:unit` (Vitest, fonctions pures) ; `cd e2e && npx playwright test` (jamais `--project`, 5 navigateurs). Analyse statique (bloquante en CI) : `PMD_JAVA_DISABLE_AUX_CLASSPATH_WARNINGS=true mvn -f java/pom.xml -pl core,sources,web,worker,exploitation-commun,mise-a-jour,exploitation-controle -am compile checkstyle:check pmd:check pmd:cpd-check spotless:check -DskipTests` ; `cd front && npm run lint && npm run lint:css`.

## Skills projet (`.claude/commands/`, `.claude/skills/`)

Seul l'outillage partagé de `.claude/` est versionné (liste blanche dans `.gitignore`, contrôlée en CI par `scripts/verifier-outillage.sh`) : jamais de données de prod, de configuration ou mémoire personnelle, ni d'accès à l'infrastructure. Ce dernier passe par `.claude/scripts/acces.local.sh`, fichier local créé depuis `acces.exemple.sh`.

- `/deploy-dev` : Build Maven (avec tests) + scp JARs + restart LXC dev + tail logs
- `/deploiement-prod` : lancé par l'utilisateur, déploie dev ou prod depuis les JARs attestés d'un run vert de `main` (sauvegardes, `update.sh`, contrôles)
- `/test` : Tests Java (4 modules) + Vitest + analyse statique + E2E Playwright, résumé chiffré par suite
- `/pre-pr` : Wiki + CLAUDE.md + changelog + README + commit docs + `gh pr create`
- `/suivi-fiabilite` : Check delta 360° du suivi de fiabilité prod — santé/jobs/données/tickets, état dans la mémoire personnelle (prérequis locaux listés dans le skill)
- `check-deps` : Mises à jour Maven/npm/stack infra + changelogs — auto en début de jalon (`"Tickets du jalon X.Y.Z à implémenter :"`, mises à jour sûres appliquées au jalon) ; résumé + proposition de ticket en invocation ad-hoc
- `/check-pipeline` : Statut et journaux de la dernière exécution de la CI sur `main`, via `gh run` (aucun jeton à passer)
- `/ticket` : Création de ticket : doublon recherché, constat/analyse/attendu sans solution, étiquette et jalon vérifiés côté serveur
- `/cloture-jalon` : Lancé par l'utilisateur uniquement : merge de la PR, pipeline, vérification prod, tag et release lisible, fermeture du jalon, nettoyage, wiki et mémoire
- `/restauration-dev` : Base du dev remplacée par une copie de la prod, configuration du dev conservée (jamais `restore.sh`)

## Outils Claude Code

- **code-review-graph** (MCP) — **IMPORTANT : toujours l'utiliser AVANT Grep/Glob/Read** pour explorer le code (plus rapide, moins de tokens, contexte structurel — appelants, dépendants, couverture de tests). `semantic_search_nodes`/`query_graph` au lieu de Grep, `get_impact_radius` au lieu de tracer les imports à la main, `detect_changes`+`get_review_context` pour une revue de code, `get_architecture_overview` pour une vue d'ensemble. Ne se rabattre sur Grep/Glob/Read que si le graphe ne couvre pas le besoin — le graphe se met à jour automatiquement (hooks).
- **everything-search** (MCP) — recherche fichiers via Spotlight, évite le bruit `node_modules/`.
