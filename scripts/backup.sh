#!/usr/bin/env bash
set -euo pipefail

# backup.sh — dump PostgreSQL compressé à la volée vers le NAS, et copie du .env à côté
# Usage : bash /opt/strapontin/exploitation/backup.sh
# À exécuter en tant que root depuis le LXC.
#
# Une sauvegarde = deux fichiers de même préfixe : strapontin-<env>_<horodatage>.sql.gz (le dump,
# écrit une seule fois, sans fichier intermédiaire) et strapontin-<env>_<horodatage>.env (secrets
# de l'application, nécessaires à une restauration complète), <env> valant dev ou prod : les deux
# environnements partagent le dossier du NAS sans se mélanger. Les journaux ne sont pas archivés :
# journald et les journaux d'accès les conservent, aucune restauration n'en a besoin.

# Emplacements surchargeables par l'environnement pour le test (tester-sauvegardes.sh).
. "$(dirname "$0")/chemins.sh"
BACKUP_DIR="$SAUVEGARDES_NAS"
ENV_NOM="$(environnement)"

if ! mountpoint -q "$BACKUP_DIR"; then
    echo "ERREUR : $BACKUP_DIR n'est pas monté" >&2
    exit 1
fi

HORODATAGE=$(date +%Y%m%d_%H%M%S)
ARCHIVE="$BACKUP_DIR/strapontin-${ENV_NOM}_${HORODATAGE}.sql.gz"
ENV_SAUVEGARDE="$BACKUP_DIR/strapontin-${ENV_NOM}_${HORODATAGE}.env"
PARTIEL="${ARCHIVE}.partiel"

# Un dump interrompu ne laisse jamais un fichier qui ressemble à une sauvegarde complète.
trap 'rm -f "$PARTIEL"' EXIT

echo "Sauvegarde en cours — $HORODATAGE"

su - postgres -c "pg_dump strapontin" | gzip -c > "$PARTIEL"
gzip -t "$PARTIEL"
mv "$PARTIEL" "$ARCHIVE"
echo "  dump DB : OK ($ARCHIVE)"

if [ -f "$ENV_FICHIER" ]; then
    install -m 600 "$ENV_FICHIER" "$ENV_SAUVEGARDE"
    echo "  .env    : OK ($ENV_SAUVEGARDE)"
fi

# Purge à 30 jours des sauvegardes de cet environnement et des anciennes, sans préfixe ; celles de
# l'autre environnement (strapontin-dev_*, strapontin-prod_*) ne sont jamais touchées.
find "$BACKUP_DIR" -type f \( -name "strapontin-${ENV_NOM}_*" -o -name "strapontin_*" \) -mtime +30 -delete
echo "  purge   : OK"

echo "Sauvegarde terminée"
