#!/usr/bin/env bash
set -euo pipefail

# sql-dev.sh — exécute du SQL sur la base du LXC dev (jamais la prod), depuis le poste.
# Le SQL passe par l'entrée standard : aucun problème de guillemets entre le poste, ssh et su.
#
# Usage :
#   bash scripts/sql-dev.sh "select count(*) from evenements"
#   bash scripts/sql-dev.sh -f requete.sql
#   echo "select 1" | bash scripts/sql-dev.sh
# Variable : HOTE_DEV (défaut root@192.168.20.78). Sortie : colonnes séparées par « | », sans
# alignement ni en-têtes ; la première erreur SQL arrête l'exécution (code de sortie non nul).

HOTE="${HOTE_DEV:-root@192.168.20.78}"

case "${1:-}" in
  -f)
    fichier="${2:?usage : sql-dev.sh -f fichier.sql}"
    sql=$(cat "$fichier")
    ;;
  "")
    sql=$(cat)
    ;;
  *)
    sql="$1"
    ;;
esac

[ -n "$sql" ] || { echo "sql-dev : aucune requête" >&2; exit 2; }

printf '%s\n' "$sql" | ssh "$HOTE" \
  "su - postgres -c 'psql -d strapontin -X -At -F \" | \" -v ON_ERROR_STOP=1'"
