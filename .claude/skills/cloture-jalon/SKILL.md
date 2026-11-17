---
name: Clôture de jalon
description: >
  Merge la PR d'un jalon puis le clôt : conflits vérifiés, CI GitHub verte et journaux scannés,
  déploiement de la prod et vérification (journaux web et worker, endpoint protégé), tag et release,
  fermeture du jalon si tous ses tickets sont fermés, nettoyage de la branche, wiki et mémoire.
disable-model-invocation: true
---

Invoqué par l'utilisateur, ce skill vaut demande explicite de merge, de déploiement de la prod, de tag, de release et de fermeture du jalon. Chaque étape ne démarre qu'une fois la précédente confirmée. Un échec arrête tout : le signaler et attendre les instructions, sans contourner.

Accès aux conteneurs : `.claude/scripts/acces.local.sh` (voir `acces.exemple.sh`), noté `acces`. Toutes les commandes `gh` se lancent depuis la racine du dépôt.

## 1. Avant le merge

- PR du jalon : `gh pr list --state open --base main --json number,title,headRefName` (branche du jalon vers `main`).
- Absence de conflit :
  ```bash
  gh pr view <n> --json state,mergeable,headRefName,baseRefName,headRefOid
  ```
  `mergeable` doit valoir `MERGEABLE`. `CONFLICTING` : s'arrêter. `UNKNOWN` : GitHub calcule encore, relire après une pause, jamais de merge sur `UNKNOWN`.
- Tous les tickets du jalon sont dans le corps de la PR, mot-clé répété devant chacun (`Closes #A, closes #B`, actif parce que la PR vise `main`). Liste du jalon :
  ```bash
  gh issue list --milestone "<jalon>" --state all --limit 1000 --json number --jq '[.[].number]|sort'
  ```
  Un ticket absent de la PR : le signaler avant de merger.
- État local propre : `git status --short` vide, `git fetch origin`.

## 2. Merge

```bash
gh pr merge <n> --merge --match-head-commit <headRefOid lu à l'étape 1>
```

`--match-head-commit` refuse le merge si la branche a bougé depuis la vérification. Puis se placer sur `main`, `git pull`, et vérifier que `git rev-parse HEAD` égale `git rev-parse origin/main` et le `mergeCommit` de la PR (`gh pr view <n> --json mergeCommit --jq .mergeCommit.oid`). Les tickets du corps doivent être fermés : `gh issue list --milestone "<jalon>" --state open`. Ne jamais se fier au seul affichage d'un checkout ou d'un pull.

## 3. CI

Lancer `/check-pipeline` sur le commit de fusion et attendre son statut final. Un `success` ne suffit pas : scanner le journal complet de chaque job (avertissements et erreurs hors fixtures de test, résumé Maven de chaque module, aucun test ignoré inattendu, `secrets` et `workflows` sans remarque).

CI en échec ou anomalie dans le journal : **s'arrêter**. Ni déploiement, ni tag, ni release, ni suite.

## 4. Déploiement et prod

La CI ne déploie rien. Appliquer les étapes de `/deploiement-prod` pour la prod (JARs attestés du run vert de ce commit, sauvegardes, livraison, `update.sh`), puis contrôler :

- Journaux web **et** worker complets depuis le redémarrage, pas les 30 premières lignes :
  ```bash
  acces prod exec "journalctl -u strapontin-web -u strapontin-worker --since '<heure du déploiement>' --no-pager"
  ```
  Classer chaque avertissement et chaque erreur ; aucune erreur au démarrage.
- Endpoint protégé sans authentification, 401 ou 403 attendu (une régression de configuration Spring Security peut lever une protection sans aucun log) :
  ```bash
  curl -s --max-time 20 -o /dev/null -w '%{http_code}\n' https://strapontin.kwaite.fr/api/admin/jobs
  ```
- JARs réellement déployés : `acces prod exec "ls -la --time-style=full-iso /opt/strapontin/application/"`, dates de `web.jar` et `worker.jar` égales à celle du déploiement, et SHA-256 identiques à ceux de l'artefact attesté.
- Migrations Flyway appliquées sans échec (`flyway_schema_history`, aucune ligne `success = false`).
- Contrôle d'intégrité AIDE : aucune ligne `error` « contrôle d'intégrité — modification détectée » dans `logs_admin` (colonne `ts`) depuis le déploiement.

