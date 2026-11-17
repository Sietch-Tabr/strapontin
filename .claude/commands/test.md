Lance toute la suite de tests du projet et résume les résultats. Un run n'est « propre » qu'avec zéro échec en une seule exécution complète (un test passé au retry reste à diagnostiquer).

1. **Tests Java** (Docker requis, Testcontainers), depuis la racine du dépôt. Sous macOS, `caffeinate -i` évite qu'une mise en veille fausse les tests sensibles à l'horloge :

   ```
   caffeinate -i mvn -f java/pom.xml clean test -pl core,sources,web,worker,exploitation-commun,mise-a-jour,exploitation-controle -am
   ```

   En cas d'échec : afficher les tests en erreur (`java/*/target/surefire-reports/*.txt`), ne pas continuer.

2. **Tests unitaires front** (Vitest) :

   ```
   cd front && npm run test:unit
   ```

3. **Analyse statique** (bloquante en CI) :

   ```
   bash scripts/analyse-statique.sh
   ```

4. **Tests E2E Playwright** (contre le LXC dev) :
   - Prérequis : le dev répond (`bash scripts/dev.sh etat`). Sinon le signaler et proposer `bash scripts/dev.sh demarrer` puis `/deploy-dev`.
   - Toujours les 5 navigateurs, jamais `--project` ; le script arrête les `playwright-mcp` et rapporte les chiffres exacts :
     ```
     bash e2e/lancer-e2e.sh
     ```

5. **Résumé final**, chiffres exacts par suite :
   - Java : tests exécutés, échecs, erreurs, ignorés (par module)
   - Vitest : tests passés/échoués
   - Analyse statique : violations
   - E2E : passed, flaky, skipped, failed, avec le nom des tests en erreur
