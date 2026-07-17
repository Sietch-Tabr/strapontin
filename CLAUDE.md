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

Pattern 3-couches (depuis v2.1.0, ticket #433) :

```
XxxController (HTTP pur) → XxxService (@Transactional, logique métier) → XxxRepository (SQL pur)
```

Pattern assumé comme non-universel (#919, audit 360°) : une vingtaine de services plus anciens ou plus simples n'ont pas de repository dédié (SQL directement dans le service) — reformater tout l'existant serait disproportionné pour un gain cosmétique ; le pattern reste la référence pour tout nouveau service non trivial.

- **Repositories** (`@Repository`, package-private) : SQL pur, `NamedParameterJdbcTemplate`
- **Services** (`@Service`) : logique métier, `@Transactional` sur les méthodes multi-JDBC
- **Records** : top-level dans le package domaine (plus d'inner classes dans les contrôleurs)
- `GlobalExceptionHandler` (`@RestControllerAdvice`) : `NoResourceFoundException` → 404, `HttpRequestMethodNotSupportedException` → 405, `AuthenticationException` → 401, `DuplicateKeyException` → 409, `DataAccessException` → 500
- `ClientController` (`/api/client/erreur`) : remontée des erreurs JS des navigateurs vers `logs_admin`, source `client-js` — câblé côté front depuis #942 (`window.onerror`/`window.onunhandledrejection` dans `main.js`, `front/src/utils/erreursClient.js` pour la construction du corps JSON)
- `SourcesController` / `SourcesService` (`/api/admin/sources`, `/api/admin/salles/{uuid}/digitick-slug|tester-digitick|fetch`, CRUD `/api/admin/digitick-salles`) : onglet Sources admin — statuts des sources + CRUD salles Digitick (admin-curé). `compterSallesParSource`/`compterArtistesParSource` (v2.6.2, #777) généralisent le nb d'items suivis (`salle_sources`/`artiste_sources` filtrés par source) aux 4 sources TM/Eventim/Deezer/Digitick — Deezer via `compterArtistesDeezer` (colonne directe `artistes.deezer_id`, pas d'entrée `artiste_sources`). Le « dernier fetch » reste global (non filtré par source) : plusieurs jobs Quartz (`FetchArtistesBatchJob`, `FetchRegionJob`, `FetchSallesAllJob`) mixent plusieurs sources dans une même exécution, sans colonne source sur `fetch_jobs_historique` — limitation connue, non corrigée (nécessiterait une réécriture de la journalisation).
- `VapidService` : génération clés VAPID EC P-256 (extrait d'`AdminService` en v2.2.0)
- `BanniereService` / `WorkerProxyService` : extraits d'`AdminService` en v2.2.3 (bannière système ; proxy HTTP worker via le `RestClient` partagé). `GenerateurSecrets` (package `securite`) : tokens/hash extraits d'`AdminService`
- `FiltreConcertsBuilder` (package `concert`) : fragments WHERE/ORDER BY/pagination communs à `DecouverteService` et `SpectacleService`
- `FiltreConcerts` (record, package `concert`, v2.5.4) : critères de filtrage partagés entre `SpectacleService.listerSpectacles` et `DecouverteService.decouvrir` — remplace les signatures à 11/8 paramètres (#743)
- `ParametresService` (package `utilisateur`, v2.5.4) : stats compteurs + flux changement email (@Transactional) — extrait de `ParametresRestController` (#744)
- `SourceConcerts` / `SourceAvecFetchLot` (module `sources`, v2.3.0) : `WorkerService` source-agnostique — ajouter une source = créer une classe + bean Spring. Identifiants salle préfixés par source (`"tm:venueId"`). Voir wiki Architecture.
- `SourceEnrichissementSalle` / `SourceEnrichissementArtiste` (module `sources`, v2.6.0) : enrichissement Wikidata/OSM/Deezer, même pattern extensible (`OverpassSalleSource`, `WikidataSalleSource`, `WikidataArtisteSource`, `DeezerArtisteSource`). Orchestré côté worker par `EnrichissementSallesService`/`EnrichissementArtistesService` (package `enrichissement`), persistance par lot de 25 avec délai 2s entre lots. `WikidataSalleSource` et `DeezerArtisteSource` ont chacun un mode découverte par nom (`SERVICE wikibase:mwapi` / `/search/artist?q=`) indépendant d'Overpass et du P2722 Wikidata respectivement — ni Overpass ni le P2722 ne sont un passage obligé. Un conflit sur l'index unique `idx_salles_wikidata_id`/`idx_artistes_wikidata_id` (même Q-ID matché sur deux entités) est rattrapé par entité (`DuplicateKeyException`) et loggé (`enrichissement-wikidata`) plutôt que de faire échouer tout le cycle nocturne. `OverpassSalleSource.echapperRegex` normalise l'apostrophe droite (`'`) en classe de caractères `['’]` (v2.6.2, #783) : le filtre `["name"~"regex"]` est appliqué côté serveur Overpass sur le nom brut, avant toute normalisation applicative — sans ça, une salle nommée avec l'apostrophe droite (nom Strapontin, issu de TM/Eventim/Digitick) ne matche jamais un nom OSM taggué avec l'apostrophe typographique courbe (fréquent sur OSM), et le nœud n'est jamais renvoyé dans la réponse. `construireRequeteDecouverte` (artiste) filtre humain+musicien/humoriste ou groupe (branche P106 Q639669/Q245068, v2.6.1 #767) ; une ambiguïté de type parmi les meilleurs candidats mwapi (ex. groupe homonyme de son fondateur) bloque tout rattachement plutôt que de deviner (#772).
- `ArtisteService.rechercherWikidata` / `SalleService.rechercherNominatim` (web, v2.6.1 #717/#718) : recherche interactive à l'ajout, mécanisme **distinct** du SPARQL mwapi ci-dessus — REST `wbsearchentities`/Nominatim `/search`, résolution différée du P2722 (`wbgetentities`) uniquement à la sélection. Persiste `wikidata_id`/`deezer_id`/`osm_id` immédiatement (mêmes repositories `enregistrerArtisteSource`/`mettreAJourIdsWikidata`, même garde-fou `DuplicateKeyException`). `core.FiltreArtiste.estTribute` (v2.6.1 #768) rejette les attractions tribute/hommage à la création (`ArtisteService.ajouter`, import `PreferencesService`). `SalleService.parseNominatimResultats` (v2.6.2, #779) : un résultat est pertinent si son `type` de premier niveau **ou** son `extratags.amenity` figure dans `TYPES_SALLE_NOMINATIM` — `class=amenity` seul n'est plus suffisant (trop large, ex. bornes de recharge). `ArtisteService`/`SalleService.contientCorrespondancePartielle` (v2.6.2, #780) : `dejaLeSuivi`/`dejaLaSuivie` testent désormais une correspondance partielle (pas une égalité stricte) pour éviter un faux « Aucun résultat » quand la recherche est un terme partiel d'un artiste/salle déjà suivi.
- `core.CorrespondanceSalle` / `core.CorrespondanceArtiste` (v2.6.0) : algorithmes de dédoublonnage flou (Levenshtein/Jaccard salles, Jaro artistes) extraits de `worker/persister` pour être partagés par `sources` (`WikidataSalleSource`) et `web` (ajout « Mes salles »/« Mes artistes », `SalleService`/`ArtisteService`) — un seul algorithme par entité dans tout le projet. Détail et justification des seuils : voir wiki Architecture.
- `ReconciliationNocturneService` + `SuiviSanteSourcesService` (package `sante`, worker, v2.6.0) : health-check/redécouverte des IDs TM suspects (`ReconciliationNocturneJob`, 3h) + compteur d'échecs consécutifs alimenté par les fetchs existants. La vérification active (`TicketmasterSource.verifierVenue`/`verifierAttraction`) réinitialise `nb_zero_consecutifs`/`suspect_depuis` dès qu'elle confirme un ID vivant — le compteur passif seul ne suffit pas à qualifier un ID mort (une salle/artiste sans concert à venir n'est pas un ID mort).
- Persistance par lot (v2.6.0) : `EnrichissementSallesService`/`EnrichissementArtistesService` (lots de 25) et `WorkerService.executerFetchArtistesBatch`/`executerFetchRegion`/`executerFetchSallesAll` (lots de 20 artistes/5 départements/20 salles) persistent au fur et à mesure plutôt qu'en un seul bloc final — une interruption (redéploiement, crash) avant la fin du cycle ne perd que le lot en cours, pas tout le travail déjà résolu. Le dédoublonnage cross-sources (`Dedoublonner`) reste correct à cette granularité : un même concert n'est jamais rapporté par deux sources pour deux artistes/départements/salles différents.
- `ReferentielSallesService` / `ReferentielArtistesService` (web, package `admin`, v2.6.0) : onglets admin Salles/Artistes — liste paginée, édition `nom_affiche`, fusion de doublons. Statut exposé `a_jour`/`a_verifier`/`invalide` (v2.6.2, #778, renommé depuis `vivant`/`suspect`/`mort` — plus autoporteur) ; dérivé à la volée du même `suspect_depuis`, aucune migration. La terminologie interne du health-check (`ReconciliationNocturneService`, `SuiviSanteSourcesService`, colonne `suspect_depuis`) reste inchangée — distincte du statut affiché.
- **Sécurité admin/sessions (v2.6.3)** : `AdminService`/`SetupService` isolent `initierRevocationAdmin`/`initialiser` en transaction `SERIALIZABLE` (`REQUIRES_NEW`) + retry sur `PessimisticLockingFailureException` (`CannotSerializeTransactionException`, dépréciée depuis Spring 6.0.3, remplacée par sa superclasse en v2.7.0 lot 2 — même comportement, capture en plus les deadlocks `CannotAcquireLockException`), via un pattern d'auto-injection par setter (`@Autowired setSelf(@Lazy X self)`, pas par constructeur — évite le problème d'auto-référence en test unitaire). `modifierUtilisateur` délègue désormais toute rétrogradation d'un admin actuel à `initierRevocationAdmin` (mêmes garde-fous 7 jours/dernier admin, pas de rétrogradation immédiate possible via `PATCH` direct). Sessions révoquées au changement de mot de passe (`UserController.changerMotDePasse`, toutes sessions pas seulement la courante) et à la rétrogradation admin effective (`RevoquerAdminsJob`, `DELETE FROM SPRING_SESSION` en raw SQL côté worker — pas de bean Spring Session dans ce module). `PATCH /api/user/profile` retiré (contournement du flux `email-change` à confirmation). Détails : [wiki Sécurité](https://codeberg.org/Kwaite/strapontin/wiki/Sécurité), [wiki Administration](https://codeberg.org/Kwaite/strapontin/wiki/Administration).
- **Pipeline fetch/dédoublonnage (v2.6.3)** : `core.Dedoublonner` inclut l'heure dans la clé de la passe exacte (matinée/soirée ne fusionnent plus à tort) et replie une ville absente sur `salleNorm` plutôt qu'une chaîne vide partagée. `ReconciliateurArtistes`/`ReconciliateurSalles` (module `worker`) priorisent systématiquement une correspondance exacte avant toute correspondance floue. `PersisteurConcertsDb.supprimerDoublonsParSalleUuid` généralisé à toutes les paires de sources (priorité TM > Eventim > Digitick). `NotificationDigestService` marque `notification_envoyee_le` séparément par canal réellement délivré (plus par OR global) et ne compte un push réussi que sur statut 2xx explicite (`ResultatEnvoiPush`). `WorkerService` : une exception dans un lot n'interrompt plus les lots suivants. Détails : [wiki Sources-de-données](https://codeberg.org/Kwaite/strapontin/wiki/Sources-de-données), [wiki Notifications-email](https://codeberg.org/Kwaite/strapontin/wiki/Notifications-email).
- **v2.6.4** : `masquerArtiste`/`demasquerArtiste` (`SpectacleService`) filtrent sur `artiste_id` (référentiel) plutôt que `artiste_norm` (texte par source, pouvait diverger entre concerts d'un même artiste réel) — `artisteNorm` reste utilisé pour les clés Vue/ancres de navigation uniquement. `PreferencesService.importerArtistes`/`importerSalles` passent désormais par `ArtisteService.resoudreArtisteId`/`SalleService.resoudreSalleUuid` (dédup floue partout, y compris l'import — principe "un seul algorithme par entité" désormais respecté sans exception) ; `SalleService.fusionnerOuAjouter` utilise `core.CorrespondanceSalle.estDoublonSalle` au lieu de son ancienne implémentation Levenshtein locale. `ReferentielArtistesService.fusionner` dédoublonne désormais les concerts en conflit avant remapping (aligné sur `ReferentielSallesService`). `ChangelogRestController` lit la version courante dynamiquement via `BuildProperties` (goal Maven `build-info`, `java/web/pom.xml`) plutôt qu'une constante figée ; `user_config.changelog_version_vue` pilote un badge "nouveautés" sur `/changelog` (`GET /api/changelog/nouveaute`). Badge "Nouveau" par concert unifié sur `vu_le IS NULL` (`AccueilService`/`SpectacleDto.estNew`) — `nbNouveau` (badge de navigation) reste volontairement restreint aux matchs artiste (documenté dans le wiki, pas un bug). `SourcesService`/`SalleService` détectent un conflit de slug Digitick déjà attribué à une autre salle (409) sur les deux chemins d'écriture (création + édition).
  ⚠️ **Piège découvert cette session** : `PushService.abonner` isole désormais le COUNT+INSERT du cap (10 abonnements/utilisateur) dans une transaction `SERIALIZABLE`+retry via le pattern d'auto-injection par setter (comme `AdminService`) — **ne jamais appeler ce type de méthode (`REQUIRES_NEW`+`SERIALIZABLE`) depuis une transaction déjà ouverte au niveau appelant** : `AdminController.modifierUtilisateur` (elle-même `@Transactional`) déléguant à `initierRevocationAdmin` en interne a produit un épuisement du pool HikariCP (`CannotCreateTransactionException`) dès qu'un flux front combinait modification nom/email et rétrogradation en un seul PATCH imbriqué. Corrigé en séquençant deux requêtes HTTP distinctes (PATCH nom/email, puis endpoint dédié de révocation) plutôt qu'un appel unique imbriqué.
- **v2.6.5** : jalon de durcissement mineur (17 tickets issus de l'audit du 4 juillet) — surtout des correctifs documentaires (javadoc, wiki), plus quelques garde-fous ciblés : `AuthService.activerCompteInvite` rattrape désormais la race d'identifiant concurrent (`DataIntegrityViolationException` → message métier, comme `SetupService.initialiser`) ; `AdminService.validerFormatConfig` valide le format des clés `VAPID_PUBLIC_KEY`/`VAPID_PRIVATE_KEY` ; `ManifestController.id` (PWA) est désormais dérivé de `PUBLIC_BASE_URL` quand configurée, stable indépendamment de l'hôte de la requête (repli sur le comportement dynamique historique sinon). Aucune migration DB.
- **v2.6.6** : jalon 100% tests (18 tickets issus de l'audit du 4 juillet, lacunes de couverture) — aucun changement fonctionnel volontaire. Seule extraction produit : `useMiseAJourServiceWorker.js` (composable pur, cycle `controllerchange` → overlay → `reload()` extrait d'`App.vue` pour être testable en Vitest sans jsdom/montage). Faux positif E2E corrigé : `pwa.e2e.ts` utilisait `page.waitForFunction()` avec un prédicat async, qui résout dès que la fonction retourne un objet (la Promise elle-même, toujours vraie) sans attendre sa valeur résolue — le test « SW actif » passait en ~100 ms quel que soit l'état réel ; corrigé par un polling manuel, révélant que `navigator.serviceWorker` n'existe jamais en E2E dev (HTTP sur IP privée ⇒ `isSecureContext` faux), limitation d'environnement documentée et testée à la place en Vitest. Détails : [wiki Roadmap](https://codeberg.org/Kwaite/strapontin/wiki/Roadmap), [wiki Développement-local](https://codeberg.org/Kwaite/strapontin/wiki/Développement-local).
  ⚠️ **Bug corps HTTP vide découvert en session** : `GET /api/banniere/json` renvoie un corps vide (`Content-Length: 0`) quand aucune bannière n'est publiée — `response.json()` lève une `SyntaxError` sur un corps vide (jamais un `null` JSON explicite). Bug pré-existant dans `useBanniere.js` masqué par un `catch(_) {}` silencieux (l'overlay public ne se mettait jamais à jour vers "pas de bannière" après effacement, jusqu'au rechargement complet suivant). Corrigé dans les deux appelants via `front/src/utils/reponseJson.js#parseJsonOuNull` (lire `response.text()` puis parser seulement si non vide). Détails : [wiki Roadmap](https://codeberg.org/Kwaite/strapontin/wiki/Roadmap).
- **v2.7.0** : fiabilité fetch/déploiement (#886/#887/#888, issus de l'analyse de fiabilité prod du 6-7 juillet). `sources.ClientHttpFactory` (nouveau, package-private) factorise le timeout HTTP explicite pour `TicketmasterSource`/`EventimSource` (connexion 10s/lecture 30s, alignées sur `DigitickSource`) — seules sources du module encore sans timeout. `spring.quartz.wait-for-jobs-to-complete-on-shutdown=true` (worker) + `TimeoutStopSec=90` (`strapontin-worker.service`, `scripts/provision.sh`) + `spring.lifecycle.timeout-per-shutdown-phase=80s` évitent qu'un déploiement pendant un cycle `fetch:region` en cours le tue silencieusement (RAMJobStore, aucune persistance de trigger) — les trois valeurs sont couplées, ne pas en changer une sans les autres. `artiste_sources.necessite_intervention_le` (V44 web/V19 worker) remplace `suspect_depuis` pour le statut admin Référentiel Artistes (`ReferentielArtistesService`, désormais binaire `a_jour`/`invalide` — `a_verifier` retiré du filtre `AdminArtistes.vue`) : le compteur passif seul générait 65% de faux positifs sur des artistes sans date à venir ; seule `ReconciliationNocturneService` pose la colonne (redécouverte confirmée en échec), `SuiviSanteSourcesService` l'efface au premier fetch positif, `fusionner` l'efface à la résolution manuelle. `salle_sources` inchangé (compteur passif jugé fiable pour les salles). Détails : [wiki Sources-de-données](https://codeberg.org/Kwaite/strapontin/wiki/Sources-de-données).
- **v2.7.0 lot 2** (#890/#891/#893/#894/#895/#899, tickets restants du jalon) : `PessimisticLockingFailureException` remplace `CannotSerializeTransactionException` (dépréciée, voir ci-dessus). `.woodpecker/ci.yaml` : `concurrency: limit: 1` racine — empêche deux déploiements prod simultanés en cas de push rapprochés sur `main`. Config Spring Cache jamais consommée retirée (`spring.cache.*`, `@EnableCaching` de `WebApplication.java` — seul cache réel du module reste `ConfigService`, indépendant). `resend-java` v4.14.1 → v4.16.0 : le contournement précédent (rester sur l'ancienne version) est remplacé par un vrai correctif — `okhttp` (transitif) est désormais un module Kotlin Multiplatform dont l'artefact Maven générique est volontairement vide côté JVM depuis okhttp 5.0.0 (`square/okhttp#8913`) ; exclusion de `com.squareup.okhttp3:okhttp` + dépendance explicite sur `com.squareup.okhttp3:okhttp-jvm` (`java/pom.xml`) résout le vrai jar. Pattern réutilisable pour toute autre lib KMP publiée ainsi. `flyway` 12.10.0 → 12.11.0, `vite` 8.1.3 → 8.1.4 (patchs mineurs). **Timers systemd de sauvegarde jamais appliqués** (#899) : `strapontin-backup.timer`/`strapontin-backup-local.timer` étaient intégralement définis dans `provision.sh` depuis le 1er juin mais jamais installés sur les LXC déjà provisionnés (prod 113, dev 114) — même piège que le gap `TimeoutStopSec=90` du lot précédent. Sauvegarde locale gelée jusqu'à 3 jours entre deux déploiements (seul déclencheur existant), purge de rétention 7 jours gelée avec elle. Corrigé en appliquant manuellement les 4 units déjà scriptées sur prod et dev (`daemon-reload` + `enable --now`) ; `provision.sh:408` corrigé pour `start` aussi `strapontin-backup.timer` (NAS), pas seulement `backup-local.timer`, sur un provisionnement neuf.
  ⚠️ **Bug réel trouvé et corrigé hors périmètre initial** : `DrawerNav.vue` (`enregistrerSwipe`) posait les écouteurs `touchstart`/`touchend` de fermeture-par-swipe sur le `<dialog>` entier plutôt que sur la poignée visuelle (`.dialog-nav-handle`) — un `touchend` sur un lien interne (bulle jusqu'au `<dialog>`) pouvait être interprété comme un swipe et fermer le menu pendant le clic, rendant la navigation intermittente sur mobile (trouvé via un test E2E flaky reproductible, `--repeat-each=8` : 3/8 échecs avant correctif, 8/8 après). Écouteurs désormais posés sur `handleRef` (la poignée) uniquement.
  ⚠️ **`scripts/seed-e2e.sh` cassé depuis la migration V24** (v2.3.0, ~20 juin) : `INSERT INTO user_artistes` référençait encore la colonne `artiste_normalise`, supprimée par `ALTER TABLE ... DROP COLUMN` dans V24 — le script échouait à chaque exécution sans que personne ne l'ait relancé depuis. Corrigé (colonnes `user_id, artiste_affiche, artiste_id`). `seed-e2e.sh` réinitialise désormais aussi `user_config.departements` à `{}` pour `e2euser` (voir section Tests E2E ci-dessous) — les deux bugs combinés avaient fait disparaître silencieusement tous les matches de concerts d'`e2euser`, cassant 6 tests E2E sur l'accueil sans rapport avec les tickets du jalon.
  ⚠️ **`e2e/tests/e2e/global-setup.ts`** : nouveau garde-fou `verifierAucunProcessAutomationParallele` — échoue le run immédiatement si un `playwright-mcp` externe est détecté persistant (deux instantanés `ps aux` espacés de 3s, pas un instantané unique pour éviter les faux positifs sur une reconnexion MCP transitoire). Une session de debug manuelle active en parallèle d'un run E2E automatisé peut le ralentir d'un facteur ~4x sans jamais le faire échouer clairement — juste tourner très lentement pendant potentiellement des dizaines de minutes.
  ⚠️ **`AdminUtilisateurs.vue` — race condition ouverture/fermeture édition** : `toggleModif` (bascule XOR) était utilisé à la fois pour le clic manuel « Modifier » et pour la fermeture automatique après sauvegarde réussie (asynchrone, `envoyerPatch`/`confirmerChangementRole`). Si le clic manuel survenait avant que la fermeture automatique d'une sauvegarde précédente n'ait basculé l'état, les deux s'annulaient et le formulaire restait fermé après un clic « Modifier » — reproductible ~50% du temps en répétition isolée. Remplacé par `ouvrirModif`/`fermerModif` à intention explicite (ajout/retrait inconditionnel), plus de course possible quel que soit l'ordre d'exécution. Test E2E `admin_retrogradationCombineeAvecNom_conserveLeNom` corrigé en parallèle (attente manquante du badge admin après confirmation de rôle, même pattern que son test sœur juste au-dessus).
  ⚠️ **Piège majeur découvert cette session — causes réelles des "flaky" E2E, ne pas rationaliser en "instabilité connue"** : sur cette machine de dev, trois facteurs indépendants ont chacun produit des runs E2E catastrophiquement longs (17 min à 3,3h au lieu de ~13 min) — (1) le serveur MCP Playwright de l'agent qui relance en boucle un navigateur externe (facteur ~4x, garde-fou ajouté ci-dessus) ; (2) le **Mac en veille répétée** faute d'être branché (`pmset -g log` montre des cycles `Idle Sleep`/`Sleep Service Back to Sleep` de ~900s récurrents sur batterie faible — gèle tout process en cours, à vérifier avec `pmset -g batt` avant tout run long, lancer avec `caffeinate -dis` en prévention) ; (3) le **rate-limiter login** (`RateLimitingLoginFilter`, Caffeine en mémoire, 15 tentatives/15 min par IP, perdu au redémarrage) épuisé par le volume de runs E2E répétés dans une même fenêtre — un redémarrage du service le vide. Toujours écarter ces trois causes avant de conclure à un bug de code.
- **v2.7.0 lot 3** (#901/#902, derniers tickets ouverts du jalon) : `core.CorrespondanceSalle.estDoublonSalle` gagne un 5ème critère — égalité des noms normalisés une fois les espaces retirés, pour couvrir une source qui omet totalement l'apostrophe/le tiret plutôt que de le remplacer par un séparateur (ex. Eventim "LOlympia" vs TM "L'Olympia" — récidive du phénomène déjà patché ponctuellement par `V27__fusionner_doublons_salles_eventim_tm.sql` #659). `worker.ReconciliateurSalles.chercherSalleParTokens` élargit son préfiltre SQL en conséquence (`replace(nom_norm, ' ', '')`), sans quoi le candidat n'est jamais soumis à la comparaison. Rattrapage des salles déjà dupliquées en base : traité séparément (opération manuelle sur données prod, hors code). `EventimSource` expose désormais un `BilanFetch` (appels/erreurs cumulés, même forme que `TicketmasterSource.BilanFetch`) remonté par `WorkerService.remonterBilanEventim` à chaque job `fetch:*` : 100% d'échecs sur ≥ 3 appels insère un `logs_admin` niveau `error` (capté automatiquement par `AlerteLogsAdminService`, contrairement au `WARN` silencieux qui avait laissé la panne Akamai du 09/07 passer inaperçue 5 jours) ; en dessous de ce seuil ou en échec partiel, reste en `warn` comme le bilan TM. Le déblocage effectif d'Eventim (piste Awin) est suivi dans le [ticket #904](https://codeberg.org/Kwaite/strapontin/issues/904), volontairement hors périmètre de ce lot.
  ⚠️ **Bug réel trouvé et corrigé hors périmètre initial — navigation drawer mobile cassée par View Transitions** : `DrawerNav.vue` (`naviguerEtFermer`) fermait le `<dialog>` via `setTimeout(() => dialogRef.value?.close(), 0)` avant d'appeler `naviguer()` — un reliquat d'avant l'introduction de `document.startViewTransition` dans `useNavigation.js` (le commentaire d'origine, "délai d'un tick pour laisser la navigation se propager", visait un `router.push()` direct, jamais révisé après l'ajout du wrapper ViewTransition). Le `close()` différé s'exécutait pendant la capture d'état de la transition, la faisant avorter en silence : `router.push()` ne résolvait jamais, la navigation restait bloquée sur la page courante. Reproductible à 100% sur mobile-chrome (confirmé par script Playwright isolé désactivant/réactivant `document.startViewTransition` pour isoler la cause), invisible sur firefox/webkit (pas de support ViewTransition ou timing différent). Corrigé en fermant le dialog de façon synchrone avant `naviguer()`. Aucun ticket dédié — trouvé en creusant un échec E2E `drawer_artistes_vuerouter_sans_rechargement_document` (mobile-chrome) pendant la session 2.7.0 lot 3.
  ⚠️ **`totp.e2e.ts` — cleanup gardé par `if (secret)` pouvait ne jamais s'exécuter** : le nettoyage admin (`reset-totp` de `e2euser`) en fin de test ne se déclenchait que si l'enrollment avait réussi dans **cette** exécution. Si `/api/totp/setup` échouait parce que le compte avait déjà une 2FA active (résidu d'un run précédent interrompu avant son propre cleanup — ex. process tué en plein test), `secret` restait vide et le cleanup ne s'exécutait jamais : l'échec s'auto-perpétuait sur tous les runs suivants jusqu'à une réinitialisation manuelle. Constaté en session (run E2E anormalement long après un kill de process). Corrigé : cleanup désormais inconditionnel (best-effort systématique en `finally`), auto-guérison garantie dès l'exécution suivante quelle que soit la cause de l'échec initial.
- **v2.7.0 lot 4** (#904/#907, 15-16 juillet 2026) : le diagnostic initial de #904 (fingerprinting TLS Akamai JA3/JA4, nécessitant le programme d'affiliation payant Awin) était **erroné** — revérifié intégralement en session par tests curl répétés (poste hors infra + IP prod elle-même) plutôt que d'exécuter le plan Awin déjà écrit. Cause réelle : Akamai bloque en 403 tout `User-Agent` de forme `Mozilla/5.0...` (navigateur imité, quel qu'il soit) sans en avoir la signature TLS/fingerprint réelle, alors qu'un `User-Agent` non-navigateur (`curl` par défaut, `Java/25.0.1`, bot identifié) passe systématiquement sans restriction, réponse JSON complète à l'appui. `git log` a montré que les headers Chrome complets de `EventimSource` dataient du 19/05/2026 (commit `231c026`, "contournement anti-bot") — un contournement d'un tout autre problème, devenu le déclencheur du blocage une fois Akamai durci le 09/07. Corrigé : `User-Agent` honnête (`EventimSource.USER_AGENT` = `StrapontinBot/1.0 (+https://strapontin.kwaite.fr)`), retrait des headers `sec-ch-ua*`/`Sec-Fetch-*`/`Referer`/`Origin` devenus incohérents. Second bug latent découvert en testant le correctif en LXC dev : `Accept-Encoding: gzip, deflate, br` (présent depuis le même commit de mai) faisait échouer la désérialisation JSON une fois le 403 levé — `SimpleClientHttpRequestFactory` (JDK `HttpURLConnection`, introduit par #887 le 08/07, un jour avant le blocage Akamai) ne décompresse pas lui-même la réponse quand l'appelant fixe `Accept-Encoding` explicitement, contrairement au comportement transparent du JDK quand ce header est absent. Ce second bug était totalement masqué par le 403 depuis le 09/07 — sans le test réel en dev (pas seulement les tests unitaires mockés), il serait passé inaperçu. Retiré, plus aucun `Accept-Encoding` explicite. Test de régression `EventimSourceTest` avec `MockRestServiceServer` lié au vrai builder de production (`EventimSource.builderAvecHeadersParDefaut()`), vérifiant `User-Agent` et absence d'`Accept-Encoding`.
  ⚠️ **#907, boucle de bounces email active pendant la session** : creusé en marge de #904 — `EventimSource.BilanFetch` (mécanisme #901/#895) a bien détecté la panne Eventim et inséré un `logs_admin` niveau `error`, mais `AlerteLogsAdminService` l'a envoyé à `CONTACT_EMAIL` (`noreply@...`, sans boîte de réception) faute d'adresse admin distincte — `EmailService.obtenirEmailPremierAdmin()` ne servait qu'au repli de l'expéditeur, jamais comme destinataire d'alerte. Le bounce résultant a lui-même déclenché `envoyerNotificationBounce` vers ce même `CONTACT_EMAIL`, rebouclant indéfiniment (115+ occurrences constatées en 14 min avant la session, boucle encore active — bounce toutes les 5-10s — au moment du diagnostic ; quota journalier Resend épuisé, bloquant tout email applicatif). Corrigé : `EmailService.obtenirDestinataireAlerte()` (nouvelle clé `ADMIN_ALERT_EMAIL`, repli sur le premier admin, **jamais** sur `CONTACT_EMAIL`) devient le destinataire de `envoyerNotificationBounce`/`envoyerNotificationPlainteSpam`/`envoyerAlerteBackup`/`envoyerAlerteWorkerInactif`/`envoyerAlerteLogsAdmin` ; `WatchdogWorkerService` et `AlerteLogsAdminService` délèguent désormais la résolution du destinataire à `EmailService` plutôt que de lire `CONTACT_EMAIL` directement. Garde-fou anti-boucle ajouté indépendamment de la correction d'adresse : `envoyerNotificationBounce`/`envoyerNotificationPlainteSpam` throttlées à 1 envoi/heure maximum par type (persisté en `app_config`, même pattern que `ALERTE_LOGS_ADMIN_CURSEUR` — survit aux redéploiements).
  ⚠️ **Bug réel trouvé et corrigé hors périmètre initial — transaction avortée par un conflit `idx_salles_wikidata_id`** : suite explicite de la consigne de session (traiter toute erreur E2E, flaky ou non, comme provenant de cette session), `salles_ajouterEtSupprimer_fluxComplet_toastSuccesAuDeux` échouait à 100% sur les 5 navigateurs (pas un flaky). `SalleService.ajouter` (`@Transactional`) attrapait déjà `DuplicateKeyException` sur `mettreAJourWikidataId` (#718) mais sans savepoint : PostgreSQL marque toute la transaction « annulée » dès la violation de contrainte, faisant échouer l'`INSERT INTO user_salles` suivant même si l'exception est capturée côté Java — invisible en test unitaire (`salleRepository` mocké, pas de vraie transaction Postgres), uniquement visible avec une vraie base (E2E réel sur LXC dev). Corrigé par `Propagation.NESTED` (savepoint sur la connexion existante, pas `REQUIRES_NEW` qui consommerait une connexion HikariCP supplémentaire depuis une transaction déjà ouverte — piège déjà documenté v2.6.4) via le pattern d'auto-injection par setter déjà utilisé pour `AdminService`/`PushService`. Piège rencontré en cours de correctif : catcher l'exception **à l'intérieur** de la méthode `NESTED` fait tenter à Spring de _libérer_ le savepoint (au lieu de le _rollback_), provoquant un second échec en cascade (`TransactionSystemException`) — l'exception doit se propager jusqu'à l'appelant. `ArtisteService` n'a jamais eu ce problème : la résolution Wikidata s'exécute après le commit de la transaction principale (`executerApresCommit`, #786/#788), pour un tout autre motif qui protège aussi de cet effet de bord. Branche et PR séparées de #904/#907 (bug sans rapport), correctif isolé.
  ⚠️ **Bug de production actif trouvé après le déploiement du correctif #907** : une fois les alertes réellement délivrées (au lieu de boucler sur `CONTACT_EMAIL`), 4 emails de digest `logs_admin` sont arrivés dans la nuit du 15 au 16 juillet. Analyse : 11 échecs de job Quartz (`fetchArtistesBatch` ×3, `fetchRegion` ×3, `fetchSallesAll` ×3, `recalculerMatches` ×2) sur la période, **100% avec la même cause racine** (`java.lang.NoClassDefFoundError: okhttp3/RequestBody`, vérifié un par un) — chaque job appelant `NotificationDigestService.envoyerEmail` (module `worker`) plantait dès la construction du client Resend (`new Resend(cle).emails()`), remontant non-catché jusqu'à Quartz (`LogAdminJobListener.jobWasExecuted` insère alors un `logs_admin` niveau `error` à chaque échec de job). Cause : `java/worker/pom.xml` n'avait jamais reçu le correctif okhttp/Kotlin-Multiplatform du jalon 2.7.0 lot 2 (#895) — l'exclusion de `com.squareup.okhttp3:okhttp` (coquille vide côté JVM depuis okhttp 5.0.0) est définie dans le `<dependencyManagement>` du pom parent, mais la dépendance de remplacement `okhttp-jvm` n'avait été ajoutée explicitement que dans `java/web/pom.xml`, jamais dans `java/worker/pom.xml` — écart resté invisible tant que les alertes email du worker ne partaient jamais réellement (masqué par la boucle #907 elle-même, puis par l'absence totale de quota Resend). Corrigé en ajoutant la même dépendance `okhttp-jvm` à `java/worker/pom.xml`. Vérifié : le jar worker reconstruit contient désormais `BOOT-INF/lib/okhttp-jvm-5.4.0.jar` (absent avant). Branche et PR dédiées, distinctes de #904/#907 et du correctif `SalleService` (trois bugs indépendants trouvés dans la même session d'investigation).
- **v2.7.0 lot 5** (#909/#912, derniers tickets ouverts du jalon) : le bilan de panne agrégé (compteurs appels/erreurs par cycle, escalade `logs_admin` niveau `error` sur panne totale — introduit pour Eventim par #901) est factorisé et généralisé à toutes les sources/canaux plutôt que dupliqué par source. `core.BilanFetch` (record `appels`/`erreursHttp`, remplace les records locaux `TicketmasterSource.BilanFetch`/`EventimSource.BilanFetch`) porte la logique d'escalade partagée (`evaluer(typeJob, nomSource, seuilAppelsMin)` → `Optional<EntreeLog>` niveau/message/meta) ; `core.CompteurBilanFetch` (compteur `AtomicInteger` composable) remplace les paires de compteurs dupliquées dans chaque source. `sources.SourceAvecBilanFetch` (interface `preleverBilan()`) est implémentée par `TicketmasterSource`, `EventimSource`, `DigitickSource` (nouveau, #909) et `ResolveurNomCanonique`/Deezer (nouveau, #912, n'implémente pas `SourceConcerts`). `WorkerService.remonterBilanTm`/`remonterBilanEventim` (une méthode par source) sont remplacées par une seule méthode générique (`remonterBilan`) itérant sur `List<SourceConcerts> sources` via `instanceof SourceAvecBilanFetch` — ajouter une source au bilan ne demande plus de toucher `WorkerService`. `concertsManques` (spécifique à TM, pagination plafonnée) reste hors du tronc commun, remonté séparément (`remonterBilanConcertsManquesTm`, toujours en `warn`, jamais d'escalade). `NotificationDigestService.envoyerPush` (#912) gagne le même mécanisme : compteur par cycle de digest, escalade dans le `finally` d'`executerDigest` — `ABONNEMENT_MORT` (410/404) n'est volontairement pas compté comme une erreur (nettoyage normal, pas une panne du canal). Les deux mécanismes réutilisent `LogsAdminWorker.inserer` (jusque-là sous-utilisé, un seul appelant) comme point d'insertion `logs_admin` commun. Aucune migration DB.
- **v2.7.0 lot 6** (#915-960, audit 360° du 16/07/2026 — 47 tickets, dernier lot du jalon) : refactors d'architecture, sécurité, SQL/Flyway, frontend, tests et observabilité. `WorkerService` (939L) scindé en `WorkerStatsService` (stats + bilans de panne), `WorkerFetchService`, `WorkerPurgeService`, `WorkerRevocationService` (#915) ; `AdminService` (735L) scindé en `ConfigAdminService`/`JobsAdminService` (#916) ; `EmailService` (776L) scindé en `EmailService` (transactionnel) + `EmailAlerteService` (bounce/spam/backup/watchdog/logs-admin) + `EmailSender` (plomberie Resend partagée, #925) ; `TotpChallengeService` extrait d'`AuthController` (#917) ; `ArtisteService`/`SalleService` unifiés sur le `RestClient` partagé (#923, retrait des `HttpClient` JDK locaux). `core.extraireDeptFrancais`/`normaliserCasse` mutualisent TM/Eventim/Digitick (#920/#921) ; `core.ConfigCipher` extrait la dérivation SCrypt+AES-256-GCM dupliquée entre `ConfigService`/`ConfigServiceWorker` (#922). Renommage `concerts.salle_uuid`/`user_salles.salle_uuid` → `salle_id` (web V45/worker V22) pour cohérence avec `artiste_id` (#933, tout le code mis à jour) — `salles.id` inchangé (déjà renommé en V24). `PreferencesService.declencherWorkerApresImport` passe au pattern `afterCommit` (#932, même motif qu'`ArtisteService`). `AdminService.modifierUtilisateur` rejette désormais explicitement la combinaison nom/email + rétrogradation admin en un seul `PATCH` (#940) — seul le frontend séquençait déjà cet appel depuis l'incident v2.6.4, le backend restait vulnérable à l'épuisement du pool HikariCP via un appelant direct. `ClientController` (`/api/client/erreur`) enfin câblé côté front (`window.onerror`/`unhandledrejection`, `main.js`, #942) — endpoint documenté depuis 2.1.2 mais jamais relié. `NotificationDigestService` rend Resend/PushService injectables via fabriques (#943), permettant de tester leur chemin nominal de succès. Observabilité : `logback-spring.xml` ajouté côté worker (#951), `WorkerProxyService` propage `X-Correlation-ID` vers le worker (#952), `micrometer-registry-prometheus` + métriques `strapontin.worker.job.duration`/`strapontin.worker.source.erreurs` (#953), `SourcesHealthIndicator` (quota TM + statut sources) exposé via `/actuator/health` côté worker (#954). Frontend : composable `useReferentielAdmin` partagé par `AdminArtistes`/`AdminSalles` (#934), harmonisation sur `useDialog` (#935), `estActif` extrait dans `useNavigation` (#936), couleurs sémantiques de `pages.css` alignées sur les variables `base.css` (#937), thème sombre pour `offline-fallback.js` (#938). Détails : [wiki Architecture](https://codeberg.org/Kwaite/strapontin/wiki/Architecture), [wiki Sécurité](https://codeberg.org/Kwaite/strapontin/wiki/Sécurité).
- `core` : `QuotaTM` (type partagé) et `UtilitairesChaine.echapperJson` mutualisés en v2.2.3
- `SpectacleService` et `DecouverteService` : COUNT et filtres disponibles calculés en parallèle via `CompletableFuture` (threads virtuels)
- `RecalculerMatchesService` : recalcul parallèle par utilisateur via `Executors.newVirtualThreadPerTaskExecutor()`, 1 transaction par user (`TransactionTemplate`)
- `WebConfiguration` : beans `ObjectMapper` + `RestClient` (timeout 5 s / 10 s)
- `PersisterConcerts` (v2.5.7) : orchestrateur injectant `ReconciliateurSalles`, `ReconciliateurArtistes`, `PersisteurConcertsDb`. `resoudreArtisteParNomNorm` couvre toutes les sources sans `artisteId`, y compris Digitick (pas d'identifiant artiste externe).
- Référence : `PersisterConcerts` + `RecalculerMatchesService` dans `java/worker/`

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

| Source                                                | Statut                                                                                                                                                                                                                                                                                    |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Ticketmaster Discovery v2                             | Core                                                                                                                                                                                                                                                                                      |
| Eventim France                                        | Core                                                                                                                                                                                                                                                                                      |
| Digitick (`web.digitick.com`)                         | Core (v2.4.0, #610) — scraping HTML par salle, slug dans `salle_sources`                                                                                                                                                                                                                  |
| Deezer Search API                                     | Autocomplete artiste (`/artistes`) — sans clé. Remplace MusicBrainz (bloqué).                                                                                                                                                                                                             |
| Wikidata (SPARQL) + OSM (Overpass, `overpass-api.de`) | Enrichissement nocturne (v2.6.0, #711/#716) — noms canoniques, Deezer ID, P6262 (cross-validation TM). Visible dans `/admin` > Sources. Overpass en incident de surcharge documenté depuis avril 2026 (bbox au lieu d'`area[...]`, échecs 504 non bloquants) — voir `OverpassSalleSource` |
| Wikidata Search (REST) + Nominatim (OSM)              | Recherche interactive à l'ajout (v2.6.1, #717/#718) — sans clé API. Nominatim : `User-Agent` obligatoire, 1 req/s (respecté par la cadence manuelle de recherche, pas de debounce nécessaire — recherche soumise par bouton, jamais en direct)                                            |
| Bandsintown                                           | Abandonné — HTTP 401 depuis 2026-04-10 (#65)                                                                                                                                                                                                                                              |
| MusicBrainz                                           | Bloqué — IP SFR blacklistée par Cloudflare (#293)                                                                                                                                                                                                                                         |

## Jobs background (Quartz)

Paradigme : **pré-fetch en arrière-plan, jamais d'appel API depuis le browser**.

Cron adaptatif (v2.4.0, #721) : `0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?` — 11 cycles/jour.

| Job                         | Déclenchement                   | Description                                                                                   |
| --------------------------- | ------------------------------- | --------------------------------------------------------------------------------------------- |
| `FetchArtisteJob`           | Ajout artiste + cron adaptatif  | Fetch TM + Eventim par nom artiste                                                            |
| `FetchArtistesBatchJob`     | Cron adaptatif (H:00)           | Fetch TM en lots de 20 attractionIds                                                          |
| `FetchRegionJob`            | Cron adaptatif (H:30) + manuel  | Fetch TM + Eventim par département                                                            |
| `FetchSallesAllJob`         | Cron adaptatif (H:30)           | Fetch de toutes les salles avec sources configurées (v2.4.0, #720)                            |
| `FetchSalleJob`             | Ajout salle + déclenchement API | Fetch par identifiants préfixés (`CLE_IDENTIFIANTS`, ex. `"tm:venueId"`)                      |
| `RecalculerMatchesJob`      | Cron adaptatif (H:00)           | Recalcule `user_matches` pour tous les users                                                  |
| `PurgerConcertsJob`         | Cron quotidien (minuit)         | Supprime concerts passés depuis > 7 jours                                                     |
| `PurgerLogsAdminJob`        | Cron quotidien (0h15)           | Supprime les `logs_admin` > 30 jours et `fetch_jobs_historique` > 7 jours                     |
| `RevoquerAdminsJob`         | Cron horaire (H:00)             | Applique les révocations admin dont la date est dépassée                                      |
| `ReconciliationNocturneJob` | Cron quotidien (3h)             | Health-check/redécouverte IDs TM suspects + enrichissement Wikidata/OSM/Deezer (v2.6.0, #706) |

Rate limiting TM : max 2 jobs simultanés (3 → HTTP 429). Quota TM persisté dans `app_config` (`TM_QUOTA_JSON`).
`FetchArtistesBatchJob`, `FetchRegionJob`, `FetchSallesAllJob`, `RecalculerMatchesJob`, `ReconciliationNocturneJob` annotés `@DisallowConcurrentExecution`. `WatchdogWorkerService` (`@Scheduled`, côté web) : alerte email si worker inactif > 3 h. `AlerteLogsAdminService` (`@Scheduled` 15 min, côté web, v2.6.1 #765) : digest email sur nouvelles entrées `logs_admin` niveau `error`, curseur persisté dans `app_config` (`ALERTE_LOGS_ADMIN_CURSEUR`, pas en mémoire — survit aux redéploiements), n'avance qu'après envoi réussi.
`spring.quartz.wait-for-jobs-to-complete-on-shutdown=true` (v2.7.0, #886) : un déploiement pendant un cycle en cours attend sa fin plutôt que de le tuer silencieusement (`RAMJobStore` ne persiste aucun trigger). Borné par `TimeoutStopSec=90` (`strapontin-worker.service`) et `spring.lifecycle.timeout-per-shutdown-phase=80s` — nécessite que `TicketmasterSource`/`EventimSource` aient un timeout HTTP borné (#887, `sources.ClientHttpFactory`), sinon un hoquet réseau ferait pendre le déploiement.

## Authentification

Invite-only. Login par identifiant (insensible à la casse). Rôles : `user` | `admin`.
2FA TOTP optionnel pour tous les utilisateurs — enrollment via `/parametres/totp`, challenge automatique à la connexion si activé. Cap : 500 artistes / 200 salles par user.
Algorithme TOTP conforme RFC 6238 (HMAC-SHA1, 30 s, ±1 fenêtre, saisie tolérante aux espaces). Réinitialisation 2FA par un admin depuis `/admin` (bouton « Réinit. 2FA », endpoint `POST /api/admin/utilisateurs/{id}/reset-totp`) pour un compte bloqué. Succès et échecs d'auth (login, rate-limit, TOTP) consignés dans `logs_admin` (source `auth`). Rate-limit login : 15 tentatives / 15 min par IP (bucket4j → 429).
Spring Security form login → cookie `SESSION` (JDBC-backed, survit aux restarts, `Max-Age=2592000`, `HttpOnly`, `SameSite=Lax` — pas `Strict`, compatibilité Safari sur navigation entrante, #926 — configuré via `DefaultCookieSerializer`, pas via `application.properties`). Mots de passe hashés en bcrypt.
Détails : [wiki Authentification](https://codeberg.org/Kwaite/strapontin/wiki/Authentification)

## Dédoublonnage inter-sources

Deux passes : exacte sur `(artiste_norm, date, ville_norm)`, fuzzy Levenshtein normalisé ≥ 0.85.
Résolution UUID-first (v2.3.0, ticket #621) : `PersisterConcerts.resoudreIdentitesSources` consulte `salle_sources`/`artiste_sources` avant la réconciliation floue. Absorbe les renommages de salle. Pour les salles TM absentes de `salle_sources`, upsert automatique dans `salles` + `salle_sources` (ticket #633). Les passes de réconciliation propagent `salleUuid`/`artisteId` (ticket #632).
`estDoublonSalle` : Levenshtein ≥ 0.75 | Jaccard tokens > 0.5 | token pivot (≥ 7 chars, Jaccard > 0.3, ≥ 2 tokens chacun — #702) | égalité sans espaces (#902, couvre une source qui omet l'apostrophe plutôt que de la remplacer par un séparateur — ex. "LOlympia" vs "L'Olympia").
`estDoublonArtiste` : Jaro ≥ 0.95 | containment ratio ≥ 0.65. `normaliserTexte` : `$` supprimé sans espace, `&` → `and` (#703).
`chercherSalleParTokens` : tous les tokens testés (pas seulement le premier), filtre dept pour éviter les homonymes inter-villes (#702), préfiltre SQL élargi à l'égalité `nom_norm` sans espaces (#902).
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
**v2.7.0 (V44 web / V19 worker)** : `artiste_sources.necessite_intervention_le TIMESTAMPTZ` (#888). Dernière version web : V44, worker : V19.
Détails : [wiki Schéma](https://codeberg.org/Kwaite/strapontin/wiki/Schéma-de-base-de-données)

## Conventions Java

- Identifiants en français (`nombreUtilisateurs`, `calculerTotal`)
- Logging : SLF4J, jamais `System.out`
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

| Skill              | Usage                                                                                                                                            |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `/deploy-dev`      | Build Maven (avec tests) + scp JARs + restart LXC dev + tail logs                                                                                |
| `/test`            | Tests unitaires Maven + E2E Playwright + résumé des résultats                                                                                    |
| `/pre-pr`          | Wiki + CLAUDE.md + changelog + README + commit docs + `tea pr create`                                                                            |
| `/suivi-fiabilite` | Check delta 360° du suivi de fiabilité prod (ouvert 06/07) — santé/jobs/données/tickets, met à jour `project_suivi_fiabilite_prod.md` en mémoire |

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
