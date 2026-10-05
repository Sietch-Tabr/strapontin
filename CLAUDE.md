# CLAUDE.md — strapontin (strapontin.kwaite.fr)

## Architecture — monorepo Maven

`java/{core,sources,web,worker}` (BOM parent `java/pom.xml`, Java 26, Spring Boot 4.1.1) — `core` : normalisation/dédup/types partagés ; `sources` : clients TM/Eventim/Deezer ; `web` : REST API + Vue SPA (8080) ; `worker` : Quartz (8081). `scripts/` : provisionnement/déploiement/sauvegarde — noms auto-descriptifs sauf `restore-test.sh` (test de restauration à la demande, jamais en CI, voir section Sauvegarde).

## Architecture module web — couche service/repository

Pattern 3-couches : `XxxController` (HTTP) → `XxxService` (`@Transactional`) → `XxxRepository` (SQL pur, `NamedParameterJdbcTemplate`, package-private) — non-universel, une vingtaine de services anciens/simples n'ont pas de repository dédié, mais c'est la référence pour tout nouveau service non trivial. `GlobalExceptionHandler` : `NoResourceFoundException`→404, `HttpRequestMethodNotSupportedException`→405, `AuthenticationException`→401, `DuplicateKeyException`→409, `DataAccessException`→500.

**Services notables** : `SourceConcerts`/`SourceAvecFetchLot` (module `sources`) rend `WorkerService` source-agnostique — ajouter une source = une classe + un bean Spring, identifiants salle préfixés (`"tm:venueId"`). `WorkerService`/`AdminService`/`EmailService` sont scindés par responsabilité (`WorkerStatsService`/`WorkerFetchService`/`WorkerPurgeService`/`WorkerRevocationService` ; `ConfigAdminService`/`JobsAdminService` ; `EmailService`/`EmailAlerteService`/`EmailSender`). `core.BilanFetch`/`sources.SourceAvecBilanFetch` : bilan de panne agrégé partagé par toutes les sources (escalade `logs_admin error` sur panne totale), plutôt qu'un mécanisme par source.

**Détail par package** (chargé automatiquement en travaillant dans le dossier) : persistance, résolution salle/artiste, fusions [`worker…/persister/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/persister/CLAUDE.md) ; santé des sources, référentiel, signalements [`worker…/sante/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/sante/CLAUDE.md) ; seuils de correspondance floue [`core…/core/CLAUDE.md`](java/core/src/main/java/fr/strapontin/core/CLAUDE.md) ; enrichissement Wikidata/OSM/Deezer [`worker…/enrichissement/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/enrichissement/CLAUDE.md) ; jobs Quartz [`worker…/worker/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/CLAUDE.md) ; Eventim par lieu/villes [`sources…/sources/CLAUDE.md`](java/sources/src/main/java/fr/strapontin/sources/CLAUDE.md) ; module web [`web…/web/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/CLAUDE.md) ; scripts de sauvegarde/restauration [`scripts/CLAUDE.md`](scripts/CLAUDE.md).

**Sécurité admin/sessions, pagination admin, alertes internes, VAPID** : détail complet dans [`java/web/src/main/java/fr/strapontin/web/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/CLAUDE.md) (chargé automatiquement en travaillant sur ce module). ⚠️ Rappel critique : ne jamais appeler une méthode `REQUIRES_NEW`+`SERIALIZABLE` depuis une transaction déjà ouverte — épuisement garanti du pool HikariCP (incident réel).

