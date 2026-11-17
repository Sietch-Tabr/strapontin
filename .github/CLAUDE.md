# CI GitHub Actions et dépôt

`ci.yml` tourne à chaque push sur `main` ou sur un tag `v*`, jamais avant le merge : rejouer en local les commandes des jobs avant la PR. Aucun déploiement sans run vert : `/deploiement-prod` livre les JARs attestés (dev et prod). Règle du dépôt : push forcé et suppression de `main` interdits.

## Jobs

`tests-java`, `analyse-statique`, `front`, `dependances` (OSV), `secrets` (gitleaks), `workflows` (actionlint, zizmor), `construction` (JARs et archive d'exploitation, attestés), `publication` (release, sur tag seulement : le commit est dans `main`, l'étiquette est `v<revision>` ou `v<revision>-suffixe`, notes tirées du changelog, brouillon puis publication, pré-release si suffixe).

## Règles

- Actions épinglées par SHA, images d'outils par condensat, jeton en lecture seule sauf `construction` (attestation) et `publication` (release).
- Aucun cache de dépendances : le workflow publie des releases (audit zizmor, cache-poisoning).
- Artefacts à un jour ; aucun secret dans la CI.
- Runners GitHub (16 Go, Docker préinstallé) ; chaque job tourne sur une machine neuve, le build du front passe à `construction` par un artefact.
- Dependabot : écosystème `github-actions` seulement.
- Maven : 6 essais de téléchargement avec un délai plafonné à 60 s (`MAVEN_ARGS`), puis `scripts/mvn-reprise.sh` (3 essais espacés de 2 min, seulement si le journal contient `HTTP Status: 429` ; un autre échec n'est jamais rejoué) dans les jobs Maven et dans `analyse-dependances.sh` : Maven Central limite par adresse, les runners en partagent. Pas de cache de lecture : `zizmor` 1.32.0 le refuse dans un workflow de release (testé avec `cache-mode` `read`, `write`, conditions sur le tag), et `actionlint` 1.7.12 rejette `cache-mode`. Test : `scripts/tester-mvn-reprise.sh`.
