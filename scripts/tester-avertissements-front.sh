#!/bin/sh
# Essais du contrôle d'avertissements du front : formats locaux et CI, écarts dans les deux sens.
set -u

racine="$(cd "$(dirname "$0")/.." && pwd)"
controle="$racine/scripts/avertissements-front.sh"
echecs=0

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

attendre "format local accepté" 0 sh "$controle" build sh -c \
  'echo " WARN  inlineDynamicImports option is deprecated, please use codeSplitting: false instead."'
attendre "format CI avec accents graves accepté" 0 sh "$controle" build sh -c \
  'echo "[warn] \`inlineDynamicImports\` option is deprecated, please use \`codeSplitting: false\` instead."'
attendre "avertissement non déclaré refusé" 1 sh "$controle" build sh -c \
  'echo " WARN  inlineDynamicImports option is deprecated"; echo "[warn] autre chose est deprecated"'
attendre "avertissement déclaré disparu refusé" 1 sh "$controle" build sh -c 'echo ok'
attendre "échec de la commande propagé" 3 sh "$controle" build sh -c 'exit 3'

exit "$echecs"