**Bean Validation, piège `@ModelAttribute`/record primitif** : détail dans [`java/web/src/main/java/fr/strapontin/web/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/CLAUDE.md). **Onboarding** (`ModaleEtapes.vue`) : détail dans [`.claude/rules/frontend-vue.md`](.claude/rules/frontend-vue.md).

**okhttp/Resend (Kotlin Multiplatform)** : l'artefact `com.squareup.okhttp3:okhttp` est vide côté JVM depuis okhttp 5.0.0 ; resend-java (≥ 4.28.0) dépend directement de `okhttp-jvm`, dont la version est seulement gérée dans le pom parent. Revérifier par `dependency:tree` à chaque montée de Resend qu'aucun `okhttp` vide n'est tiré (un oubli côté worker a déjà causé un `NoClassDefFoundError` silencieux sur tout email worker). **Jackson 3 uniquement** (`tools.jackson`, `JsonMapper` autoconfiguré par Spring Boot, aucun bean maison) : les `RestClient` des sources retiennent Jackson 3 dans le runtime web/worker, le module `sources` en dépend pour que ses tests désérialisent avec la même pile ; seules les annotations restent dans `com.fasterxml.jackson.annotation`.

Historique jalon par jalon : [wiki Roadmap](https://codeberg.org/Kwaite/strapontin/wiki/Roadmap). Détails par domaine : [wiki Architecture](https://codeberg.org/Kwaite/strapontin/wiki/Architecture), [wiki Sources-de-données](https://codeberg.org/Kwaite/strapontin/wiki/Sources-de-données), [wiki Sécurité](https://codeberg.org/Kwaite/strapontin/wiki/Sécurité).

## Stack technique

- **Spring Boot 4.1.1** + **Vue 3 + Vite 8.1** (`front/` → `static/`) — port 8080. Thymeleaf et HTMX supprimés.
- **Vitest 4** (`front/vitest.config.js`, v2.6.2, #781) : tests unitaires des fonctions pures partagées entre composants (`front/src/utils/`), sans montage de composant ni jsdom — complémentaire à Playwright (flux utilisateur, rendu réel), pas redondant. Config dédiée séparée de `vite.config.js` pour ne pas embarquer les plugins Vue/PWA dans l'exécution des tests.
- `npm run build`/`npm ci` émettent des warnings connus et non actionnables (vite-plugin-pwa, workbox) : [`front/CLAUDE.md`](front/CLAUDE.md).
- **Spring Security 7** — auth session, TOTP 2FA optionnel (bucket4j rate-limiting) ; `anyRequest().authenticated()` — non-API sans session → redirect `/login`, API → 401 JSON
- **Spring Data JDBC** + PostgreSQL 18 — données persistantes. `statement_timeout=30s` / `idle_in_transaction_session_timeout=30s` (protection contre une transaction bloquante qui épuiserait le pool HikariCP, cf. incident v2.6.3). Tuning mémoire (`shared_buffers`/`work_mem`/`effective_cache_size`) laissé aux valeurs par défaut PostgreSQL — base ~16 MB (juillet 2026), aucun gain mesurable à un tuning mémoire à ce volume.
- **Quartz** — jobs background (fetch concerts) ; `threadPool.threadCount=10` (virtual threads)
- **Flyway 13** — migrations DB, un seul jeu (`java/web/src/main/resources/db/migration/`) appliqué aussi par les tests worker. Conventions : [`.claude/rules/sql-migrations.md`](.claude/rules/sql-migrations.md).
- **Resend** — notifications email (tier gratuit 3 000/mois) ; gabarits HTML via `EmailTemplates.java` (text blocks Java). Garde-fous de quota/anti-boucle : [`web/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/CLAUDE.md).
- **web-push (VAPID)** — notifications push PWA
- **Maven** — build (`mvn -f java/pom.xml -pl web,worker -am package`). Version CI-friendly (v2.6.1, #764) : `<revision>` dans `java/pom.xml` (parent), `${revision}` dans les 4 pom.xml enfants — un seul bump met à jour tout le monorepo.
- **Checkstyle/PMD** (Java) + **ESLint/stylelint** (front) — analyse statique bloquante depuis v2.9.1 (`failOnViolation=true`, CI et local identiques), après remédiation intégrale de l'existant à 0 violation plutôt qu'une baseline de dette masquée. SpotBugs non activé (mis de côté au spike Java 25 initial, hors périmètre du mode bloquant).

## Workflow de développement

### LXC

| Environnement | VMID | IP            | Notes                                           |
| ------------- | ---- | ------------- | ----------------------------------------------- |
| dev           | 114  | 192.168.20.78 | déploiement manuel via `/deploy-dev`            |
| prod          | 113  | 192.168.20.79 | production — Woodpecker déploie sur push `main` |

Logs : `journalctl -fu strapontin-web`/`strapontin-worker` (depuis le LXC), ou `ssh root@<ip> "journalctl -u strapontin-web -n 50 --no-pager"` depuis le Mac. Actuator : `:9090` (web) / `:9091` (worker) — **pas** `:8080`, le port applicatif/SPA. Accès app dev : `http://192.168.20.78:8080` — config locale (tunnel SSH, URL sandbox) dans `CLAUDE.local.md`.

### Build et déploiement LXC dev

Utiliser le skill `/deploy-dev` (build Maven avec tests + scp JARs vers `/opt/strapontin/{web,worker}.jar.new` + `scripts/update.sh` + tail logs) — commandes manuelles dans le skill lui-même si besoin d'un fallback.

### Gestion des issues (Codeberg) et Git

CLI : `tea` (pas `gh` — Codeberg est Gitea, incompatible avec `gh`). `tea issue view N` → toujours `-f "index,title,body"` (body absent par défaut). Labels : `Bug`, `Feature`, `Question`, `Task`, `Duplicate`, `Invalid`. PR : `Closes #N` **dans le corps** (`--description`), pas seulement le titre — Gitea ferme les issues via le corps, jamais via le titre.

`main` : Woodpecker déploie sur prod à chaque push (**jamais de push manuel sans build valide**). Features : branche `feature/xxx` → PR → merge. Pipeline (`.woodpecker/ci.yaml`) : test (Maven) + test-front (Vitest) → build-front → lint-front → static-analysis → build → backup → deploy, restreints à `main`. Le repo doit être marqué "trusted" (Settings → repo) pour autoriser les volumes Maven.
⚠️ **Agent CI (LXC 111, 2 Go RAM)** : les steps `front/` (`build-front`/`lint-front`) et `static-analysis` (Checkstyle+PMD) restent entièrement sérialisés : jamais deux `npm ci` concurrents sur le workspace partagé (`ENOTEMPTY` sur `node_modules`), jamais `static-analysis` en parallèle d'un step `front/` (OOM), `MAVEN_OPTS: -Xmx768m` sur `static-analysis` (mémoire résiduelle des Testcontainers de `test`). Aucune preuve que 2 Go permette de paralléliser.

## Sauvegarde et restauration

Scripts `backup.sh`/`restore.sh`/`restore-test.sh` (propriétaire de base, réalignement du mot de passe, LXC temporaire, jamais en CI) : [`scripts/CLAUDE.md`](scripts/CLAUDE.md).

## Variables d'environnement — `/opt/strapontin/.env`

Généré par `provision.sh` (commun à web et worker, `chmod 600`) :

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/strapontin
SPRING_DATASOURCE_USERNAME=<rôle applicatif PostgreSQL>
SPRING_DATASOURCE_PASSWORD=<mot de passe du rôle applicatif>
APP_CONFIG_SECRET=<clé AES-256-GCM de chiffrement de app_config>
BACKUP_INTERNAL_SECRET=<secret Bearer protégeant POST /api/interne/backup-statut>
INTEGRITY_INTERNAL_SECRET=<secret Bearer protégeant POST /api/interne/integrite-statut>
SPRING_PROFILES_ACTIVE=dev|prod
```

`BACKUP_INTERNAL_SECRET` (v2.2.2) : `backup-local.sh` le lit dans `.env` et l'envoie via `Authorization: Bearer`. Absent → l'endpoint répond 403 (notifications de backup perdues, mais le dump continue). `INTEGRITY_INTERNAL_SECRET` : même mécanisme pour `aide-check.sh`/`update.sh` → `IntegriteAlerteController`. `update.sh` vérifie aussi, avant de régénérer la baseline AIDE, qu'aucun script déployé (`backup`/`restore`/`update`/`rotation-secrets.sh`) n'a été modifié hors déploiement — sinon baseline conservée et alerte `modifie`.

**Rotation des secrets** (v3.0.1) : `scripts/rotation-secrets.sh` (`--db`/`--app-config`/`--backup`/`--integrite`/`--tous`, sur le LXC) sauvegarde `.env`, pose le nouveau secret, redémarre et restaure l'ancien `.env` en cas d'échec. Pour `APP_CONFIG_SECRET`, l'ancien secret passe dans `APP_CONFIG_SECRET_PRECEDENT` : `ConfigCipher` s'y replie au déchiffrement et `RechiffrementSecretConfig` (web, au démarrage) rechiffre `app_config` **et** `users.two_factor_secret` avec le nouveau, puis le script retire la variable. Procédure complète : [wiki Sécurité](https://codeberg.org/Kwaite/strapontin/wiki/S%C3%A9curit%C3%A9).

## Configuration applicative (`app_config`)

Chiffrement AES-256-GCM (`core.ConfigCipher`, clé dérivée de `APP_CONFIG_SECRET` via SCrypt, paramètres par défaut Node.js N=16384/r=8/p=1/dkLen=32 — différent de `BetterAuthPasswordEncoder` qui utilise r=16 — sel SCrypt volontairement fixe, non secret, la clé réelle dérivant du secret). Format de stockage `base64(IV(12) || TAG(16) || données_chiffrées)`, compatible avec les valeurs chiffrées par l'ancien module TypeScript. Extrait en 2.7.0 de deux implémentations dupliquées verbatim (`web.ConfigService`/`worker.ConfigServiceWorker`).
⚠️ Changer `APP_CONFIG_SECRET` sans `scripts/rotation-secrets.sh --app-config` → toutes les valeurs `app_config` et secrets TOTP illisibles.
⚠️ Changer les clés VAPID → supprimer `push_abonnements` d'abord.
Clés applicatives (`TICKETMASTER_API_KEY`, `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`, `CONTACT_EMAIL`, `PUBLIC_BASE_URL`, `VAPID_*`) configurables depuis `/admin`.
`RESEND_WEBHOOK_SECRET` : clé de signature Svix pour l'endpoint `POST /api/webhooks/resend` (bounces email). À copier depuis le dashboard Resend → Webhooks.
`ResendWebhooksAdminService` (v2.9.0, admin) : liste/rejoue un événement webhook Resend en échec (`Webhooks.listEvents`/`replayEvent`, resend-java 4.23.0) — le `webhookId` n'est jamais configuré explicitement, résolu à chaque appel en filtrant `webhooks().list()` sur l'endpoint `PUBLIC_BASE_URL + "/api/webhooks/resend"` déjà connu.

## Sources de données

- **Ticketmaster Discovery v2**, **Eventim France** : sources principales (Eventim par lieu/villes : [`sources…/sources/CLAUDE.md`](java/sources/src/main/java/fr/strapontin/sources/CLAUDE.md)).
- **Digitick** : retirée (v3.0.1, #1263), billetteries migrées vers Eventim ; valeur d'enum `digitick` conservée (non retirable en PostgreSQL), observations seules d'événements à venir conservées.
- **Deezer Search API** : autocomplete artiste (`/artistes`), sans clé ; remplace MusicBrainz.
- **Wikidata (SPARQL) + OSM (Nominatim)** : enrichissement nocturne (noms canoniques, alias, Deezer ID) ; identifiant Wikidata d'une salle lu sur l'objet OSM que Nominatim trouve autour de sa position (`NominatimSalleSource`, 1 req/s) ; Overpass retiré en 3.0.2 (instance publique saturée) ; rotation hebdomadaire `jour_batch`.
- **Wikidata Search (REST) + Nominatim** : recherche interactive à l'ajout (web, `SalleService`), sans clé ; Nominatim : `User-Agent` obligatoire, 1 req/s cadencé côté worker (`sources.ClientNominatim`, dernier recours de `ResolveurSalleParConcert`), pas encore côté web (appel non cadencé).
- **Bandsintown** : abandonné (HTTP 401 depuis 2026-04-10, #65). **MusicBrainz** : bloqué (IP SFR blacklistée par Cloudflare, #293).

## Jobs background (Quartz)

Paradigme : **pré-fetch en arrière-plan, jamais d'appel API depuis le browser**. Cron adaptatif (v2.4.0, #721) : `0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?` — 11 cycles/jour. Liste des jobs, quotas TM, cadencement des appels externes, watchdogs : détail complet dans [`java/worker/src/main/java/fr/strapontin/worker/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/CLAUDE.md) (chargé automatiquement en travaillant sur ce module).