Anomalie : s'arrêter et la signaler.

## 5. Tag et release

Seulement après les étapes 3 et 4 confirmées.

```bash
git tag -a v<version> -m "v<version>" && git push origin v<version>
git rev-parse v<version>^{commit}   # doit égaler origin/main
```

GitHub ne crée aucune release au push d'un tag : la créer directement.

Les notes s'adressent aux utilisateurs et aux admins : reprendre l'entrée de la version dans `front/src/pages/changelogVersions.js` (changements visibles, regroupés par thème, formulés pour eux), sans numéros de ticket ou de PR ni jargon interne. Un lien vers la PR ou la roadmap ne remplace pas ce contenu.

```bash
gh release create v<version> --verify-tag --title "v<version>" --notes-file <notes.md>
```

Vérifier ensuite qu'il n'existe qu'une release pour le tag et que son corps n'est pas vide : `gh release view v<version> --json tagName,body --jq '[.tagName,(.body|length)]'`. Une erreur de `gh` n'est jamais une conclusion : relire l'état avant de relancer. Une release transitoire du dev se marque `--prerelease` et se supprime à la validation de la prod (`gh release delete <tag> --yes`, sans `--cleanup-tag`).

## 6. Jalon

Aucune commande `gh` pour les jalons : passer par l'API. Compter les tickets encore ouverts :

```bash
gh issue list --milestone "<jalon>" --state open --limit 1000 --json number,title
```

- Aucun : fermer le jalon.
  ```bash
  numero=$(gh api -X GET repos/{owner}/{repo}/milestones -f state=all -f per_page=100 --jq '.[]|select(.title=="<jalon>")|.number')
  gh api -X PATCH repos/{owner}/{repo}/milestones/$numero -f state=closed
  ```
- Sinon : signaler l'écart et attendre les instructions, ne pas fermer le jalon.

## 7. Nettoyage

Une fois les étapes précédentes confirmées :

```bash
git checkout main && git pull && git fetch --prune origin
git branch --show-current          # doit afficher main
git branch -d <branche> && git push origin --delete <branche>
```

Puis toutes les autres branches déjà mergées, même créées par une autre session : `git branch -r --merged origin/main` liste les candidates ; chacune se confirme par sa PR (`gh pr list --state merged --limit 100 --json headRefName,number`) avant `git branch -d <branche> && git push origin --delete <branche>`. Une branche non mergée ne se supprime jamais sans demande. À la fin, `git branch -a` ne doit plus montrer que `main`.

## 8. Wiki et mémoire

- Wiki, page Roadmap : marquer le jalon livré et dire ce qu'il apporte, en langage clair pour un utilisateur ou un admin. Pas de numéros de PR, de run ni de détail d'incident.
- Wiki, toutes les autres pages : relire chaque page listée à l'étape 2 de `/pre-pr` contre le code réel (en-têtes de sécurité, tableau des jobs de la CI, journal et alertes de l'administration, limites, ADR pour un choix d'outil ou d'architecture) et corriger ce que le jalon a rendu faux ou incomplet. Chaque fait ajouté est relu dans le code avant d'être écrit, puis `scripts/verifier-doc.sh` doit passer sans `[ERREUR]`. Ne pas attendre un rappel : la Roadmap seule ne vaut pas mise à jour du wiki.
- Commit et push dans le dépôt du wiki (`git push github main:master` depuis le clone local), puis contrôle : `git ls-remote github refs/heads/master` doit donner le même hash que `git rev-parse HEAD`. Sinon, le signaler à l'utilisateur sans conclure la clôture.
- Mémoire, dans le même geste que le wiki : merge, numéro et conclusion du run de CI, incidents éventuels et suite donnée, retours de l'utilisateur de la session en feedback. Un incident de déploiement donne lieu à un ticket (`/ticket`).

## 9. Fin

Résumer : PR mergée, CI, état de la prod, tag et release, jalon, éléments en attente. Puis fin de session.
