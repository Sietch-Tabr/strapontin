#!/usr/bin/env bash
set -euo pipefail

# backup-local.sh — dump PostgreSQL compressé à la volée + .env, en local (voir backup.sh)
# Usage : bash /opt/strapontin/exploitation/backup-local.sh
# À exécuter en tant que root depuis le LXC.

# Emplacements surchargeables par l'environnement pour le test (tester-sauvegardes.sh).
. "$(dirname "$0")/chemins.sh"
BACKUP_DIR="$SAUVEGARDES_LOCALES"
ENV_NOM="$(environnement)"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

BACKUP_SECRET="$(lire_env BACKUP_INTERNAL_SECRET)"

HORODATAGE=$(date +%Y%m%d_%H%M%S)
ARCHIVE="$BACKUP_DIR/strapontin-${ENV_NOM}_${HORODATAGE}.sql.gz"
ENV_SAUVEGARDE="$BACKUP_DIR/strapontin-${ENV_NOM}_${HORODATAGE}.env"
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

if [ -f "$ENV_FICHIER" ]; then
    install -m 600 "$ENV_FICHIER" "$ENV_SAUVEGARDE"
    echo "  .env    : OK ($ENV_SAUVEGARDE)"
fi

# Rétention de 7 jours : sauvegardes de cet environnement et anciennes, sans préfixe.
find "$BACKUP_DIR" -type f \( -name "strapontin-${ENV_NOM}_*" -o -name "strapontin_*" \) -mtime +7 -delete
echo "  purge   : OK (rétention 7 jours)"

echo "Sauvegarde locale terminée"

# Succès — désarmer le trap d'échec avant d'appeler le statut succès
nettoyer_partiel
trap - EXIT
_appel_statut "succes" "" "$ARCHIVE"