## Authentification

Invite-only, TOTP 2FA optionnel, Spring Security session-based. Détail complet : [`java/web/src/main/java/fr/strapontin/web/securite/CLAUDE.md`](java/web/src/main/java/fr/strapontin/web/securite/CLAUDE.md) (chargé automatiquement en travaillant sur ce package).

## Dédoublonnage inter-sources

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, puis floue. Résolution UUID-first (`salle_sources`/`artiste_sources`) avant toute réconciliation floue ; un seul algorithme flou par entité (`core.CorrespondanceSalle`/`core.CorrespondanceArtiste`), partagé `sources`/`web`/`worker`. Point unique de résolution avant écriture : `PersisteurConcertsDb.resoudreOuCreerEvenement`. La résolution de salle filtre toujours par département ; tout rapprochement par position ne se fie qu'à une position fiable.

Ordre de traitement, garde-fous et incidents : [`worker…/persister/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/persister/CLAUDE.md). Seuils : [`core…/core/CLAUDE.md`](java/core/src/main/java/fr/strapontin/core/CLAUDE.md). Justification des seuils : [wiki Sources-de-données](https://codeberg.org/Kwaite/strapontin/wiki/Sources-de-donn%C3%A9es).

## Modèle événement canonique (v2.9.0)

`evenements`+`observations` remplace la table unique `concerts` (refonte complète, pas une simple consolidation) : `evenements` porte l'identité canonique (`artiste_id`/`salle_id`/date/heure/statut/absences), `observations` une ligne par `(evenement_id, source, url)` — une fiche package TM et sa fiche normale sont désormais deux observations distinctes du même événement plutôt qu'un doublon supprimé, gain net d'information. `concerts` redevient une **vue** (agrégation `evenements`+meilleure `observations` par priorité de source TM>Eventim>Digitick, `LATERAL JOIN`) pour tous les lecteurs existants (13 fichiers, aucune modification) — une vue multi-table n'étant pas modifiable, les 8 fichiers du chemin d'écriture (`PersisteurConcertsDb`, `FusionSallesClePorteeService`, `ReconciliateurArtistes`, `WorkerFetchService`, `WorkerPurgeService`, `ConcertsAdminService`, `ReferentielArtistesService`, `ReferentielSallesService`) écrivent directement sur `evenements`/`observations`. Le pattern de fusion (salle ou artiste) préserve désormais les observations plutôt que de les supprimer : supprime les observations du doublon partageant déjà `(source, url)` avec la cible, réassigne le reste, supprime l'évènement source devenu vide.

`signalements_matching` (point d'entrée unique des signalements de matching) et observabilité des jobs multi-lots : [`worker…/sante/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/sante/CLAUDE.md), [`worker…/worker/CLAUDE.md`](java/worker/src/main/java/fr/strapontin/worker/CLAUDE.md).

