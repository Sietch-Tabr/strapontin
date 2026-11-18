#!/usr/bin/env bash
set -euo pipefail

# restore.sh — restaure la base strapontin depuis une sauvegarde
# Usage : restore.sh <strapontin-ENV_YYYYMMDD_HHMMSS.sql.gz | ancienne archive .tar.gz>
# Une sauvegarde .sql.gz est restaurée avec le .env de même nom (.env) s'il est à côté ; une
# ancienne archive .tar.gz (dump + .env + journaux) l'est avec son contenu.
# À exécuter en tant que root depuis le LXC prod/dev.
# La logique est dans le JAR exploitation-controle (commande restore) : lecture et contrôle de la
# sauvegarde avant l'arrêt des services, première erreur SQL fatale (services alors laissés arrêtés).

. "$(dirname "$0")/chemins.sh"
lancer_controle restore "$@"
