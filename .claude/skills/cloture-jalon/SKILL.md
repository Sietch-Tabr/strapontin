---
name: Clôture de jalon
description: >
  Merge la PR d'un jalon puis le clôt : conflits vérifiés, pipeline Woodpecker verte et
  journal scanné, prod vérifiée (journaux web et worker, endpoint protégé), tag et release,
  fermeture du jalon si tous ses tickets sont fermés, nettoyage de la branche, wiki et
  mémoire.
disable-model-invocation: true
---

Invoqué par l'utilisateur, ce skill vaut demande explicite de merge, de tag, de release et de fermeture du jalon. Chaque étape ne démarre qu'une fois la précédente confirmée. Un échec arrête tout : le signaler et attendre les instructions, sans contourner.

Accès aux conteneurs : `.claude/scripts/acces.local.sh` (voir `acces.exemple.sh`), noté `acces`.

## 1. Avant le merge

- PR du jalon : `tea pr -r Kwaite/strapontin` (ouverte, branche du jalon vers `main`).
- Absence de conflit :
  ```bash
  curl -s --max-time 20 "https://codeberg.org/api/v1/repos/Kwaite/strapontin/pulls/<n>" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['state'], d['mergeable'], d['head']['ref'], '->', d['base']['ref'])"
  ```
  `mergeable` doit valoir `True`. Sinon, s'arrêter.
- Tous les tickets du jalon sont dans le corps de la PR, mot-clé répété devant chacun (`Closes #A, closes #B`). Liste du jalon par l'API (tea peut tronquer un gros jalon) :
  ```bash
  curl -s --max-time 20 "https://codeberg.org/api/v1/repos/Kwaite/strapontin/issues?milestones=<jalon>&state=all&type=issues&limit=50" \
    | python3 -c "import json,sys; print(sorted(i['number'] for i in json.load(sys.stdin)))"
  ```
  Un ticket absent de la PR : le signaler avant de merger.
- État local propre : `git status --short` vide, `git fetch origin`.

## 2. Merge

```bash
tea pr merge -r Kwaite/strapontin <n>
```

Puis se placer sur `main` et vérifier `git rev-parse HEAD` égal à `git rev-parse origin/main` (après `git pull`), et égal au `merge_commit_sha` de la PR. Ne jamais se fier au seul affichage d'un checkout ou d'un pull.

## 3. Pipeline

Lancer `/check-pipeline` sur ce commit et attendre son statut final. Un `success` ne suffit pas : scanner le journal complet de chaque étape (avertissements et erreurs hors fixtures de test, résumé Maven de chaque module, aucun test ignoré inattendu).

Pipeline en échec ou anomalie dans le journal : **s'arrêter**. Ni tag, ni release, ni suite.

## 4. Prod

- Journaux web **et** worker complets depuis le redémarrage du déploiement, pas les 30 premières lignes :
  ```bash
  acces prod exec "journalctl -u strapontin-web -u strapontin-worker --since '<heure du déploiement>' --no-pager"
  ```
  Classer chaque avertissement et chaque erreur ; aucune erreur au démarrage.
- Endpoint protégé sans authentification, 401 ou 403 attendu (une régression de configuration Spring Security peut lever une protection sans aucun log) :
  ```bash
  curl -s --max-time 20 -o /dev/null -w '%{http_code}\n' https://strapontin.kwaite.fr/api/admin/jobs
  ```
- JAR réellement déployés : `acces prod exec "ls -la --time-style=full-iso /opt/strapontin/"`, date de `web.jar`/`worker.jar` égale à celle du déploiement.
- Migrations Flyway appliquées sans échec (`flyway_schema_history`, aucune ligne `success = false`).
- Contrôle d'intégrité AIDE : aucune « modification détectée » liée au déploiement dans `logs_admin`.

Anomalie : s'arrêter et la signaler.

## 5. Tag et release

Seulement après les étapes 3 et 4 confirmées.

```bash
git tag -a v<version> -m "v<version>" && git push origin v<version>
git rev-parse v<version>^{commit}   # doit égaler origin/main
```

Les notes s'adressent aux utilisateurs et aux admins : reprendre l'entrée de la version dans `front/src/pages/changelogVersions.js` (changements visibles, regroupés par thème, formulés pour eux), sans numéros de ticket ou de PR ni jargon interne. Un lien vers la PR ou la roadmap ne remplace pas ce contenu.

```bash
tea releases create -r Kwaite/strapontin --tag v<version> --title "v<version>" --note-file <notes.md>
```

## 6. Jalon

Compter les tickets encore ouverts du jalon par l'API :

```bash
curl -s --max-time 20 "https://codeberg.org/api/v1/repos/Kwaite/strapontin/issues?milestones=<jalon>&state=open&type=issues&limit=50" \
  | python3 -c "import json,sys; print([(i['number'], i['title']) for i in json.load(sys.stdin)])"
```

- Aucun : `tea milestones close -r Kwaite/strapontin <jalon>`.
- Sinon : signaler l'écart et attendre les instructions, ne pas fermer le jalon.

## 7. Nettoyage

Une fois les étapes précédentes confirmées :

```bash
git checkout main && git pull
git branch --show-current          # doit afficher main
git branch -d <branche> && git push origin --delete <branche>
```

## 8. Wiki et mémoire

- Wiki, page Roadmap : marquer le jalon livré et dire ce qu'il apporte, en langage clair pour un utilisateur ou un admin. Pas de numéros de PR, de pipeline ni de détail d'incident. Commit et push dans le dépôt du wiki, puis contrôle : `git ls-remote origin main` doit donner le même hash que `git rev-parse HEAD`. Sinon, le signaler à l'utilisateur sans conclure la clôture : `origin/main` local ne prouve pas que le serveur a reçu les commits.
- Mémoire : merge, numéro et statut de la pipeline, incidents éventuels et suite donnée. Un incident de déploiement donne lieu à un ticket (`/ticket`).

## 9. Fin

Résumer : PR mergée, pipeline, état de la prod, tag et release, jalon, éléments en attente. Puis fin de session.
