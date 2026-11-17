#!/bin/bash
set -euo pipefail

# Analyse des vulnérabilités connues (base OSV) des dépendances Maven et npm.
# Usage : analyse-dependances.sh sbom|local
#   sbom  : génère l'inventaire CycloneDX des modules Java (étape CI, image Maven)
#   local : sbom puis analyse de l'inventaire et des package-lock.json (front et e2e) via Docker
# L'analyse en CI (job dependances du workflow) lance « local » : Docker est préinstallé sur le
# runner. Une exception justifiée se déclare dans osv-scanner.toml (IgnoredVulns, effectiveUntil).

RACINE=$(cd "$(dirname "$0")/.." && pwd)
# osv-scanner v2.7.1, image désignée par son condensat.
IMAGE_SCANNER="ghcr.io/google/osv-scanner@sha256:a70ffffc2bfd33482953e5d6b64c6fcdb30239717a07b0f0d3ab2859ce3e4cde"
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
      -L /src/java/target/bom.json -L /src/front/package-lock.json -L /src/e2e/package-lock.json
    ;;
  *)
    echo "Usage : $0 sbom|local" >&2
    exit 2
    ;;
esac
