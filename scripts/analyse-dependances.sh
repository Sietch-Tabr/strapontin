#!/bin/bash
set -euo pipefail

# Analyse des vulnérabilités connues (base OSV) des dépendances Maven et npm.
# Usage : analyse-dependances.sh sbom|local
#   sbom  : génère l'inventaire CycloneDX des modules Java (étape CI, image Maven)
#   local : sbom puis analyse de l'inventaire et de package-lock.json via Docker
# L'analyse en CI est lancée directement par .woodpecker/ci.yaml (l'image osv-scanner n'a pas
# de bash). Une exception justifiée se déclare dans osv-scanner.toml (IgnoredVulns, ignoreUntil).

RACINE=$(cd "$(dirname "$0")/.." && pwd)
IMAGE_SCANNER="ghcr.io/google/osv-scanner:v2.7.0"
VERSION_CYCLONEDX="2.9.3"

generer_inventaire() {
  # Dépendances de test exclues : seules celles livrées en production comptent.
  mvn -B -q -f "$RACINE/java/pom.xml" \
    "org.cyclonedx:cyclonedx-maven-plugin:${VERSION_CYCLONEDX}:makeAggregateBom" \
    -DoutputFormat=json -DoutputName=bom -DincludeTestScope=false
}

case "${1:-}" in
  sbom)
    generer_inventaire
    ;;
  local)
    generer_inventaire
    docker run --rm -v "$RACINE":/src --entrypoint /osv-scanner "$IMAGE_SCANNER" \
      scan source --config=/src/osv-scanner.toml \
      -L /src/java/target/bom.json -L /src/front/package-lock.json
    ;;
  *)
    echo "Usage : $0 sbom|local" >&2
    exit 2
    ;;
esac
