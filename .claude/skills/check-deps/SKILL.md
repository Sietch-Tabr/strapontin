---
name: Vérification dépendances
description: >
  Vérifie les mises à jour disponibles pour les dépendances Maven (java/pom.xml,
  4 modules), npm (front/package.json) et la stack infra (PostgreSQL, Java/Temurin,
  Node, template LXC dans provision.sh et .github/workflows), analyse les changelogs pour
  repérer breaking changes et nouveautés exploitables.
when_to_use: >
  Invoquer automatiquement dès qu'un message utilisateur commence par
  "Tickets du jalon X.Y.Z à implémenter" (mode jalon : mises à jour sûres
  appliquées et intégrées au jalon en cours, jamais de ticket de bruit) ;
  sinon invocation ad-hoc explicite (mode hors-jalon : résumé + proposition
  de ticket constat-only, aucune modification appliquée).
---

## 1. Détecter le mode

- **Mode jalon** : le message qui déclenche cette session commence par `"Tickets du jalon X.Y.Z à implémenter :"`.
- **Mode hors-jalon** : toute autre invocation (ad-hoc, explicite).

Le mode ne change que l'étape 8 (application) — la collecte et l'analyse (étapes 2 à 7) sont identiques dans les deux cas.

## 2. Scanner Maven

```bash
mvn -f java/pom.xml versions:display-property-updates
mvn -f java/pom.xml versions:display-dependency-updates
```

Le projet épingle la plupart de ses versions via des propriétés dans le pom parent (`<revision>` excepté — jamais touché ici, cf. étape 11) : c'est `display-property-updates` qui reflète le mécanisme réellement modifié à chaque jalon d'après l'historique CLAUDE.md. `display-dependency-updates` complète pour les dépendances non gérées par propriété.

Vérifier aussi séparément si une version plus récente du parent Spring Boot (BOM) est disponible.

Plugins Maven : `mvn -f java/pom.xml -N versions:display-plugin-updates`. Ne lire que sa première ligne (« All plugins with a version specified are using the latest versions ») ; les lignes `->` qui suivent proposent des versions plus anciennes (`maven-pmd-plugin 3.28.0 -> 2.2`), faux signal de rétrogradation à ignorer. Les plugins sans version dans le pom (surefire, compiler, jar…) suivent le parent Spring Boot : leur mise à jour passe par celle du parent.

## 3. Scanner npm

```bash
cd front && npm outdated
cd ../e2e && npm outdated
```

Deux projets npm : `front/` (application) et `e2e/` (Playwright, tests de bout en bout). Les deux se scannent à chaque invocation ; `e2e/` y a échappé jusqu'au jalon 3.1.2 (Playwright resté en 1.63.0 alors que la 1.64.1, avec WebKit 27.2 pour un hôte en macOS 27.2, existait). Lister current/wanted/latest par paquet. Pour Playwright, relever aussi le moteur embarqué : `npx -y playwright@<version> install --dry-run webkit` (affiche « WebKit X.Y »), à comparer à celui de l'hôte (`sw_vers`) ; un moteur de test plus ancien que le système est un écart à signaler.

## 4. Scanner la stack infra

Informatif uniquement — jamais appliqué automatiquement, un changement d'OS/PostgreSQL/Java majeur dépasse le cadre d'un jalon applicatif.

