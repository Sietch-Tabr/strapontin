#!/usr/bin/env bash
set -euo pipefail

# analyse-statique.sh — l'analyse statique bloquante de la pipeline, en local : Checkstyle, PMD et
# Spotless sur les quatre modules Java, puis ESLint et stylelint sur le front. S'arrête à la première
# violation (code de sortie non nul). Les mêmes commandes que la CI : un résultat propre ici l'est
# aussi sur `main`.
# Usage, depuis n'importe où : bash scripts/analyse-statique.sh

racine="$(cd "$(dirname "$0")/.." && pwd)"

echo "== Java : Checkstyle, PMD, Spotless"
PMD_JAVA_DISABLE_AUX_CLASSPATH_WARNINGS=true bash "$racine/scripts/mvn-reprise.sh" -f "$racine/java/pom.xml" \
  -pl core,sources,web,worker,exploitation-commun,mise-a-jour -am compile checkstyle:check pmd:check pmd:cpd-check spotless:check -DskipTests

echo "== Front : ESLint et stylelint"
(cd "$racine/front" && npm run lint && npm run lint:css)

echo "analyse statique : aucune violation"
