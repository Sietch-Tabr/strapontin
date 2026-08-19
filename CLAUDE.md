# CLAUDE.md — strapontin (strapontin.kwaite.fr)

## Architecture — monorepo Maven

```
strapontin/
├── java/
│   ├── pom.xml       ← parent BOM (Java 25, Spring Boot 4.1.0)
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
    ├── restore.sh            ← restauration depuis archive tar.gz (NAS ou local)
    └── restore-test.sh       ← test de restauration à la demande (#748), jamais en CI
```

## Architecture module web — couche service/repository

Pattern 3-couches (depuis v2.1.0) : `XxxController` (HTTP pur) → `XxxService` (`@Transactional`, logique métier) → `XxxRepository` (SQL pur, `NamedParameterJdbcTemplate`, package-private). Non-universel : une vingtaine de services plus anciens/simples n'ont pas de repository dédié (SQL direct dans le service) — le pattern reste la référence pour tout nouveau service non trivial. Records top-level dans le package domaine (pas d'inner classes dans les contrôleurs).

`GlobalExceptionHandler` (`@RestControllerAdvice`) : `NoResourceFoundException`→404, `HttpRequestMethodNotSupportedException`→405, `AuthenticationException`→401, `DuplicateKeyException`→409, `DataAccessException`→500.

### Services extraits notables

- `ClientController` (`/api/client/erreur`) : erreurs JS navigateur → `logs_admin` (source `client-js`), câblé côté front (`window.onerror`/`unhandledrejection` dans `main.js`).
- `SourcesController`/`SourcesService` : onglet admin Sources — statuts par source (TM/Eventim/Deezer/Digitick) + CRUD salles Digitick. Le « dernier fetch » reste global (non filtré par source, limitation connue).
- `VapidService`, `BanniereService`/`WorkerProxyService`, `GenerateurSecrets` (package `securite`) : extraits d'`AdminService`.
- `FiltreConcertsBuilder`/`FiltreConcerts` (package `concert`) : fragments WHERE/ORDER BY/pagination et critères de filtrage partagés entre `SpectacleService`/`DecouverteService`.
- `ParametresService` (package `utilisateur`) : stats compteurs + flux changement email (`@Transactional`).
- `SourceConcerts`/`SourceAvecFetchLot` (module `sources`) : `WorkerService` source-agnostique — ajouter une source = créer une classe + bean Spring. Identifiants salle préfixés par source (`"tm:venueId"`).
- `WorkerService` scindé en `WorkerStatsService`/`WorkerFetchService`/`WorkerPurgeService`/`WorkerRevocationService`. `AdminService` scindé en `ConfigAdminService`/`JobsAdminService`. `EmailService` scindé en `EmailService` (transactionnel)/`EmailAlerteService` (bounce/spam/backup/watchdog/logs-admin)/`EmailSender` (plomberie Resend partagée).
- `core.BilanFetch`/`core.CompteurBilanFetch` : bilan de panne agrégé (appels/erreurs par cycle, escalade `logs_admin` niveau `error` sur panne totale) — partagé par toutes les sources via l'interface `sources.SourceAvecBilanFetch`, plutôt qu'un mécanisme dupliqué par source.

### Enrichissement Wikidata/OSM/Deezer (worker, package `enrichissement`)

`SourceEnrichissementSalle`/`SourceEnrichissementArtiste` : pattern extensible (`OverpassSalleSource`, `WikidataSalleSource`, `WikidataArtisteSource`, `DeezerArtisteSource`), orchestré par `EnrichissementSallesService`/`EnrichissementArtistesService`, persistance par lot de 25 (délai 2s entre lots — une interruption ne perd que le lot en cours). Sélection nocturne par rotation hebdomadaire `jour_batch` (colonne `smallint` 1-7 = `EXTRACT(ISODOW FROM date)`, séquence Postgres partagée) plutôt qu'un plafond fixe — garantit un passage toutes les 7 nuits même pour une salle en échec systématique.

