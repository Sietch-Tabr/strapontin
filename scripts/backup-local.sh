#!/usr/bin/env bash
set -euo pipefail

# backup-local.sh — dump PostgreSQL + .env + logs dans une archive locale
# Usage : bash /opt/strapontin/scripts/backup-local.sh
# À exécuter en tant que root depuis le LXC.

BACKUP_DIR="/opt/strapontin/backup"
APP_DIR="/opt/strapontin"

mkdir -p "$BACKUP_DIR"

BACKUP_SECRET=""
if [ -f "$APP_DIR/.env" ]; then
    BACKUP_SECRET=$(grep '^BACKUP_INTERNAL_SECRET=' "$APP_DIR/.env" | cut -d'=' -f2- || true)
fi

HORODATAGE=$(date +%Y%m%d_%H%M%S)
ARCHIVE="$BACKUP_DIR/strapontin_${HORODATAGE}.tar.gz"

_appel_statut() {
    curl -sf -X POST \
        -H "Authorization: Bearer $BACKUP_SECRET" \
        --data-urlencode "statut=$1" \
        --data-urlencode "horodatage=$HORODATAGE" \
        --data-urlencode "raison=${2:-}" \
        --data-urlencode "archive=${3:-}" \
        "http://localhost:8080/api/interne/backup-statut" \
        >/dev/null 2>&1 || true
}

notifier_echec() {
    _appel_statut "echec" "Échec à la ligne $ERREUR_LIGNE : $ERREUR_CMD" ""
}

ERREUR_CMD=""
ERREUR_LIGNE=0
trap 'ERREUR_CMD=$BASH_COMMAND; ERREUR_LIGNE=$LINENO' ERR
trap notifier_echec EXIT

echo "Sauvegarde locale en cours — $HORODATAGE"

su - postgres -c "pg_dump strapontin" > /tmp/dump_local.sql
echo "  dump DB : OK"

TAR_ARGS=(-czf "$ARCHIVE" -C /tmp dump_local.sql)

if [ -f "$APP_DIR/.env" ]; then
    TAR_ARGS+=(-C "$APP_DIR" .env)
    echo "  .env    : OK"
fi

if [ -d "$APP_DIR/logs" ]; then
    TAR_ARGS+=(-C "$APP_DIR" logs)
    echo "  logs    : OK"
fi

tar "${TAR_ARGS[@]}"
rm /tmp/dump_local.sql
echo "  archive : $ARCHIVE"

find "$BACKUP_DIR" -type f -name "strapontin_*" -mtime +7 -delete
echo "  purge   : OK (rétention 7 jours)"

echo "Sauvegarde locale terminée"

# Succès — désarmer le trap d'échec avant d'appeler le statut succès
trap - EXIT
_appel_statut "succes" "" "$ARCHIVE"
