Vérifie l'état de la dernière pipeline Woodpecker sur la branche main et affiche les logs en cas d'échec.

## Outil et accès

Tout passe par `woodpecker-cli` (client officiel de Woodpecker, installé sur le Mac par Homebrew, version 3.20.1 au 09/11/2026), qui interroge directement le serveur CI : aucun accès au LXC de CI n'est nécessaire. Dépôt : `Kwaite/strapontin` (ID 4).

**Authentification : rien à passer.** La CLI lit le contexte enregistré par `woodpecker-cli setup` (serveur et token, fichier `~/Library/Application Support/woodpecker/contexts.json`) : toutes les commandes ci-dessous s'écrivent sans `--server`, sans `--token` et sans variable d'environnement. Vérifié le 09/11/2026 en retirant `WOODPECKER_SERVER` et `WOODPECKER_TOKEN` de l'environnement.

- Jamais de `--token` dans une commande : si elle échoue, le message d'erreur du shell recopie la ligne entière, donc le token (constaté le 09/11/2026).
- Contexte absent (autre poste, fichier supprimé) : lancer `woodpecker-cli setup` (ouvre l'interface Woodpecker dans le navigateur pour valider la connexion), ou, à défaut, exporter `WOODPECKER_SERVER` (voir `CLAUDE.local.md`, section Woodpecker CI) et `WOODPECKER_TOKEN` (trousseau : `security find-generic-password -s woodpecker-strapontin -w`).
- `woodpecker-cli context list` affiche les contextes enregistrés.

## Procédure

1. Récupérer le dernier pipeline sur main :

   ```bash
   woodpecker-cli pipeline last Kwaite/strapontin --branch main
   ```

   Afficher : numéro, statut, événement, message du commit, auteur. Le tableau ne donne pas la date ; `woodpecker-cli pipeline ls Kwaite/strapontin --branch main --limit 5` donne l'historique récent.

2. Si le statut est déjà terminal (`success`, `failure`, `error`, `killed`) : passer directement à l'étape 4, sans attendre.

3. Si le statut est `pending` ou `running` : lancer l'attente en arrière-plan (jamais de `sleep` en foreground, bloqué dans ce harness) via l'outil Bash avec `run_in_background: true`. Lire le statut **global** du pipeline (modèle `{{.Status}}` ci-dessous), pas celui d'une étape : `pipeline ps` liste un statut par étape (`clone` peut être `success` pendant que `test` tourne encore).

   ```bash
   while true; do
     statut=$(woodpecker-cli pipeline show Kwaite/strapontin <N> \
       --output 'go-template={{range .}}{{.Status}}{{end}}' 2>/dev/null)
     case "$statut" in pending|running|"") sleep 15 ;; *) break ;; esac
   done
   echo "$statut"
   ```

   `<N>` est le numéro lu à l'étape 1. La boucle attend aussi bien `pending` (en file) que `running`, et un statut vide (erreur réseau passagère). Une seule notification arrive quand le pipeline quitte l'état attendu (timeout par défaut 5 min, à ajuster si le pipeline est historiquement plus long). Une fois notifié, refaire l'étape 1 pour lire le statut final.

4. Si le statut final est `success` : signaler OK et s'arrêter, sauf demande de vérification des avertissements (voir l'étape 5). Sinon (`failure`, `error`) : lister les étapes et lire les logs.

   ```bash
   woodpecker-cli pipeline ps Kwaite/strapontin <N>
   woodpecker-cli pipeline log show Kwaite/strapontin <N> <étape> 2>&1 | grep -iE 'warn|error|exception|failed' | head -50
   ```

   Étapes de la pipeline : `clone`, `test`, `test-front`, `build-front`, `lint-front`, `static-analysis`, `inventaire-dependances`, `analyse-dependances`, `build`, `backup`, `deploy`. Sans nom d'étape, `pipeline log show` imprime le journal de toutes les étapes (plusieurs milliers de lignes) : toujours cibler l'étape, ou filtrer avec `grep`.

5. Vérification des avertissements (clôture de jalon, `success` ne suffit pas) : lire le journal de **chaque** étape, pas seulement celles en échec. Les WARN/ERROR dans les tests unitaires (fixtures, cas simulés) sont normaux : vérifier que `Tests run: X, Failures: 0, Errors: 0` pour chaque module et qu'aucune exception n'apparaît hors des tests.

   ```bash
   woodpecker-cli pipeline log show Kwaite/strapontin <N> test 2>&1 | grep -E 'Tests run:.*Fail'
   ```

## Aide-mémoire

| Besoin                    | Commande                                                                                   |
| ------------------------- | ------------------------------------------------------------------------------------------ |
| Dernier pipeline de main  | `pipeline last Kwaite/strapontin --branch main`                                            |
| Historique                | `pipeline ls Kwaite/strapontin --branch main --limit 5`                                    |
| Statut seul d'un pipeline | `pipeline show Kwaite/strapontin <N> --output 'go-template={{range .}}{{.Status}}{{end}}'` |
| Étapes et leur état       | `pipeline ps Kwaite/strapontin <N>`                                                        |
| Journal d'une étape       | `pipeline log show Kwaite/strapontin <N> <étape>`                                          |
| Pipelines en file         | `pipeline queue`                                                                           |

`--output json` n'est pas pris en compte par `pipeline show` sur la version testée (il affiche le tableau) : utiliser `go-template`, avec `{{range .}}` (le résultat est une liste, même pour un seul pipeline).

Documentation officielle : <https://woodpecker-ci.org/docs/cli/reference>.
