#!/usr/bin/env bash
set -euo pipefail

# update.sh — mise à jour de l'application en production
# À exécuter depuis le LXC (déclenché par Woodpecker via SSH)
# Ordre strict : backup → pull → install → build → migrate → restart → healthcheck

APP_DIR="/opt/strapontin"

cd "$APP_DIR"

echo "=== Mise à jour Strapontin ==="

# 1. Backup préalable obligatoire
echo "[1/6] Sauvegarde..."
"$APP_DIR/scripts/backup.sh"

# 2. Récupérer les dernières modifications (reset dur pour éviter les conflits avec patches locaux)
echo "[2/6] git pull..."
su - strapontin -c "GIT_SSH_COMMAND='ssh -i ~/.ssh/id_ed25519' git -C $APP_DIR fetch origin && git -C $APP_DIR reset --hard origin/main"

# 3. Installer les dépendances
echo "[3/6] pnpm install..."
su - strapontin -c "cd $APP_DIR && pnpm install --frozen-lockfile"

# 4. Build — exécuté en root pour éviter les conflits de permissions sur .svelte-kit/output
# (la build initiale via deploy.sh crée des fichiers root-owned ; le service systemd
# n'a besoin que de lire le résultat, pas d'en être propriétaire)
echo "[4/6] build..."
set -a; source "$APP_DIR/.env"; set +a
pnpm build

# 5. Migrations DB
echo "[5/6] migrations..."
su - strapontin -c "set -a; source $APP_DIR/.env; set +a; cd $APP_DIR && pnpm --filter @strapontin/core db:migrate"

# 6. Redémarrage des services
echo "[6/6] redémarrage..."
systemctl restart strapontin-web strapontin-worker

# Healthcheck — retries sur 30s
RETRIES=10
until curl -sf http://localhost:3000 > /dev/null 2>&1 || [ "$RETRIES" -eq 0 ]; do
    sleep 3
    RETRIES=$((RETRIES - 1))
done
if [ "$RETRIES" -eq 0 ]; then
    echo "ERREUR : le serveur ne répond pas sur le port 3000" >&2
    systemctl status strapontin-web --no-pager >&2
    exit 1
fi
echo "=== Mise à jour réussie ==="
