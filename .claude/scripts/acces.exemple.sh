#!/bin/bash
# Gabarit de l'accès aux conteneurs du projet, utilisé par les skills et l'agent de suivi.
# Copier en .claude/scripts/acces.local.sh (ignoré par git) puis implémenter les deux
# opérations pour son infrastructure. La procédure d'accès réelle n'est jamais versionnée.
#
# Usage :
#   acces.local.sh <cible> exec "<commande>"     exécute une commande dans le conteneur
#   acces.local.sh <cible> psql <fichier.sql>    exécute un fichier SQL sur la base strapontin
#   acces.local.sh hote exec "<commande>"        exécute une commande sur l'hôte des conteneurs
#   acces.local.sh <cible> copier <fichier> <cible-dest> <fichier-dest>
#                                                copie un fichier d'un conteneur à un autre
#
# Cibles : prod, dev, ci (et hote pour l'hôte lui-même).

set -euo pipefail

cible="${1:?cible manquante (prod, dev, ci, hote)}"
operation="${2:?opération manquante (exec, psql)}"
shift 2

case "$operation" in
  exec | psql | copier)
    echo "acces.local.sh non configuré : implémenter '$operation' pour la cible '$cible'." >&2
    exit 2
    ;;
  *)
    echo "opération inconnue : $operation" >&2
    exit 2
    ;;
esac
