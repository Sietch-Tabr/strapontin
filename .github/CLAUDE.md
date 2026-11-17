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
- Maven : 6 essais de téléchargement avec un délai plafonné à 60 s (`MAVEN_ARGS`), parce que Maven Central répond parfois 429 aux runners (3 essais par défaut, sans cache nous retéléchargeons tout à chaque job).