- `WikidataSalleSource.decouvrir` : 3 replis successifs sur le nom si la recherche `mwapi:search` complète échoue — nom tronqué au premier séparateur (`-`/`/`/`(`), puis nom sans son dernier mot (couvre le pattern « Nom Ville » sans séparateur, ex. « La Belle Electrique Grenoble » → « La Belle Electrique »). La validation finale reste toujours faite contre le nom complet (`estDoublonSalle`) — ces replis n'élargissent que l'ensemble de candidats interrogés, jamais le critère d'acceptation. Filtre les entités administratives (région/département/commune) et rejette un candidat quand `salle.ville()` et la commune Wikidata (P131) sont tous deux connus et incohérents (`Normalisation.normaliserVille`).
- `OverpassSalleSource` : requête par département (`around:`, plus léger que la France entière), `echapperRegex` échappe métacaractères puis guillemet puis normalise l'apostrophe droite en `['’]`. Backoff différencié 429 (rate-limit, 20s) vs autre erreur réseau/504 (5s) ; délai minimal entre lots 3000ms.
- `WikidataArtisteSource.construireRequeteDecouverte` : filtre humain+musicien/humoriste (P106 Q639669/Q245068/Q12406482) ou groupe — une ambiguïté de type parmi les meilleurs candidats bloque tout rattachement plutôt que de deviner.
- Conflit sur l'index unique `idx_salles_wikidata_id`/`idx_artistes_wikidata_id` (même Q-ID matché sur deux entités) : rattrapé par entité (`DuplicateKeyException`) et loggé, sans faire échouer tout le cycle nocturne.
- `CoherenceWikidataSalleService` (package `worker.sante`) : contrôle a posteriori comparant `salles.wikidata_id`/`concerts.ville` (priorité, jamais touchée par l'enrichissement) à `salles.ville` — journalise les incohérences déjà en base (`logs_admin`, source `coherence-wikidata`) pour examen manuel, sans changer le matching lui-même.
- `ArtisteService.rechercherWikidata`/`SalleService.rechercherNominatim` (web) : recherche interactive à l'ajout, mécanisme **distinct** du SPARQL mwapi ci-dessus (REST `wbsearchentities`/Nominatim `/search`, résolution différée du P2722 uniquement à la sélection). `core.FiltreArtiste.estTribute` rejette les attractions tribute/hommage à la création. `SalleService.contientCorrespondancePartielle`/`ArtisteService` : `dejaLeSuivi`/`dejaLaSuivie` testent une correspondance partielle pour éviter un faux « Aucun résultat ».
- `core.CorrespondanceSalle`/`core.CorrespondanceArtiste` : algorithmes de dédoublonnage flou partagés par `sources`/`web`/`worker` — un seul algorithme par entité dans tout le projet (détail des seuils : [wiki Architecture](https://codeberg.org/Kwaite/strapontin/wiki/Architecture)).

### Santé des sources / référentiel admin

`ReconciliationNocturneService`+`SuiviSanteSourcesService` (package `sante`, worker) : health-check/redécouverte des IDs TM suspects (3h) + compteur d'échecs consécutifs. La vérification active (`TicketmasterSource.verifierVenue`/`verifierAttraction`) réinitialise le compteur dès qu'elle confirme un ID vivant — un compteur passif seul ne suffit pas (une salle/artiste sans concert à venir n'est pas un ID mort). `retenterSallesOrphelines()` retente chaque nuit la résolution par tokens des concerts sans `salle_id`.

`ReferentielSallesService`/`ReferentielArtistesService` (web, package `admin`) : onglets admin Salles/Artistes — liste paginée, édition `nom_affiche`, fusion de doublons (dédoublonne les concerts en conflit avant remapping, et les `salle_sources`/`artiste_sources` en conflit par colonne `source` avant réassignation). Statut salle `a_jour`/`a_verifier`/`invalide` (compteur passif). Statut artiste `a_jour`/`invalide` (binaire, `artiste_sources.necessite_intervention_le` — le compteur passif seul générait 65% de faux positifs sur des artistes sans date à venir ; seule `ReconciliationNocturneService` pose la colonne, `SuiviSanteSourcesService` l'efface au premier fetch positif confirmé cohérent avec l'artiste suivi, `fusionner`/`ignorerIntervention`/`corrigerIdentifiantTm` l'effacent à la résolution manuelle).

### Sécurité admin/sessions

`AdminService`/`SetupService`/`PushService`/`SalleService` isolent leurs opérations sensibles (`initierRevocationAdmin`, `initialiser`, cap abonnements push, résolution Wikidata salle) en transaction `SERIALIZABLE` (`REQUIRES_NEW` ou `NESTED` selon le contexte) + retry sur `PessimisticLockingFailureException`, via un pattern d'auto-injection par setter (`@Autowired setSelf(@Lazy X self)`, pas par constructeur — évite l'auto-référence en test unitaire).

⚠️ **Ne jamais appeler une méthode `REQUIRES_NEW`+`SERIALIZABLE` depuis une transaction déjà ouverte au niveau appelant** — épuisement du pool HikariCP garanti (incident réel v2.6.4, `AdminController.modifierUtilisateur`). `AdminService.modifierUtilisateur` rejette donc explicitement la combinaison nom/email + rétrogradation admin en un seul `PATCH` (400) plutôt que d'imbriquer les deux transactions.

Sessions révoquées au changement de mot de passe (toutes, pas seulement la courante) et à la rétrogradation admin effective (`RevoquerAdminsJob`, raw SQL côté worker). `session_appareils` associe un `appareil_id` opaque à chaque session (jamais le `session_id` réel côté client) ; `AppareilEnregistrementInterceptor` (pas le contrôleur de login) enregistre l'appareil au premier appel API authentifié suivant — Spring Session (JDBC) ne persiste la session qu'après la réponse du login. `/parametres` (Compte) et `/admin` exposent la gestion des appareils/sessions.

### Dédoublonnage/corruption artiste — `resoudreParUrl` et `EligibiliteResolutionDeezer`

`PersisteurConcertsDb.resoudreParUrl` (résolution par `(source, url)`) est scopé à Eventim uniquement (son rôle documenté : pages festival, une même URL exposant des artistes différents selon le fetch) — appliqué à tort à toutes les sources, il empêchait `supprimerDoublonsParUrl` (auto-guérison par URL, garde la ligne la plus récente) de jamais s'engager.

`ReconciliateurArtistes.reconcilierUnArtiste` fait une réconciliation floue (Jaro/containment) contre les concerts déjà en base au même (date, salle). `EligibiliteResolutionDeezer` (`worker.persister`, partagée avec `WorkerFetchService.estEligibleResolutionDeezer`) distingue : un concert **éligible** à la résolution Deezer (nom potentiellement fiable-mais-corruptible, ex. classification musicale — comportement protecteur historique conservé, l'entrant converge vers l'existant, nécessaire contre un mauvais rapprochement Deezer futur) d'un concert **inéligible** (nom brut fiable par construction, ex. classification `theatre`/`divers`/`sports`/Eventim sans classification — corrige alors rétroactivement en base la ligne déjà persistée via `UPDATE` SQL directe, au lieu de régresser l'entrant vers un nom potentiellement corrompu).

### Bean Validation

Tout endpoint `@RequestBody` prend un DTO record annoté (`@NotBlank`/`@NotNull`/`@Size`/`@Email`/`@Pattern`) + `@Valid` sur le paramètre du contrôleur — jamais de `Map<String,String>` brut. `@Valid` ne s'exerce que via le pipeline Spring MVC réel, jamais sur un appel Java direct au contrôleur en test : la validation "champ manquant → 400" se teste via `Validator.validate(new Record(...))` sur le DTO (pattern `RequeteXxxValidationTest`), pas sur le contrôleur. Exceptions documentées : `ForgotPasswordRequest` (anti-énumération de comptes, réponse HTTP identique quel que soit le contenu), `ClientController` (bag de métadonnées ouvert, déjà borné autrement), `ResendWebhookController` (payload brut signé Svix), et les champs dont les valeurs autorisées sont définies côté service (`THEMES_VALIDES`/`FREQUENCES_VALIDES`) plutôt que dupliquées en `@Pattern`.

### Autres

- `core` : `QuotaTM`/`UtilitairesChaine.echapperJson`/`extraireDeptFrancais`/`normaliserCasse`/`ConfigCipher` (dérivation SCrypt+AES-256-GCM, partagée `ConfigService`/`ConfigServiceWorker`) mutualisés entre modules.
- `SpectacleService`/`DecouverteService` : COUNT et filtres disponibles calculés en parallèle via `CompletableFuture` (threads virtuels).
- `RecalculerMatchesService` : recalcul parallèle par utilisateur via `Executors.newVirtualThreadPerTaskExecutor()`, 1 transaction par user.
- `WebConfiguration` : beans `ObjectMapper`+`RestClient` (timeout 5s/10s).
- `PersisterConcerts` (worker) : orchestrateur injectant `ReconciliateurSalles`/`ReconciliateurArtistes`/`PersisteurConcertsDb`. `resoudreArtisteParNomNorm` couvre toutes les sources sans `artisteId`, y compris Digitick.
- **okhttp/Resend (Kotlin Multiplatform)** : l'artefact Maven générique `com.squareup.okhttp3:okhttp` est vide côté JVM depuis okhttp 5.0.0 — exclusion + dépendance explicite `okhttp-jvm` requise dans **les deux** `java/web/pom.xml` **et** `java/worker/pom.xml` (l'oubli côté worker a déjà provoqué un `NoClassDefFoundError` silencieux sur tout envoi d'email worker).

Historique détaillé de chaque jalon (bugs trouvés, tickets, rationale complète) : [wiki Roadmap](https://codeberg.org/Kwaite/strapontin/wiki/Roadmap). Détails architecturaux par domaine : [wiki Architecture](https://codeberg.org/Kwaite/strapontin/wiki/Architecture), [wiki Sources-de-données](https://codeberg.org/Kwaite/strapontin/wiki/Sources-de-données), [wiki Sécurité](https://codeberg.org/Kwaite/strapontin/wiki/Sécurité).

## Stack technique (v2.2.0)

- **Spring Boot 4.1.0** + **Vue 3 + Vite 8.1** (`front/` → `static/`) — port 8080. Thymeleaf et HTMX supprimés.
- **Vitest 4** (`front/vitest.config.js`, v2.6.2, #781) : tests unitaires des fonctions pures partagées entre composants (`front/src/utils/`), sans montage de composant ni jsdom — complémentaire à Playwright (flux utilisateur, rendu réel), pas redondant. Config dédiée séparée de `vite.config.js` pour ne pas embarquer les plugins Vue/PWA dans l'exécution des tests.
- **Spring Security 7** — auth session, TOTP 2FA optionnel (bucket4j rate-limiting) ; `anyRequest().authenticated()` — non-API sans session → redirect `/login`, API → 401 JSON
- **Spring Data JDBC** + PostgreSQL 18 — données persistantes. `statement_timeout=30s` / `idle_in_transaction_session_timeout=30s` (protection contre une transaction bloquante qui épuiserait le pool HikariCP, cf. incident v2.6.3). Tuning mémoire (`shared_buffers`/`work_mem`/`effective_cache_size`) laissé aux valeurs par défaut PostgreSQL — base ~16 MB (juillet 2026), aucun gain mesurable à un tuning mémoire à ce volume.
- **Quartz** — jobs background (fetch concerts) ; `threadPool.threadCount=10` (virtual threads)
- **Flyway 12** — migrations DB (`java/web/src/main/resources/db/migration/`)
- **Resend** — notifications email (tier gratuit 3 000/mois) ; gabarits HTML via `EmailTemplates.java` (text blocks Java)
- **web-push (VAPID)** — notifications push PWA
- **Maven** — build (`mvn -f java/pom.xml -pl web,worker -am package`). Version CI-friendly (v2.6.1, #764) : `<revision>` dans `java/pom.xml` (parent), `${revision}` dans les 4 pom.xml enfants — un seul bump met à jour tout le monorepo.

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

# Health check Actuator (depuis le LXC)
curl -sf http://localhost:9090/actuator/health   # web
curl -sf http://localhost:9091/actuator/health   # worker
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

### Pipeline Woodpecker (`.woodpecker/ci.yaml`)

Pipeline active : **test (Maven) + test-front (Vitest) → build-front → build (Maven) → backup → deploy**, tous restreints à `branch: main`. `build-front` dépend de `test-front` (v2.6.2, #781), comme `build` dépend de `test`. Secrets requis : `ssh_key`, `lxc_host`. Le step `backup` exécute `woodpecker-backup.sh` (backup local sur le LXC prod avant déploiement).

> **Woodpecker — repo trusted** : le repo doit être marqué "trusted" (Settings → repo → cocher "Trusted") pour autoriser les volumes Maven dans le pipeline.

## Sauvegarde et restauration

`backup.sh`/`backup-local.sh` archivent `dump.sql`/`dump_local.sql` + `.env` + logs. `restore.sh` restaure l'archive complète : recrée la base avec le **bon propriétaire** (`createdb -O <utilisateur>`, extrait du `.env` de l'archive) — depuis PostgreSQL 15, les droits `CREATE` sur le schéma `public` sont révoqués par défaut pour tout rôle autre que le propriétaire, un `createdb` sans `-O` (rôle `postgres` par défaut) fait donc échouer toute migration Flyway ultérieure avec `droit refusé pour le schéma public`. Restaure aussi le `.env` de l'archive et réaligne le mot de passe PostgreSQL du rôle applicatif sur `SPRING_DATASOURCE_PASSWORD` qu'il contient — sans ce réalignement, l'application ne peut plus se connecter à sa propre base après une restauration sur une instance dont le mot de passe diffère (ex. instance neuve).

`scripts/restore-test.sh` (#748) : test de restauration à la demande, jamais automatisé en cron/CI. Séquence : `vzdump` snapshot de la prod (non disruptif) → `pct restore` en LXC temporaire (199) → réattribution réseau (MAC neuve + `ip=dhcp`, sinon le LXC restauré hérite de l'IP/MAC statique de la prod et provoque un conflit ARP pendant tout le test) → `restore.sh` → health checks (`:9090`/`:9091`, **pas** `:8080` — c'est le port applicatif/SPA, pas l'actuator) → destruction. `pct clone` est délibérément évité : il refuse de cloner un conteneur ayant un point de montage bind (`mp0`, le partage NAS de la prod, monté uniquement en profil `prod` de `provision.sh`) — `vzdump` l'exclut nativement (« not a volume »). Le LXC restauré hérite aussi du flag `protection` de la prod, à désactiver avant tout `pct destroy`.

**Jamais automatisé via Woodpecker** : le LXC Woodpecker (VLAN 20, `192.168.20.0/24`) n'a volontairement pas de route active vers le réseau de management Proxmox (`192.168.1.0/24`, gateway distincte `192.168.1.1`) — confirmé (route présente mais tout le trafic, y compris ICMP, y est filtré), cohérent avec le modèle default-deny inter-VLAN déjà en place ailleurs sur cette infra (ex. NFS VLAN20→VLAN10 nécessite une règle explicite). Percer cette frontière donnerait à un pipeline CI (code de dépendances tierces, images Docker) un accès root à l'hyperviseur entier — disproportionné face au risque de compromission d'un pipeline. `scripts/restore-test.sh` s'exécute donc uniquement à la demande, copié puis lancé directement sur Proxmox.

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

| Source                                                | Statut                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| ----------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Ticketmaster Discovery v2                             | Core                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| Eventim France                                        | Core                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| Digitick (`web.digitick.com`)                         | Core (v2.4.0, #610) — scraping HTML par salle, slug dans `salle_sources`                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| Deezer Search API                                     | Autocomplete artiste (`/artistes`) — sans clé. Remplace MusicBrainz (bloqué).                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Wikidata (SPARQL) + OSM (Overpass, `overpass-api.de`) | Enrichissement nocturne — noms canoniques, Deezer ID, P6262 (cross-validation TM). Visible dans `/admin` > Sources. Requête par département (`around:`, plus léger que la France entière, restaure la fiabilité d'`overpass-api.de` après un aller-retour par `overpass.osm.ch` limité de facto au territoire suisse), backoff différencié 429 (20s) vs autre erreur (5s), délai minimal entre lots 3000ms. Sélection nocturne par rotation hebdomadaire `jour_batch` plutôt qu'un plafond fixe — voir `OverpassSalleSource` |
| Wikidata Search (REST) + Nominatim (OSM)              | Recherche interactive à l'ajout (v2.6.1, #717/#718) — sans clé API. Nominatim : `User-Agent` obligatoire, 1 req/s (respecté par la cadence manuelle de recherche, pas de debounce nécessaire — recherche soumise par bouton, jamais en direct)                                                                                                                                                                                                                                                                                                 |
| Bandsintown                                           | Abandonné — HTTP 401 depuis 2026-04-10 (#65)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| MusicBrainz                                           | Bloqué — IP SFR blacklistée par Cloudflare (#293)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |

## Jobs background (Quartz)

Paradigme : **pré-fetch en arrière-plan, jamais d'appel API depuis le browser**.

Cron adaptatif (v2.4.0, #721) : `0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?` — 11 cycles/jour.

| Job                         | Déclenchement                   | Description                                                                                                                                        |
| --------------------------- | ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FetchArtisteJob`           | Ajout artiste + cron adaptatif  | Fetch TM + Eventim par nom artiste                                                                                                                 |
| `FetchArtistesBatchJob`     | Cron adaptatif (H:00)           | Fetch TM en lots de 20 attractionIds                                                                                                               |
| `FetchRegionJob`            | 1 batch nocturne (02h) + manuel | Fetch TM + Eventim par département — réduit du cron adaptatif à 1x/jour en v2.7.3 (#996, seul `DecouverteService` en dépend, browse-only)          |
| `FetchSallesAllJob`         | Cron adaptatif (H:30)           | Fetch des salles **suivies** (`user_salles`) avec sources configurées (v2.4.0, #720 ; restreint des 255 salles TM aux ~19 suivies en v2.7.3, #996) |
| `FetchSalleJob`             | Ajout salle + déclenchement API | Fetch par identifiants préfixés (`CLE_IDENTIFIANTS`, ex. `"tm:venueId"`)                                                                           |
| `RecalculerMatchesJob`      | Cron adaptatif (H:00)           | Recalcule `user_matches` pour tous les users                                                                                                       |
| `PurgerConcertsJob`         | Cron quotidien (minuit)         | Supprime concerts passés depuis > 7 jours                                                                                                          |
| `PurgerLogsAdminJob`        | Cron quotidien (0h15)           | Supprime les `logs_admin` > 30 jours et `fetch_jobs_historique` > 7 jours                                                                          |
| `RevoquerAdminsJob`         | Cron horaire (H:00)             | Applique les révocations admin dont la date est dépassée                                                                                           |
| `ReconciliationNocturneJob` | Cron quotidien (3h)             | Health-check/redécouverte IDs TM suspects + enrichissement Wikidata/OSM/Deezer (v2.6.0, #706)                                                      |

Rate limiting TM : max 2 jobs simultanés (3 → HTTP 429). Quota TM persisté dans `app_config` (`TM_QUOTA_JSON`).
`FetchArtistesBatchJob`, `FetchRegionJob`, `FetchSallesAllJob`, `RecalculerMatchesJob`, `ReconciliationNocturneJob` annotés `@DisallowConcurrentExecution`. `WatchdogWorkerService` (`@Scheduled`, côté web) : alerte email si worker inactif > 3 h. `AlerteLogsAdminService` (`@Scheduled` 15 min, côté web, v2.6.1 #765) : digest email sur nouvelles entrées `logs_admin` niveau `error`, curseur persisté dans `app_config` (`ALERTE_LOGS_ADMIN_CURSEUR`, pas en mémoire — survit aux redéploiements), n'avance qu'après envoi réussi.
`spring.quartz.wait-for-jobs-to-complete-on-shutdown=true` (v2.7.0, #886) : un déploiement pendant un cycle en cours attend sa fin plutôt que de le tuer silencieusement (`RAMJobStore` ne persiste aucun trigger). Borné par `TimeoutStopSec=90` (`strapontin-worker.service`) et `spring.lifecycle.timeout-per-shutdown-phase=80s` — nécessite que `TicketmasterSource`/`EventimSource` aient un timeout HTTP borné (#887, `sources.ClientHttpFactory`), sinon un hoquet réseau ferait pendre le déploiement.

## Authentification

Invite-only. Login par identifiant (insensible à la casse). Rôles : `user` | `admin`.
2FA TOTP optionnel pour tous les utilisateurs — enrollment via `/parametres/totp`, challenge automatique à la connexion si activé. Cap : 500 artistes / 200 salles par user.
Algorithme TOTP conforme RFC 6238 (HMAC-SHA1, 30 s, ±1 fenêtre, saisie tolérante aux espaces). Réinitialisation 2FA par un admin depuis `/admin` (bouton « Réinit. 2FA », endpoint `POST /api/admin/utilisateurs/{id}/reset-totp`) pour un compte bloqué. Succès et échecs d'auth (login, rate-limit, TOTP) consignés dans `logs_admin` (source `auth`). Rate-limit login : 15 tentatives / 15 min par IP (bucket4j → 429).
Spring Security form login → cookie `SESSION` (JDBC-backed, survit aux restarts, `Max-Age=2592000`, `HttpOnly`, `SameSite=Lax` — pas `Strict`, compatibilité Safari sur navigation entrante, #926 — configuré via `DefaultCookieSerializer`, pas via `application.properties`). Mots de passe hashés en bcrypt.
**Appareils/sessions actives (v2.7.8, #1029)** : `session_appareils` (V48 web) associe un `appareil_id` opaque à chaque `SPRING_SESSION.SESSION_ID`, sans modifier le schéma tiers de Spring Session JDBC. Le `session_id` réel n'est jamais exposé côté client — un XSS qui lirait la liste ne peut pas usurper une session par son identifiant. `AppareilEnregistrementInterceptor` (Spring MVC, pas le contrôleur de login) enregistre l'appareil au premier appel API qui suit la connexion : Spring Session (JDBC) ne persiste la session en base qu'à la toute fin de son propre filtre, après que le contrôleur de login a déjà rendu sa réponse — un enregistrement synchrone pendant `AuthController.login()` viole systématiquement la clé étrangère vers `SPRING_SESSION` (bug réel trouvé en E2E cette session, corrigé avant livraison). `/parametres` (onglet Compte) et `/admin` (révocation de toutes les sessions d'un utilisateur) exposent la gestion.
Détails : [wiki Authentification](https://codeberg.org/Kwaite/strapontin/wiki/Authentification)

## Dédoublonnage inter-sources

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, fuzzy Levenshtein normalisé ≥ 0.85.
Résolution UUID-first (v2.3.0, ticket #621) : `PersisterConcerts.resoudreIdentitesSources` consulte `salle_sources`/`artiste_sources` avant la réconciliation floue. Absorbe les renommages de salle. Pour les salles TM absentes de `salle_sources`, upsert automatique dans `salles` + `salle_sources` (ticket #633). Les passes de réconciliation propagent `salleUuid`/`artisteId` (ticket #632).
`estDoublonSalle` : Levenshtein ≥ 0.75 | Jaccard tokens spécifiques > 0.5 | token pivot spécifique (≥ 7 chars, Jaccard > 0.3, ≥ 2 tokens chacun — #702) | égalité sans espaces (#902, couvre une source qui omet l'apostrophe plutôt que de la remplacer par un séparateur — ex. "LOlympia" vs "L'Olympia") | containment de tous les tokens spécifiques du nom le plus court dans le plus long, ≥ 4 caractères par token et ≥ 2 tokens spécifiques de chaque côté (introduit en v2.7.8, #1045 — le garde-fou de comptage était documenté mais pas réellement implémenté avant #1054/v2.7.9, qui compare désormais bien les tokens **spécifiques**, pas bruts — sans ce correctif, un nom réduit à un seul token spécifique après retrait des mots génériques, ex. "Casino de Paris" → `{paris}`, matchait trivialement n'importe quel lieu partageant ce simple nom de ville). "Tokens spécifiques" = tokens significatifs moins `MOTS_GENERIQUES_SALLE` (theatre, chateau, casino, colisee, expositions, culturel, metropole, municipal, antique, parc, barriere… — exclusion étendue en v2.7.8, #1032/#1046 ; "arena" volontairement absent de cette liste, contributeur légitime et non générique). Limites connues non couvertes par ces critères : pivot nom de ville, Jaccard gonflé par un token court comme "des".
`estDoublonArtiste` : Jaro ≥ 0.95 | containment ratio ≥ 0.65. `normaliserTexte` : `$` supprimé sans espace, `&` → `and` (#703).
`chercherSalleParTokens` : tous les tokens testés (pas seulement le premier), filtre dept avec repli sur le département réel des concerts déjà rattachés quand `salles.dept` est vide (#973) pour éviter les homonymes inter-villes (#702), préfiltre SQL élargi à l'égalité `nom_norm` sans espaces (#902). `raccrocherSallesParNomNorm` (repli par égalité exacte de `nom_norm`, appelé après `chercherSalleParTokens` dans le pipeline) applique le même filtre dept depuis #973 — sans lui, il défaisait silencieusement la protection du premier pour deux salles homonymes au texte strictement identique. `ReconciliateurSalles.upsertSalleSource` propage désormais `dept` par `COALESCE` à chaque résolution de salle existante (v2.7.3, #992) — cette colonne n'était écrite qu'à la création jusque-là.
`ReconciliateurSalles` (worker) inclut désormais `dept` et l'heure (minute-précision, `to_char(heure, 'HH24:MI')`) dans la clé de réconciliation intra-lot/inter-jobs, en plus de `artiste_norm`/`date` (v2.7.8, #1033) — sans eux, deux concerts du même artiste le même jour dans des départements ou créneaux différents pouvaient se voir attribuer la même salle par erreur. `retenterSallesOrphelines()` (nouveau, #1040) retente chaque nuit (`ReconciliationNocturneService`) la résolution par tokens des concerts dont `salle_id` est resté `NULL` à la persistance initiale (TM omet parfois `venue.id`) — la salle correspondante peut apparaître plus tard via une autre source.
→ Détails : [wiki Sources-de-données](https://codeberg.org/Kwaite/strapontin/wiki/Sources-de-donn%C3%A9es)

## UI / Design system (v2.2.0 — Vue SPA)

- **Vue 3 + Vite** — SPA intégralement côté client. `front/src/pages/` (pages), `front/src/components/` (composants), `front/src/composables/` (logique réutilisable avec état/cycle de vie Vue), `front/src/utils/` (fonctions pures sans dépendance Vue, testables par Vitest — v2.6.2, #781, ex. `statutReferentiel.js` partagé entre `AdminSalles.vue`/`AdminArtistes.vue`)
- **CSS vanilla** — variables CSS pour thème clair/sombre, dans `front/src/styles/` (importés dans `main.js` → fingerprinting Vite vers `/assets/index-*.css`)
- **PWA** : `vite-plugin-pwa` (mode `injectManifest`) compile `front/src/sw.js` → `static/sw.js` (artifact gitignored). Workbox précache 24 assets (bundles JS/CSS, images, icônes, `index.html`, `offline-fallback.js` depuis v2.6.1 #766). Mise à jour automatique : `controllerchange` → `window.location.reload()` dans `App.vue`. `front/public/offline-fallback.js` : filet de secours hors bundle (script externe, jamais inline — bloqué par la CSP `script-src 'self'`) affichant un message si `#app` reste vide 4s après chargement (premier lancement hors-ligne avant toute synchronisation du SW).
- **Badge "Nouveau"** : basé sur `user_matches.vu_le IS NULL` — remis à `NULL` à la visite de `/`. Affiché sur la sidebar desktop et la bottom nav mobile. `notification_envoyee_le` est une colonne distincte pour le suivi des envois email/push.
- **Thème** : cookie `theme` (1 an) + `user_config.theme` → pas de FOUC
- **Navigation** : Vue Router (`front/src/router/`) — sidebar desktop, bottom nav + drawer mobile. `useEstMobile()` (`front/src/composables/useBreakpoint.js`, seuil 1024 px) contrôle le rendu conditionnel via `v-if` — pas de duplication DOM ni de `display: none`.
- **Pages à onglets** (`admin`, `parametres`) : orchestrateur léger + sous-composants autonomes (`KeepAlive` + `v-if`). Chaque sous-composant fetch ses données dans `onMounted`. `<select v-model>` sur mobile (`v-if="estMobile"`), `<nav v-else>` sur desktop — rendu conditionnel via `useEstMobile()`. Onglet actif géré par Vue (`:class="{ actif }"`) — pas de sélecteurs CSS `:has()`.
  - Admin : `front/src/components/admin/` (AdminJobs, AdminSources, AdminSalles, AdminArtistes, AdminLogs, AdminConfig, AdminBanniere, AdminUtilisateurs)
  - Paramètres : `front/src/components/parametres/` (ParametresPreferences, ParametresNotifications, ParametresCompte, ParametresDonnees)
- **Toasts** : Popover API, `afficherToast(message, type)` retourne un `id` pour fermeture ciblée. `App.vue` gère offline/online : toast persistant à la perte de connexion, fermé à la reconnexion. Position mobile (`components.css`, media `max-width: 1024px`) inclut `env(safe-area-inset-bottom)` (v2.6.2, #774) — sinon masqué par la bottom nav sur iPhone avec home indicator, même piège que `.bottom-nav`/`.main-content` (section PWA ci-dessus).

## Schéma PostgreSQL

Migrations Flyway dans `java/web/src/main/resources/db/migration/` — ne jamais modifier à la main.
`users.id` : type `text` (UUID généré par Java).
**v2.3.0 (V23)** : `salles.salle_uuid uuid`, tables `artistes`, `salle_sources`, `artiste_sources`, colonnes `salle_uuid`/`artiste_id` sur `concerts`, `user_salles`, `user_artistes`. Migration additive.
**v2.3.0 (V24)** : nettoyage post-backfill #628 — `salles.salle_uuid` → `salles.id` (PK uuid), ancienne PK text → `salles.salle_cle` (UNIQUE) ; PK `user_salles` = `(user_id, salle_uuid)` ; PK `user_artistes` = `(user_id, artiste_id)` ; suppression `salle_id`/`tm_venue_id`/`artiste_normalise`/`attraction_id`/`concerts.salle_cle`.
**v2.5.6 (V39)** : `users.totp_failure_count INT DEFAULT 0` — compteur d'échecs TOTP persisté en base (#732).
**v2.5.7 (V40 web / V15 worker)** : backfill `artiste_id` concerts futurs sans artiste ; V15 worker : `ALTER TYPE source_concert ADD VALUE 'digitick'`.
**v2.6.0 (V41-43 web / V16-18 worker)** : `nb_zero_consecutifs`/`suspect_depuis` sur `salle_sources`/`artiste_sources` ; `salles.wikidata_id`/`nom_affiche` ; `artistes.wikidata_id`/`deezer_id`/`nom_affiche` (#706/#711).
**v2.7.0 (V44 web / V19 worker)** : `artiste_sources.necessite_intervention_le TIMESTAMPTZ` (#888).
**v2.7.3 (V46 web / V25 worker)** : `salles.jour_batch`/`artistes.jour_batch smallint` (1-7, ISODOW) + séquence `enrichissement_jour_batch_seq` partagée, rotation hebdomadaire remplaçant `PLAFOND_NOCTURNE` (#994). Rattrapages worker sans équivalent web (drift du jeu de migrations, jamais d'impact prod) : V23 `user_config.notif_artistes_push`/`notif_salles_push` (web V15/V16), V24 tables `logs_admin`/`fetch_jobs` (web V4), V26 `salles.ville`/`cp` (web V34).
**v2.7.8 (V47 web / V27 worker)** : `concerts.absences_consecutives`/`absence_detectee_le` (#1042). **v2.7.8 (V48 web, sans équivalent worker)** : `session_appareils` (#1029) — table propre au module web, `spring.flyway.enabled=false` côté worker rend un équivalent worker inutile. Dernière version web : V48, worker : V27.
Détails : [wiki Schéma](https://codeberg.org/Kwaite/strapontin/wiki/Schéma-de-base-de-données)

## Conventions Java

- Identifiants en français (`nombreUtilisateurs`, `calculerTotal`)
- Logging : SLF4J, jamais `System.out`
- Commentaires : jamais de référence à un numéro de ticket Codeberg — le commentaire sert le code et le projet, pas l'historique de sa résolution ; les tickets sont référencés dans les messages de commit et les PR, jamais dans le code source (balayage complet du dépôt en v2.7.8)
- **Tests Java** : `mvn -pl core,sources,web test` (unitaires — nécessite Docker depuis #940, `AdminServiceIntegrationTest` monte un vrai PostgreSQL via Testcontainers). `mvn -pl worker test -am` requiert Docker (Testcontainers).
- **Tests front (Vitest)** : `cd front && npm run test:unit` (v2.6.2, #781) — fonctions pures uniquement (`front/src/utils/`), sans jsdom.
- **Tests E2E** : Playwright (`e2e/`), nécessite LXC dev actif (port 8080)
  - **Deux comptes dédiés** créés par `bash scripts/seed-e2e.sh root@192.168.20.78` : `e2eadmin` (admin) et `e2euser` (user). Idempotent. Le seed supprime et recrée les users (CASCADE DELETE) — après relance, supprimer `e2e/tests/e2e/.auth-*.json` pour forcer la reconnexion. `e2euser` suit un artiste choisi uniquement sur la présence d'un concert futur (sans notion de département) ; `seed-e2e.sh` réinitialise donc aussi `user_config.departements` à `{}` pour cet utilisateur — sans ça, un filtre département resté positionné par un test antérieur (ou une session manuelle) fait disparaître silencieusement tous les matches au cycle suivant de `RecalculerMatchesJob`, dès que la tournée réelle de l'artiste suivi s'éloigne des départements filtrés (bug réel constaté en session le 13 juillet 2026, jalon 2.7.0 lot 2 — 6 tests E2E en échec sur l'accueil).
  - global-setup ouvre 2 sessions (`.auth-admin.json`, `.auth-user.json`) via `POST /api/auth/login`, puis réinitialise les buckets rate-limit (`DELETE /api/salles/rate-limits/signalement`, profil `dev` uniquement). Échoue immédiatement (avant tout login) si un `playwright-mcp` externe persistant est détecté — une session de debug manuelle active en parallèle peut ralentir toute la suite d'un facteur ~4x sans jamais la faire échouer clairement.
  - Tests "Scénario: API auth" et test signalement valide restreints à chromium pour ne pas épuiser le rate limiter bucket4j
  - `invitation_tokenValide` ne tourne que sur chromium — le test crée sa propre invitation via l'API admin (indépendant du seed)
  - Lancer : `cd e2e && npx playwright test` (mdp surchargeable via `E2E_ADMIN_MOT_DE_PASSE` / `E2E_USER_MOT_DE_PASSE`)
- Nommage tests : `<methode>_<scenario>_<resultatAttendu>`

## Skills projet (`.claude/commands/`)

| Skill              | Usage                                                                                                                                                                                                               |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/deploy-dev`      | Build Maven (avec tests) + scp JARs + restart LXC dev + tail logs                                                                                                                                                   |
| `/test`            | Tests unitaires Maven + E2E Playwright + résumé des résultats                                                                                                                                                       |
| `/pre-pr`          | Wiki + CLAUDE.md + changelog + README + commit docs + `tea pr create`                                                                                                                                               |
| `/suivi-fiabilite` | Check delta 360° du suivi de fiabilité prod (ouvert 06/07) — santé/jobs/données/tickets, met à jour `project_suivi_fiabilite_prod.md` en mémoire                                                                    |
| `check-deps`       | Mises à jour Maven/npm/stack infra + changelogs — auto en début de jalon (`"Tickets du jalon X.Y.Z à implémenter :"`, mises à jour sûres appliquées au jalon) ; résumé + proposition de ticket en invocation ad-hoc |

## Migration Vue 2.2.0

Migration terminée (Epics A–F), historique complet : [wiki Migration-Vue-2.2.0](https://codeberg.org/Kwaite/strapontin/wiki/Migration-Vue-2.2.0).

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
