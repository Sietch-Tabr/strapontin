Lance toute la suite de tests du projet et résume les résultats. Un run n'est « propre » qu'avec zéro échec en une seule exécution complète (un test passé au retry reste à diagnostiquer).

1. **Tests Java** (Docker requis, Testcontainers), depuis la racine du dépôt. Sous macOS, `caffeinate -i` évite qu'une mise en veille fausse les tests sensibles à l'horloge :

   ```
   caffeinate -i mvn -f java/pom.xml clean test -pl core,sources,web,worker -am
   ```

   En cas d'échec : afficher les tests en erreur (`java/*/target/surefire-reports/*.txt`), ne pas continuer.

2. **Tests unitaires front** (Vitest) :

   ```
   cd front && npm run test:unit
   ```

3. **Analyse statique** (bloquante en CI) :

   ```
   PMD_JAVA_DISABLE_AUX_CLASSPATH_WARNINGS=true mvn -f java/pom.xml -pl core,sources,web,worker -am compile checkstyle:check pmd:check spotless:check -DskipTests
   cd front && npm run lint && npm run lint:css
   ```

4. **Tests E2E Playwright** (contre le LXC dev) :
   - Prérequis : le dev répond (`curl -s --max-time 5 http://192.168.20.78:9090/actuator/health`, actuator web sur 9090, pas 8080). Sinon le signaler et proposer `/deploy-dev`.
   - Toujours les 5 navigateurs, jamais `--project` :
     ```
     cd e2e && npx playwright test 2>&1 | tail -30
     ```

5. **Résumé final**, chiffres exacts par suite :
   - Java : tests exécutés, échecs, erreurs, ignorés (par module)
   - Vitest : tests passés/échoués
   - Analyse statique : violations
   - E2E : passed, flaky, skipped, failed, avec le nom des tests en erreur
