---
name: Déploiement en production
description: >
  Déploie sur le LXC dev ou prod les JARs produits et attestés par la CI GitHub pour un commit de main :
  run vert, provenance vérifiée, sauvegardes, livraison des scripts, update.sh, contrôles. Remplace les
  étapes backup et deploy de l'ancienne pipeline.
disable-model-invocation: true
---

Invoqué par l'utilisateur, ce skill vaut demande explicite de déploiement sur la cible nommée (`dev` ou `prod`, `prod` si rien n'est précisé). Chaque étape ne démarre qu'une fois la précédente confirmée. Un échec arrête tout : le signaler et attendre les instructions, sans contourner. Jamais de JAR construit sur le Mac pour la prod ; les tests locaux du dev passent par `/deploy-dev`.

Accès aux conteneurs : `.claude/scripts/acces.local.sh` (voir `acces.exemple.sh`), noté `acces`. Dépôt : `DEPOT=$(gh repo view --json nameWithOwner --jq .nameWithOwner)`.

Hôtes : dev `192.168.20.78` (LXC 114, arrêté par défaut : `acces hote exec "pct start 114"` puis attendre le démarrage), prod `192.168.20.79`. `HOST=root@<ip>`.

## 1. Commit et run

- Dépôt propre sur `main` à jour : `git status --short` vide, `git fetch origin`, `git rev-parse HEAD` égal à `git rev-parse origin/main`. Les scripts livrés viennent de ce dépôt local : il doit être exactement au commit déployé.
- Run de ce commit, terminé et vert :
  ```bash
  gh run list --branch main --commit "$(git rev-parse HEAD)" --json databaseId,status,conclusion
  ```
  Sans run `completed` avec `success` : s'arrêter (ni attente active, ni reconstruction locale).

## 2. JARs et provenance

Dans un dossier vide du scratchpad (les artefacts expirent au bout d'un jour) :

```bash
gh run download <id> -n jars -D <dossier>
for j in web worker; do
  gh attestation verify <dossier>/$j.jar --repo "$DEPOT" \
    --signer-workflow "$DEPOT/.github/workflows/ci.yml" --format json \
    --jq '.[0].verificationResult.signature.certificate.sourceRepositoryDigest'
done
```

Chaque commande doit sortir avec le code 0 et afficher le SHA de `git rev-parse HEAD`. Sinon : ne rien livrer.

## 3. Sauvegardes (prod)

```bash
ssh "$HOST" bash /opt/strapontin/exploitation/backup-local.sh
ssh "$HOST" /opt/strapontin/exploitation/backup.sh || echo "sauvegarde NAS ignorée (NAS non monté)"
```

Sur le dev : sauvegarde locale seulement, jamais de sauvegarde NAS.

## 4. Livraison

```bash
SSH=ssh SCP=scp HOST=root@<ip>
. scripts/deposer-exploitation.sh && deposer_exploitation
scp <dossier>/web.jar    "$HOST":/opt/strapontin/application/web.jar.new
scp <dossier>/worker.jar "$HOST":/opt/strapontin/application/worker.jar.new
ssh "$HOST" /opt/strapontin/exploitation/update.sh
```

`deposer_exploitation` compare la livraison au manifeste du dépôt ; `update.sh` aligne unités et AIDE, remplace atomiquement, garde `.prev` et revient en arrière seul en cas d'échec.

## 5. Contrôles

Ceux de l'étape 4 de `/cloture-jalon` (journaux web et worker depuis le redémarrage, 401 ou 403 sur `/api/admin/jobs`, dates des JARs, Flyway, AIDE). Sur le dev, remettre le LXC à l'arrêt en fin de session sauf demande contraire (`acces hote exec "pct stop 114"`).

## Ne jamais

- Déployer un commit sans run vert, ni sans attestation vérifiée pour ce commit.
- Déployer en prod un JAR qui ne vient pas de l'artefact `jars` du run.
- Lancer une sauvegarde NAS depuis le dev.
