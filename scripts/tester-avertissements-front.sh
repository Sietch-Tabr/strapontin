#!/bin/sh
# Essais du contrôle d'avertissements du front : formats locaux et CI, écarts dans les deux sens.
set -u

racine="$(cd "$(dirname "$0")/.." && pwd)"
controle="$racine/scripts/avertissements-front.sh"
echecs=0

# Listes d'essai : les essais ne dépendent pas du contenu de la vraie liste, vide ou non.
declaree="$(mktemp)"
videe="$(mktemp)"
trap 'rm -f "$declaree" "$videe"' EXIT
echo "build|inlineDynamicImports option is deprecated|cause d'essai|2026-01-01" >"$declaree"
echo "# liste sans avertissement déclaré" >"$videe"

attendre() {
  intitule="$1"
  code_attendu="$2"
  shift 2
  "$@" >/dev/null 2>&1
  code=$?
  if [ "$code" -eq "$code_attendu" ] || { [ "$code_attendu" -eq 1 ] && [ "$code" -ne 0 ]; }; then
    echo "ok   : $intitule"
  else
    echo "ECHEC: $intitule (code $code, attendu $code_attendu)"
    echecs=$((echecs + 1))
  fi
}

attendre "format local accepté" 0 env LISTE_AVERTISSEMENTS="$declaree" sh "$controle" build sh -c \
  'echo " WARN  inlineDynamicImports option is deprecated, please use codeSplitting: false instead."'
attendre "format CI avec accents graves accepté" 0 env LISTE_AVERTISSEMENTS="$declaree" sh "$controle" build sh -c \
  'echo "[warn] \`inlineDynamicImports\` option is deprecated, please use \`codeSplitting: false\` instead."'
attendre "avertissement non déclaré refusé" 1 env LISTE_AVERTISSEMENTS="$declaree" sh "$controle" build sh -c \
  'echo " WARN  inlineDynamicImports option is deprecated"; echo "[warn] autre chose est deprecated"'
attendre "avertissement déclaré disparu refusé" 1 env LISTE_AVERTISSEMENTS="$declaree" sh "$controle" build sh -c 'echo ok'
attendre "échec de la commande propagé" 3 env LISTE_AVERTISSEMENTS="$declaree" sh "$controle" build sh -c 'exit 3'
attendre "liste vide : aucun avertissement accepté" 0 env LISTE_AVERTISSEMENTS="$videe" sh "$controle" \
  build sh -c 'echo ok'
attendre "liste vide : un avertissement est refusé" 1 env LISTE_AVERTISSEMENTS="$videe" sh "$controle" \
  build sh -c 'echo " WARN  une option est deprecated"'

exit "$echecs"
