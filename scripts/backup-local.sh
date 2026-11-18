#!/usr/bin/env bash
set -euo pipefail

# backup-local.sh — dump PostgreSQL compressé à la volée + .env, en local (voir backup.sh)
# Usage : bash /opt/strapontin/exploitation/backup-local.sh
# À exécuter en tant que root depuis le LXC (timer strapontin-backup-local.timer).
# Rétention de 7 jours, compte rendu à l'application (POST /api/interne/backup-statut). La logique
# est dans le JAR exploitation-controle (commande backup-local).

. "$(dirname "$0")/chemins.sh"
lancer_controle backup-local "$@"