**Workflow de la CI** : actions épinglées par SHA de commit (version en commentaire) et images désignées par condensat (`docker run` d'osv-scanner et de gitleaks). Lister les actions : `grep -n 'uses:' .github/workflows/ci.yml`, comparer chaque version à la dernière release (`gh api repos/<action>/releases/latest --jq .tag_name`) et vérifier que le SHA épinglé correspond bien au tag (`gh api repos/<action>/git/ref/tags/<tag>`). Dependabot propose ces mises à jour par PR (écosystème `github-actions`) : les relire comme les autres dépendances (changelog). Versions d'outils des runners : Java Temurin 27 et Node 26 (`setup-java`, `setup-node`).

**Scanner OSV** : l'image est épinglée dans `scripts/analyse-dependances.sh` (`IMAGE_SCANNER`), que le job `dependances` de la CI lance ; elle se compare à la dernière release (`curl -s https://api.github.com/repos/google/osv-scanner/releases/latest`, champ `tag_name`). C'est le contrôle bloquant de la CI : un scanner périmé est un angle mort.

**Hors périmètre, géré par l'utilisateur** (ne pas le reproposer) : les mises à jour `apt` des conteneurs LXC, et les outils du poste (brew, gh, Docker) via topgrade.

Grep des versions épinglées dans `scripts/provision.sh` (PostgreSQL, template Debian LXC, Temurin/Java) et `.github/workflows/ci.yml` (versions de Java et de Node) ; comparer aux dernières versions stables publiées (WebSearch/WebFetch).

## 5. Analyser le changelog de chaque dépendance obsolète

Pour chaque ligne remontée aux étapes 2-4 : WebFetch/WebSearch vers le changelog réel (GitHub releases, page Maven Central, changelog npm/GitHub du paquet). Cette lecture se termine **avant toute synthèse ou analyse présentée à l'utilisateur** (y compris celle qui attend son accord en mode jalon) : un rapport « changelogs à lire après le go » est une étape sautée. Une dépendance dont le changelog n'a pas pu être lu n'est ni bumpée ni reportée : relancer la lecture par une autre source (notes du dépôt au tag, archive du tag décompressée dans un dossier vide, registre du paquet, page du site officiel) jusqu'à l'obtenir, et ne demander à l'utilisateur que si toutes échouent. Classer :

- **Sûr** (patch/mineur, pas de breaking change identifié dans le changelog) → applicable directement en mode jalon.
- **À risque** (majeur, ou changelog mentionnant explicitement un breaking change) → jamais appliqué automatiquement, remonté comme point de décision.

Repérer aussi, même sur une mise à jour sûre, les **nouveautés/optimisations** potentiellement pertinentes pour ce projet (même démarche que les revues historiques de `flyway` 13 ou `resend-java` déjà faites manuellement) — chacune devient un point de décision explicite (cf. étape 8), jamais une simple mention noyée dans le rapport qui n'engage à rien.

⚠️ **Ne jamais grouper plusieurs bumps de dépendances dans un seul commit sans que chacune ait individuellement traversé cette étape** — un message `"chore: bump X, Y, Z"` en une ligne masque l'oubli de cette analyse pour une dépendance particulière (déjà arrivé : `resend-java` 4.23.0→4.24.0 groupé avec 3 autres dépendances le 11/09/2026, `feat(webhooks): add signing secret rotation endpoint` jamais repéré ni remonté, cf. `feedback_dependances_versions_changelogs`).

## 6. Vétusté et CVE (chaque dépendance pinnée, pas seulement celles avec une mise à jour disponible)

Une dépendance déjà à sa dernière version publiée peut quand même être un risque si le projet amont est mort ou vulnérable — l'étape 5 ne le détecte pas (elle ne s'intéresse qu'aux dépendances _en retard_).

Pour chaque propriété de version du pom parent (`java/pom.xml`) et chaque dépendance npm épinglée (`front/` et `e2e/`) : vérifier la date de dernière publication (`maven-metadata.xml` → `<lastUpdated>`, ou `npm view <paquet> time` / registre npm) et rechercher une CVE connue (WebSearch `"<paquet>" CVE <année>`, ou base OSV/GitHub Advisory).

- **Silencieux dans le rapport si RAS** : dernière publication < 12 mois et aucune CVE ouverte.
- **Signalé** (mode jalon : mentionné sans bloquer le jalon ; mode hors-jalon : dans le rapport, avec proposition de ticket constat-only si pertinent) : aucune publication depuis 12+ mois, projet visiblement à l'arrêt, ou CVE trouvée sans version corrigée disponible.
- Ne jamais rétrograder ou remplacer une dépendance vétuste par une alternative de son propre chef — même règle que l'étape 11 : constat, jamais de piste d'implémentation imposée.
- **Étape obligatoire, jamais sautée** : lancer `bash scripts/analyse-dependances.sh local` (Docker requis ; même analyse OSV que le job bloquant `dependances` de la CI de `main`, exceptions de `osv-scanner.toml` comprises). Un avis sans correctif installable se signale à l'utilisateur **avant la PR** pour décider d'une exception datée, jamais après le merge : la CI de `main` échoue sinon avant `construction`, donc sans JAR à déployer (jalon 3.1.1 avec deepmerge, jalon 3.1.2 avec glob et minimatch). Le compte rendu de l'invocation écrit « étape 6 : faite » avec le résultat du scan.

## 7. Audit périodique des fonctionnalités inexploitées (dépendances déjà à jour)

L'étape 5 n'analyse que le _delta_ entre la version épinglée et la dernière disponible — une fonctionnalité déjà présente dans une version qu'on utilise **depuis longtemps**, jamais adoptée dans le code, ne remonte plus jamais dans aucun scan futur (la dépendance n'est plus "en retard" sur elle). Trouvé le 18/09/2026 : le module `Suppressions` de `resend-java`, disponible depuis la v4.18.0 (bien avant tous les bumps tracés dans l'historique du pom), jamais repéré par aucune invocation de ce skill.

- Ne pas auditer ça à chaque invocation (coût disproportionné pour un projet perso, cf. `feedback_validation_et_perimetre`) — seulement à la demande explicite de l'utilisateur sur une dépendance donnée ("on a implémenté les dernières nouveautés de X ?"), ou en mode jalon si le jalon touche déjà une fonctionnalité liée à cette dépendance.
- Démarche : lister les classes/méthodes du SDK réellement utilisées dans le code (grep) vs. celles exposées par la version actuellement installée (décompilation du jar en cache local `~/.m2`, `javap -classpath <jar> <classe>` — jamais deviner une signature). Comparer au changelog complet du projet amont (pas seulement le delta courant) pour retrouver depuis quelle version chaque capacité inexploitée existe.
- Chaque capacité pertinente trouvée suit le même traitement qu'une nouveauté de l'étape 5 : point de décision explicite (étape 8), jamais une mention seule.

## 8. Application (mode jalon uniquement)

- Bumper les propriétés/versions concernées. **Ne jamais toucher `<revision>`** (version applicative du pom parent) — décision utilisateur explicite requise, cf. mémoire `feedback_dependances_versions_changelogs`.
- Recompiler/tester avant de considérer la mise à jour intégrée :
  ```bash
  mvn -pl core,sources,web test
  mvn -pl worker test -am
  cd front && npm run test:unit
  ```
  Une mise à jour de `e2e/` (Playwright) commence par `cd e2e && npx playwright install chromium firefox webkit` : chaque version embarque ses propres navigateurs, et leur absence se présente comme un run entier en échec (`browserType.launch: Executable doesn't exist`, 1 281 échecs constatés pour la 1.65.0). Elle se valide ensuite par la suite E2E complète, 5 navigateurs, un run unique à zéro échec et zéro instable.
- Toute mise à jour "à risque" **ou nouveauté/optimisation repérée jugée pertinente** (étape 5) : poser la question précise **dans le même tour** — implémentation immédiate / ticket dédié / ignoré pour une nouveauté, à appliquer ou non pour une mise à jour à risque — jamais noter "en attente de décision" ou "à mentionner" sans la poser (cf. mémoire `feedback_validation_et_perimetre`). Seule une réponse explicite ferme le point ; ne bloque pas le reste du jalon si elle n'est pas immédiate — continuer sur les tickets du jalon en parallèle.
- Le bump de dépendances rejoint le commit/la PR du jalon en cours, jamais une PR séparée.

## 9. Rapport (mode hors-jalon)

Résumé structuré par dépendance : version actuelle → disponible, résumé du changelog, sûr/à risque, nouveauté notable. Si quelque chose mérite un suivi, proposer un ticket — **constat/analyse uniquement, jamais de piste d'implémentation** (cf. mémoire `feedback_tickets`). Aucune modification de fichier sans validation explicite de l'utilisateur.

## 9 bis. Dette déclarée des avertissements front

`front/avertissements-attendus.txt` liste les avertissements `npm ci` et `npm run build` encore émis, chacun avec sa cause amont et la date de sa dernière vérification ; `scripts/avertissements-front.sh` (job `front`) fait échouer un avertissement absent de la liste **et** un avertissement de la liste qui a disparu. À chaque invocation en mode jalon : relire chaque ligne contre l'état amont (paquet, version, demande de fusion citée), mettre à jour la date ou supprimer la ligne si la cause est corrigée. Ne jamais allonger la liste sans cause nommée ; l'objectif est qu'elle soit vide.

## 10. Pièges connus à revérifier systématiquement

- **`okhttp`** (dépendance transitive Resend) : artefact Maven générique vide depuis okhttp 5.0.0 (module Kotlin Multiplatform). resend-java ≥ 4.28.0 dépend directement de `okhttp-jvm`, version gérée dans le pom parent : à chaque montée de Resend, vérifier par `mvn dependency:tree -pl web,worker` qu'aucun `com.squareup.okhttp3:okhttp` vide n'est tiré (le worker avait été oublié une fois, v2.7.0 lot 4, bug de prod silencieux).
- Le plugin Maven `versions` peut suggérer une **rétrogradation fausse** (`resend-java` → 4.13.0 déjà rencontré) — toujours vérifier `maven-metadata.xml` sur Maven Central directement avant de conclure qu'une version plus récente n'existe pas.
- `npm audit` peut proposer un correctif non actionnable sans `--force` (qui rétrograderait un paquet) — documenter plutôt que forcer, sauf si `npm audit fix` (sans `--force`) résout proprement (déjà arrivé, v2.7.9). La chaîne `vite-plugin-pwa`/`workbox-build` n'existe plus (`@unplugin-pwa/vite`, 0.x : relire son changelog à chaque montée).
- Montée majeure de Spring Boot : vérifier les packages déplacés (ex. `Health`/`HealthIndicator` → `org.springframework.boot.health.contributor` depuis Spring Boot 4).
- Montée majeure de `flyway` : toujours vérifier le changelog de la version majeure pour les breaking changes avant d'appliquer (déjà fait pour 12→13, aucun breaking change identifié).

## 11. Ne jamais

- Bumper `<revision>` (version applicative) sans demande explicite.
- Créer un ticket contenant une piste d'implémentation.
- Appliquer une mise à jour majeure/breaking sans avoir posé la question de décision dans le tour où elle est identifiée.
- Rétrograder ou remplacer une dépendance vétuste par une alternative de son propre chef.
- Mentionner une nouveauté/optimisation dans le rapport sans la poser comme point de décision explicite (étape 8) — une mention seule se perd et n'est jamais suivie d'effet.
- Grouper plusieurs bumps de dépendances dans un seul commit sans que chacune ait individuellement traversé l'étape 5 (changelog) — cf. `feedback_dependances_versions_changelogs`.
