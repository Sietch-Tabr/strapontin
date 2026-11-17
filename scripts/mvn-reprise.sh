#!/usr/bin/env bash
# mvn-reprise.sh — lance mvn avec les arguments reçus et le relance seulement quand l'échec vient
# d'une limite de débit de Maven Central (HTTP 429 dans le journal) : les runners GitHub partagent
# leurs adresses et Maven Central les limite par adresse. Tout autre échec (compilation, test,
# analyse) est rendu tel quel, sans reprise, pour ne jamais masquer un défaut.
# Variables : ESSAIS_MAX (3 par défaut), DELAI_REPRISE en secondes (120 par défaut).
set -uo pipefail

essais_max="${ESSAIS_MAX:-3}"
delai="${DELAI_REPRISE:-120}"
journal="$(mktemp)"
trap 'rm -f "$journal"' EXIT

essai=1
while true; do
  # PIPESTATUS : le code de mvn, pas celui de tee.
  mvn "$@" 2>&1 | tee "$journal"
  code="${PIPESTATUS[0]}"
  [ "$code" -eq 0 ] && exit 0
  if ! grep -q "HTTP Status: 429" "$journal" || [ "$essai" -ge "$essais_max" ]; then
    exit "$code"
  fi
  echo "mvn-reprise : limite de débit de Maven Central (429), nouvel essai dans ${delai} s ($((essai + 1))/${essais_max})" >&2
  sleep "$delai"
  essai=$((essai + 1))
done
