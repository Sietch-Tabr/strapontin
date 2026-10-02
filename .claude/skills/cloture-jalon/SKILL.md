---
name: Clôture de jalon
description: >
  Clôt un jalon après le merge de sa PR : pipeline Woodpecker verte et journaux scannés,
  vérification de la prod (journaux web et worker complets, endpoints protégés, AIDE),
  tickets du jalon fermés, puis, sur demande explicite, tag, release lisible, fermeture du
  jalon et nettoyage des branches.
when_to_use: >
  Quand l'utilisateur annonce ou demande le merge de la PR d'un jalon, ou demande de
  clôturer un jalon, de taguer ou de publier une release.
---

Le push sur `main` déploie la prod. Tag, release et fermeture du jalon sont publics et ne se défont pas proprement : chacun attend une demande explicite de l'utilisateur, même quand tout est vert.

## 1. Merge

Seulement sur confirmation explicite. Vérifier d'abord l'état local :

```bash
git fetch origin
git branch --show-current
git status --short
```

Après le merge : se placer sur `main`, puis contrôler `git rev-parse HEAD` contre `git rev-parse origin/main`. Ne jamais enchaîner `checkout && pull && <action>` sans ce contrôle : un checkout raté laisse la branche précédente active.

## 2. Pipeline

Lancer `/check-pipeline` dès le merge. Un statut `success` ne suffit pas : scanner le journal complet de chaque étape (avertissements et erreurs hors fixtures de test, résumé Maven de chaque module). Toute anomalie se diagnostique avant la suite, et n'est jamais présumée préexistante.

## 3. Prod

Avec `.claude/scripts/acces.local.sh` (voir `acces.exemple.sh`) :

- Journaux web **et** worker complets depuis le redémarrage du déploiement, pas les 30 dernières lignes : `acces.local.sh prod exec "journalctl -u strapontin-web -u strapontin-worker --since '<heure du déploiement>' --no-pager"`. Classer chaque avertissement et chaque erreur.
- JAR réellement déployés : `acces.local.sh prod exec "ls -la --time-style=full-iso /opt/strapontin/"`, date de `web.jar`/`worker.jar` égale à celle du déploiement, `.jar.prev` conservés.
- Endpoint protégé sans session : 401 attendu (`curl -s -o /dev/null -w '%{http_code}' https://strapontin.kwaite.fr/api/admin/jobs`).
- Contrôle d'intégrité AIDE : dernier message « contrôle d'intégrité » dans `logs_admin`, aucune « modification détectée » liée au déploiement.
- Migrations Flyway appliquées sans échec (`flyway_schema_history`, `success = false` absent).

## 4. Tickets du jalon

Tous les tickets du jalon doivent être fermés par la PR (`Closes #A, closes #B` : mot-clé répété devant chaque numéro). Compter avec l'API, pas seulement `tea`, qui peut tronquer :

```bash
curl -s --max-time 20 "https://codeberg.org/api/v1/repos/Kwaite/strapontin/issues?milestones=<jalon>&state=open&limit=50" \
  | python3 -c "import json,sys; print([(i['number'], i['title']) for i in json.load(sys.stdin)])"
```

Un ticket resté ouvert : soit oublié dans la PR (le signaler), soit jamais traité (décision de l'utilisateur, jamais un report décidé seul).

## 5. Tag et release (sur demande explicite)

```bash
git tag -a v<version> -m "v<version>" && git push origin v<version>
git rev-parse v<version>^{commit}   # doit égaler origin/main
```

Les notes de release s'adressent aux utilisateurs et aux admins : reprendre l'entrée de la version dans `front/src/pages/changelogVersions.js` (changements visibles, regroupés par thème, formulés pour eux), sans numéros de tickets, de PR ni jargon interne. Un lien vers la PR ou la roadmap ne remplace pas ce contenu.

```bash
tea releases create -r Kwaite/strapontin --tag v<version> --title "v<version>" --note-file <notes.md>
```

Pas de release pour un correctif isolé sans changement notable, sauf demande.

## 6. Fermeture et nettoyage (sur demande explicite)

```bash
tea milestones close -r Kwaite/strapontin <jalon>
git branch -d <branche> && git push origin --delete <branche>
```

Puis mettre à jour la page Roadmap du wiki (jalon livré, date), commit et push dans le dépôt du wiki.
