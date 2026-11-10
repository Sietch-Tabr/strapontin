#!/usr/bin/env bash
set -euo pipefail

# backup.sh — dump PostgreSQL compressé à la volée vers le NAS, et copie du .env à côté
# Usage : bash /opt/strapontin/scripts/backup.sh
# À exécuter en tant que root depuis le LXC.
#
# Une sauvegarde = deux fichiers de même préfixe : strapontin_<horodatage>.sql.gz (le dump, écrit
# une seule fois, sans fichier intermédiaire) et strapontin_<horodatage>.env (secrets de
# l'application, nécessaires à une restauration complète). Les journaux ne sont pas archivés :
# journald et les journaux d'accès les conservent, aucune restauration n'en a besoin.

# Surchargeables par l'environnement pour le test (tester-sauvegardes.sh).
BACKUP_DIR="${BACKUP_DIR:-/mnt/backup}"
APP_DIR="${APP_DIR:-/opt/strapontin}"

if ! mountpoint -q "$BACKUP_DIR"; then
    echo "ERREUR : $BACKUP_DIR n'est pas monté" >&2
    exit 1
fi

HORODATAGE=$(date +%Y%m%d_%H%M%S)
ARCHIVE="$BACKUP_DIR/strapontin_${HORODATAGE}.sql.gz"
ENV_SAUVEGARDE="$BACKUP_DIR/strapontin_${HORODATAGE}.env"
PARTIEL="${ARCHIVE}.partiel"

# Un dump interrompu ne laisse jamais un fichier qui ressemble à une sauvegarde complète.
trap 'rm -f "$PARTIEL"' EXIT

echo "Sauvegarde en cours — $HORODATAGE"

su - postgres -c "pg_dump strapontin" | gzip -c > "$PARTIEL"
gzip -t "$PARTIEL"
mv "$PARTIEL" "$ARCHIVE"
echo "  dump DB : OK ($ARCHIVE)"

if [ -f "$APP_DIR/.env" ]; then
    install -m 600 "$APP_DIR/.env" "$ENV_SAUVEGARDE"
    echo "  .env    : OK ($ENV_SAUVEGARDE)"
fi

# Purge des sauvegardes > 30 jours — tout fichier strapontin_*, pas seulement
# le format .tar.gz actuel (couvre les formats d'archive passés/futurs).
find "$BACKUP_DIR" -type f -name "strapontin_*" -mtime +30 -delete
echo "  purge   : OK"

echo "Sauvegarde terminée"
