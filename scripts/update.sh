#!/usr/bin/env bash
set -euo pipefail

# update.sh — mise à jour de l'application en production
# À exécuter depuis le LXC (déclenché par Woodpecker via SSH)
# Ordre strict : backup → pull → install → build → migrate → restart → healthcheck

APP_DIR="/app"

cd "$APP_DIR"

echo "=== Mise à jour Strapontin ==="

# 1. Backup préalable obligatoire
echo "[1/6] Sauvegarde..."
"$APP_DIR/scripts/backup.sh"

# 2. Récupérer les dernières modifications
echo "[2/6] git pull..."
git pull

# 3. Installer les dépendances
echo "[3/6] pnpm install..."
pnpm install --frozen-lockfile

# 4. Build
echo "[4/6] build..."
pnpm build

# 5. Migrations DB
echo "[5/6] migrations..."
set -a; source "$APP_DIR/.env"; set +a
pnpm --filter @strapontin/core db:migrate

# 6. Redémarrage des services
echo "[6/6] redémarrage..."
systemctl restart concerts-web concerts-worker

# Healthcheck
sleep 3
if curl -sf http://localhost:3000/health > /dev/null; then
    echo "=== Mise à jour réussie ==="
else
    echo "ERREUR : healthcheck échoué après redémarrage" >&2
    exit 1
fi
