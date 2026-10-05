Vérifie l'état de la dernière pipeline Woodpecker sur la branche main et affiche les logs en cas d'échec.

L'URL du serveur et la commande d'obtention du token API sont dans `CLAUDE.local.md` (section Woodpecker CI).
Repo strapontin : ID 4 dans Woodpecker.

1. Récupérer le dernier pipeline sur main :

   ```bash
   curl -s -H "Authorization: Bearer <TOKEN>" \
     "http://192.168.20.88:8000/api/repos/4/pipelines?branch=main&limit=1"
   ```

   Afficher : numéro, statut, commit, message, date.

2. Si le statut est déjà terminal (`success`, `failure`, `error`, `killed`) → passer directement à l'étape 3/4 selon le cas, sans attendre.

3. Si le statut est `pending` ou `running` : lancer l'attente en arrière-plan (jamais de `sleep` en foreground, bloqué dans ce harness) via l'outil Bash avec `run_in_background: true`. ⚠️ Interroger l'endpoint **détail** `/pipelines/<N>` (avec le numéro `<N>` récupéré à l'étape 1), jamais la **liste** `?branch=main&limit=1` — la liste imbrique un champ `"status"` par step/workflow en plus du statut global du pipeline, un `grep` non ciblé matche sur le premier step terminé (ex. `clone`) alors que le pipeline entier tourne encore (constaté : `test` encore en cours pendant que le grep se satisfaisait du `success` de `clone`) :

   ```bash
   until [ "$(curl -s -H "Authorization: Bearer <TOKEN>" \
     "http://192.168.20.88:8000/api/repos/4/pipelines/<N>" \
     | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')" != "running" ] 2>/dev/null; do
     sleep 15
   done
   ```

   Une seule notification arrive quand le pipeline atteint un statut terminal (timeout par défaut 5 min, ajuster si le pipeline est historiquement plus long). Une fois notifié, refaire l'appel de l'étape 1 pour lire le statut final.

4. Si le statut final est `success` → signaler OK et s'arrêter. Sinon, pour lire les logs (statut `failure`, `error`, ou vérification de warnings) :
   woodpecker-cli est installé sur le LXC de CI et fonctionne en localhost :
   ```bash
   TOKEN="<TOKEN>"
   .claude/scripts/acces.local.sh ci exec "/usr/local/bin/woodpecker-cli \
     --server http://localhost:8000 --token $TOKEN \
     pipeline log show Kwaite/strapontin <N> 2>&1 | grep -iE 'warn|error|exception|failed' | head -50"
   ```
   Les WARN/ERROR dans les tests unitaires (fixtures, cas simulés) sont normaux — vérifier
   que `Tests run: X, Failures: 0, Errors: 0` pour chaque module et qu'aucune exception
   n'apparaît hors des tests.
