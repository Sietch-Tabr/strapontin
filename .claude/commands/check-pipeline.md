Vérifie l'état de la dernière exécution de la CI GitHub sur la branche main et affiche les journaux en cas d'échec.

## Outil et accès

Tout passe par `gh`, déjà authentifié : aucun jeton à passer, aucun contexte à enregistrer, aucun accès à un serveur de CI. Les commandes se lancent depuis la racine du dépôt (hors d'un dépôt git, `gh` ne trouve ni le dépôt ni l'exécution). Jamais de jeton dans une commande.

## Procédure

1. Dernière exécution sur main :

   ```bash
   gh run list --branch main --limit 5 --json databaseId,status,conclusion,headSha,displayTitle,createdAt
   ```

   Afficher : numéro, statut, conclusion, titre, date. L'exécution « Dependabot Updates » est une autre exécution, sans rapport avec le commit : l'ignorer (`--workflow ci` la filtre).

2. Statut `completed` : passer directement à l'étape 4, sans attendre.

3. Sinon (`queued` ou `in_progress`) : attendre en arrière-plan (jamais de `sleep` en foreground, bloqué dans ce harness) via l'outil Bash avec `run_in_background: true` :

   ```bash
   gh run watch <id> --exit-status --interval 15
   ```

   Une seule notification arrive à la fin ; relire ensuite l'état par `gh run view <id> --json status,conclusion` (le code de sortie ne remplace pas la relecture).

4. Conclusion `success` : signaler OK et s'arrêter, sauf demande de vérification des avertissements (étape 5). Sinon : lister les jobs et lire les journaux.

   ```bash
   gh run view <id> --json jobs --jq '.jobs[]|[.name,.conclusion]|@tsv'
   gh run view <id> --log-failed | grep -iE 'warn|error|exception|failed' | head -50
   ```

   Jobs du workflow : `tests-java`, `analyse-statique`, `front`, `dependances`, `secrets`, `workflows`, `construction`. Sans filtre, `--log` imprime des milliers de lignes : toujours cibler (`--job <idJob>`) ou filtrer avec `grep`.

5. Vérification des avertissements (clôture de jalon, `success` ne suffit pas) : lire le journal de **chaque** job. Les WARN et ERROR dans les tests unitaires (fixtures, cas simulés) sont normaux : vérifier `Tests run: X, Failures: 0, Errors: 0` pour chaque module et qu'aucune exception n'apparaît hors des tests.

   ```bash
   gh run view <id> --log > <fichier du scratchpad>
   grep -E 'Tests run:.*Fail' <fichier> | grep -v 'Time elapsed'
   ```

6. Provenance des JARs (avant un déploiement) : voir `/deploiement-prod`.

## Aide-mémoire

| Besoin                    | Commande                                                              |
| ------------------------- | --------------------------------------------------------------------- |
| Dernière exécution de ci  | `gh run list --workflow ci --branch main --limit 1`                   |
| Statut seul               | `gh run view <id> --json status,conclusion`                           |
| Jobs et leur conclusion   | `gh run view <id> --json jobs`                                        |
| Journal d'un job          | `gh run view <id> --log --job <idJob>`                                |
| Attendre la fin           | `gh run watch <id> --exit-status`                                     |
| Artefacts                 | `gh run download <id> -n jars -D <dossier vide>`                      |

Documentation : <https://cli.github.com/manual/gh_run>.