## Conventions détaillées (path-scoped rules)

UI/Vue, schéma SQL, style Java, tests E2E — scopées par type de fichier, chargées automatiquement en travaillant dessus plutôt que dupliquées ici : [`.claude/rules/frontend-vue.md`](.claude/rules/frontend-vue.md), [`.claude/rules/sql-migrations.md`](.claude/rules/sql-migrations.md), [`.claude/rules/backend-java.md`](.claude/rules/backend-java.md), [`.claude/rules/e2e-testing.md`](.claude/rules/e2e-testing.md).

Commandes de test (à connaître même hors édition d'un fichier) : `mvn -f java/pom.xml clean test -pl core,sources,web,worker -am` (Docker requis, Testcontainers) ; `cd front && npm run test:unit` (Vitest, fonctions pures) ; `cd e2e && npx playwright test` (jamais `--project`, 5 navigateurs). Analyse statique (bloquante en CI depuis v2.9.1) : `PMD_JAVA_DISABLE_AUX_CLASSPATH_WARNINGS=true mvn -f java/pom.xml -pl core,sources,web,worker -am compile checkstyle:check pmd:check spotless:check -DskipTests` ; `cd front && npm run lint && npm run lint:css`.

## Skills projet (`.claude/commands/`, `.claude/skills/`)

Seul l'outillage partagé de `.claude/` est versionné (liste blanche dans `.gitignore`, contrôlée en CI par `scripts/verifier-outillage.sh`) : jamais de données de prod, de configuration ou mémoire personnelle, ni d'accès à l'infrastructure. Ce dernier passe par `.claude/scripts/acces.local.sh`, fichier local créé depuis `acces.exemple.sh`.

- `/deploy-dev` : Build Maven (avec tests) + scp JARs + restart LXC dev + tail logs
- `/test` : Tests Java (4 modules) + Vitest + analyse statique + E2E Playwright, résumé chiffré par suite
- `/pre-pr` : Wiki + CLAUDE.md + changelog + README + commit docs + `tea pr create`
- `/suivi-fiabilite` : Check delta 360° du suivi de fiabilité prod — santé/jobs/données/tickets, état dans la mémoire personnelle (prérequis locaux listés dans le skill)
- `check-deps` : Mises à jour Maven/npm/stack infra + changelogs — auto en début de jalon (`"Tickets du jalon X.Y.Z à implémenter :"`, mises à jour sûres appliquées au jalon) ; résumé + proposition de ticket en invocation ad-hoc
- `/check-pipeline` : Statut et journaux de la dernière pipeline Woodpecker sur `main`
- `/ticket` : Création de ticket : doublon recherché, constat/analyse/attendu sans solution, étiquette et jalon vérifiés côté serveur
- `/cloture-jalon` : Lancé par l'utilisateur uniquement : merge de la PR, pipeline, vérification prod, tag et release lisible, fermeture du jalon, nettoyage, wiki et mémoire
- `/restauration-dev` : Base du dev remplacée par une copie de la prod, configuration du dev conservée (jamais `restore.sh`)

## Outils Claude Code

- **code-review-graph** (MCP) — **IMPORTANT : toujours l'utiliser AVANT Grep/Glob/Read** pour explorer le code (plus rapide, moins de tokens, contexte structurel — appelants, dépendants, couverture de tests). `semantic_search_nodes`/`query_graph` au lieu de Grep, `get_impact_radius` au lieu de tracer les imports à la main, `detect_changes`+`get_review_context` pour une revue de code, `get_architecture_overview` pour une vue d'ensemble. Ne se rabattre sur Grep/Glob/Read que si le graphe ne couvre pas le besoin — le graphe se met à jour automatiquement (hooks).
- **everything-search** (MCP) — recherche fichiers via Spotlight, évite le bruit `node_modules/`.
