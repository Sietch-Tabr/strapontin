#!/usr/bin/env bash
set -euo pipefail

# backup.sh — dump PostgreSQL + .env dans une archive tar.gz vers le NAS
# Usage : bash /opt/strapontin/scripts/backup.sh
# À exécuter en tant que root depuis le LXC.

BACKUP_DIR="/mnt/backup"
APP_DIR="/opt/strapontin"

if ! mountpoint -q "$BACKUP_DIR"; then
    echo "ERREUR : $BACKUP_DIR n'est pas monté" >&2
    exit 1
fi

HORODATAGE=$(date +%Y%m%d_%H%M%S)
ARCHIVE="$BACKUP_DIR/strapontin_${HORODATAGE}.tar.gz"

echo "Sauvegarde en cours — $HORODATAGE"

# Dump PostgreSQL dans un fichier temporaire
su - postgres -c "pg_dump strapontin" > /tmp/dump.sql
echo "  dump DB : OK"

# Archive tar.gz : dump.sql + .env
if [ -f "$APP_DIR/.env" ]; then
    tar -czf "$ARCHIVE" -C /tmp dump.sql -C "$APP_DIR" .env
    echo "  .env    : OK"
else
    tar -czf "$ARCHIVE" -C /tmp dump.sql
fi
rm /tmp/dump.sql
echo "  archive : $ARCHIVE"

# Purge des sauvegardes > 30 jours
find "$BACKUP_DIR" -name "strapontin_*.tar.gz" -mtime +30 -delete
echo "  purge   : OK"

echo "Sauvegarde terminée"
