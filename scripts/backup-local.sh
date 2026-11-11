#!/usr/bin/env bash
set -euo pipefail

# backup-local.sh — dump PostgreSQL compressé à la volée + .env, en local (voir backup.sh)
# Usage : bash /opt/strapontin/scripts/backup-local.sh
# À exécuter en tant que root depuis le LXC.

# Surchargeables par l'environnement pour le test (tester-sauvegardes.sh).
APP_DIR="${APP_DIR:-/opt/strapontin}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backup}"

mkdir -p "$BACKUP_DIR"

BACKUP_SECRET=""
if [ -f "$APP_DIR/.env" ]; then
    BACKUP_SECRET=$(grep '^BACKUP_INTERNAL_SECRET=' "$APP_DIR/.env" | cut -d'=' -f2- || true)
fi

HORODATAGE=$(date +%Y%m%d_%H%M%S)
ARCHIVE="$BACKUP_DIR/strapontin_${HORODATAGE}.sql.gz"
ENV_SAUVEGARDE="$BACKUP_DIR/strapontin_${HORODATAGE}.env"
PARTIEL="${ARCHIVE}.partiel"

_appel_statut() {
    curl -sf -X POST \
        -H "Authorization: Bearer $BACKUP_SECRET" \
        --data-urlencode "statut=$1" \
        --data-urlencode "horodatage=$HORODATAGE" \
        --data-urlencode "raison=${2:-}" \
        --data-urlencode "archive=${3:-}" \
        "${URL_STATUT:-http://localhost:8080/api/interne/backup-statut}" \
        >/dev/null 2>&1 || true
}

notifier_echec() {
    _appel_statut "echec" "Échec à la ligne $ERREUR_LIGNE : $ERREUR_CMD" ""
}

ERREUR_CMD=""
ERREUR_LIGNE=0
trap 'ERREUR_CMD=$BASH_COMMAND; ERREUR_LIGNE=$LINENO' ERR
nettoyer_partiel() { rm -f "$PARTIEL"; }
trap 'nettoyer_partiel; notifier_echec' EXIT

echo "Sauvegarde locale en cours — $HORODATAGE"

su - postgres -c "pg_dump strapontin" | gzip -c > "$PARTIEL"
gzip -t "$PARTIEL"
mv "$PARTIEL" "$ARCHIVE"
echo "  dump DB : OK ($ARCHIVE)"

if [ -f "$APP_DIR/.env" ]; then
    install -m 600 "$APP_DIR/.env" "$ENV_SAUVEGARDE"
    echo "  .env    : OK ($ENV_SAUVEGARDE)"
fi

find "$BACKUP_DIR" -type f -name "strapontin_*" -mtime +7 -delete
echo "  purge   : OK (rétention 7 jours)"

echo "Sauvegarde locale terminée"

# Succès — désarmer le trap d'échec avant d'appeler le statut succès
nettoyer_partiel
trap - EXIT
_appel_statut "succes" "" "$ARCHIVE"
