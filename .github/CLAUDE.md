# CI GitHub Actions et dépôt

`ci.yml` tourne à chaque push sur `main` ou sur un tag `v*`, jamais avant le merge : rejouer en local les commandes des jobs avant la PR. Aucun déploiement sans run vert : `/deploiement-prod` livre les JARs attestés (dev et prod). Règle du dépôt : push forcé et suppression de `main` interdits.

## Jobs

`tests-java`, `analyse-statique`, `front`, `dependances` (OSV), `secrets` (gitleaks), `construction` (JARs et archive d'exploitation, attestés), `publication` (release, sur tag seulement : le commit est dans `main`, l'étiquette est `v<revision>` ou `v<revision>-suffixe`, notes tirées du changelog, brouillon puis publication, pré-release si suffixe).

## Règles

- Actions épinglées par SHA, images d'outils par condensat, jeton en lecture seule sauf `construction` (attestation) et `publication` (release).
- Cache Maven (`actions/cache`, un jeu par job : clé `maven-<job>-<hachage des pom.xml>`, repli sur le dernier jeu du même job ; une clé commune ne laissait enregistrer que le premier job fini, mesuré au run 44083303273) dans `tests-java`, `analyse-statique` et `dependances` seulement : sans cache, chaque job Maven retéléchargeait environ 140 Mo (255 JARs, 465 POMs pour `tests-java`) et Maven Central répondait 429. `construction` et `publication` n'ont aucun cache : les JARs livrés et attestés viennent toujours de Maven Central. Le workflow ne se déclenche que sur push de `main` et de tags, aucune PR ni fork n'écrit dans le cache ; un tag lit le cache de `main`. Après un changement de `pom.xml`, les trois jobs repartent à vide une fois.
- Artefacts à un jour ; aucun secret dans la CI.
- Runners GitHub (16 Go, Docker préinstallé) ; chaque job tourne sur une machine neuve, le build du front passe à `construction` par un artefact.
- Dependabot : écosystème `github-actions` seulement.
- Maven : 6 essais de téléchargement avec un délai plafonné à 60 s (`MAVEN_ARGS`), parce que Maven Central répond parfois 429 aux runners (3 essais par défaut, sans cache nous retéléchargeons tout à chaque job).
- Contrôles de sécurité : ceux de GitHub (secret scanning avec push protection sur tout l'historique, CodeQL en configuration par défaut pour Actions, Java et JavaScript, alertes Dependabot) et deux outils tiers retenus parce qu'ils comblent un écart : OSV (vulnérabilités des dépendances) et gitleaks (secrets génériques : le secret scanning gratuit ne couvre que les fournisseurs connus, motifs génériques et contrôles de validité exigent GitHub Secret Protection, qui reste `disabled` ici ; gitleaks a trouvé un ancien mot de passe de test). actionlint et zizmor ont été retirés : actionlint (syntaxe) n'a pas d'équivalent, GitHub refuse lui-même un workflow invalide.
