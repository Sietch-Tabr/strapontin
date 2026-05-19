#!/usr/bin/env bash
# restore.sh — restaure la base strapontin depuis un dump pg_dump compressé.
# Usage : restore.sh <fichier.sql.gz>
# À exécuter depuis le LXC prod/dev en tant que root.
set -euo pipefail

FICHIER="${1:?Usage: restore.sh <fichier.sql.gz>}"
APP_DIR="/opt/strapontin"

[ -f "$FICHIER" ] || { echo "ERREUR : fichier introuvable : $FICHIER" >&2; exit 1; }
[[ "$FICHIER" == *.sql.gz ]] || { echo "ERREUR : fichier attendu en .sql.gz" >&2; exit 1; }

echo "=== Restauration Strapontin ==="
echo "Fichier : $FICHIER"
echo ""

echo "[1/4] Arrêt des services..."
systemctl stop strapontin-web strapontin-worker

echo "[2/4] Réinitialisation de la base..."
su - postgres -c "dropdb --if-exists strapontin && createdb strapontin"

echo "[3/4] Restauration du dump..."
zcat "$FICHIER" | su - postgres -c "psql strapontin"

echo "[4/4] Redémarrage des services..."
systemctl start strapontin-web strapontin-worker

echo ""
echo "================================================================"
echo "  Restauration terminée"
echo "  Vérifiez l'application : curl -sf http://localhost:8080/actuator/health"
echo "================================================================"
