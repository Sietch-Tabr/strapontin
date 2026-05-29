#!/usr/bin/env bash
set -euo pipefail

# restore.sh — restaure la base strapontin depuis une archive backup tar.gz
# Usage : restore.sh <strapontin_YYYYMMDD_HHMMSS.tar.gz>
# À exécuter en tant que root depuis le LXC prod/dev.

FICHIER="${1:?Usage: restore.sh <strapontin_YYYYMMDD_HHMMSS.tar.gz>}"
APP_DIR="/opt/strapontin"

[ -f "$FICHIER" ] || { echo "ERREUR : fichier introuvable : $FICHIER" >&2; exit 1; }
[[ "$FICHIER" == *.tar.gz ]] || { echo "ERREUR : fichier attendu en .tar.gz" >&2; exit 1; }

echo "=== Restauration Strapontin ==="
echo "Fichier : $FICHIER"
echo ""

echo "[1/5] Arrêt des services..."
systemctl stop strapontin-web strapontin-worker

echo "[2/5] Extraction de l'archive..."
tar -xzf "$FICHIER" -C /tmp dump.sql
echo "  dump.sql extrait"

echo "[3/5] Réinitialisation de la base..."
su - postgres -c "dropdb --if-exists strapontin && createdb strapontin"

echo "[4/5] Restauration du dump..."
su - postgres -c "psql strapontin" < /tmp/dump.sql
rm /tmp/dump.sql

echo "[5/5] Redémarrage des services..."
systemctl start strapontin-web strapontin-worker

echo ""
echo "================================================================"
echo "  Restauration terminée"
echo "  Vérifiez l'application : curl -sf http://localhost:8080/actuator/health"
echo "  IMPORTANT : restaurer aussi le .env de l'archive pour que"
echo "  APP_CONFIG_SECRET corresponde aux donnees chiffrees du dump :"
echo "    tar -xzf $FICHIER -C $APP_DIR .env"
echo "    systemctl restart strapontin-web strapontin-worker"
echo "================================================================"
