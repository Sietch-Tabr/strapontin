#!/bin/sh
# Exécute une commande du front et compare ses avertissements à front/avertissements-attendus.txt.
# Usage : sh scripts/avertissements-front.sh <npm-ci|build> <commande...>
# Échoue si la commande échoue, si un avertissement n'est pas déclaré, ou si un avertissement
# déclaré a disparu (la ligne est alors à supprimer : plus de dette à tolérer).
set -u

portee="$1"
shift
liste="$(dirname "$0")/../front/avertissements-attendus.txt"
journal="$(mktemp)"
trap 'rm -f "$journal"' EXIT

"$@" >"$journal" 2>&1
statut=$?
cat "$journal"
if [ "$statut" -ne 0 ]; then
  exit "$statut"
fi

# Messages d'avertissement de npm (« npm warn ») et de Vite (« WARN » ou « [warn] »).
# Les accents graves autour des noms d'options varient selon la sortie : ils sont retirés.
avertissements="$({ grep -i -E '^npm warn|^[[:space:]]*WARN[[:space:]]|\[warn\]' "$journal" || true; } | tr -d '`')"
echec=0

if [ -n "$avertissements" ]; then
  echo "$avertissements" | while IFS= read -r ligne; do
    reconnu=1
    while IFS='|' read -r p extrait _cause _date; do
      case "$p" in '' | '#'*) continue ;; esac
      if [ "$p" = "$portee" ] && printf '%s' "$ligne" | grep -q -F -- "$extrait"; then
        reconnu=0
        break
      fi
    done <"$liste"
    if [ "$reconnu" -ne 0 ]; then
      echo "AVERTISSEMENT NON DÉCLARÉ ($portee) : $ligne" >&2
      exit 1
    fi
  done || echec=1
fi

while IFS='|' read -r p extrait _cause date; do
  case "$p" in '' | '#'*) continue ;; esac
  [ "$p" = "$portee" ] || continue
  if ! printf '%s\n' "$avertissements" | grep -q -F -- "$extrait"; then
    echo "AVERTISSEMENT DISPARU ($portee) : « $extrait » (vérifié le $date) — supprimer la ligne de front/avertissements-attendus.txt" >&2
    echec=1
  fi
done <"$liste"

exit "$echec"
