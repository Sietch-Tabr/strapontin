#!/usr/bin/env bash
set -euo pipefail

# backup.sh — sauvegarde pg_dump + .env vers le NAS
# À exécuter depuis le LXC en tant que root ou strapontin

BACKUP_DIR="/mnt/backup"
APP_DIR="/opt/strapontin"

# Vérifier que le backup NAS est monté
if ! mountpoint -q "$BACKUP_DIR"; then
    echo "ERREUR : $BACKUP_DIR n'est pas monté" >&2
    exit 1
fi

HORODATAGE=$(date +%Y%m%d_%H%M%S)

echo "Sauvegarde en cours — $HORODATAGE"

# Dump PostgreSQL (exécuté en tant que postgres pour la peer auth)
su - postgres -c "pg_dump strapontin" | gzip > "$BACKUP_DIR/strapontin_${HORODATAGE}.sql.gz"
echo "  dump DB : OK"

# Sauvegarde .env
if [ -f "$APP_DIR/.env" ]; then
    cp "$APP_DIR/.env" "$BACKUP_DIR/.env.${HORODATAGE}"
    echo "  .env    : OK"
fi

# Purge des sauvegardes > 30 jours
find "$BACKUP_DIR" -name "*.sql.gz" -mtime +30 -delete
find "$BACKUP_DIR" -name ".env.*"   -mtime +30 -delete
echo "  purge   : OK"

echo "Sauvegarde terminée"
