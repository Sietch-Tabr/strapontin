#!/usr/bin/env bash
set -euo pipefail

# backup-local.sh — dump PostgreSQL + .env + logs dans une archive locale
# Usage : bash /opt/strapontin/scripts/backup-local.sh
# À exécuter en tant que root depuis le LXC.

BACKUP_DIR="/opt/strapontin/backup"
APP_DIR="/opt/strapontin"

mkdir -p "$BACKUP_DIR"

HORODATAGE=$(date +%Y%m%d_%H%M%S)

notifier_echec() {
    curl -sf -X POST \
        "http://localhost:8080/api/interne/backup-alerte?horodatage=$HORODATAGE" \
        >/dev/null 2>&1 || true
}
trap notifier_echec ERR
ARCHIVE="$BACKUP_DIR/strapontin_${HORODATAGE}.tar.gz"

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

find "$BACKUP_DIR" -name "strapontin_*.tar.gz" -mtime +7 -delete
echo "  purge   : OK (rétention 7 jours)"

echo "Sauvegarde locale terminée"
