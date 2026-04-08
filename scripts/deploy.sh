#!/usr/bin/env bash
set -euo pipefail

# deploy.sh — premier déploiement de l'application sur le LXC
# À exécuter depuis le LXC en tant que strapontin (ou root)
# Prérequis : /app/.env configuré, PostgreSQL et Redis démarrés

REPO_URL="${1:-}"
APP_DIR="/opt/strapontin"

echo "=== Premier déploiement Strapontin ==="

# Cloner ou synchroniser le dépôt
if [ -d "$APP_DIR/.git" ]; then
    echo "Dépôt déjà présent, synchronisation..."
    su - strapontin -c "GIT_SSH_COMMAND='ssh -i ~/.ssh/id_ed25519' git -C $APP_DIR pull"
elif [ -n "$REPO_URL" ]; then
    git clone "$REPO_URL" "$APP_DIR"
else
    echo "ERREUR : dépôt absent et aucune URL fournie" >&2
    exit 1
fi

cd "$APP_DIR"

# Vérifier que le .env est en place
if [ ! -f "$APP_DIR/.env" ]; then
    echo "ERREUR : $APP_DIR/.env manquant" >&2
    echo "Créez le fichier .env à partir de .env.example avant de continuer" >&2
    exit 1
fi

# Installer les dépendances
echo "Installation des dépendances..."
su - strapontin -c "cd $APP_DIR && pnpm install --frozen-lockfile"

# Migrations DB
echo "Migrations..."
su - strapontin -c "set -a; source $APP_DIR/.env; set +a; cd $APP_DIR && pnpm --filter @strapontin/core db:migrate"

# Build
echo "Build..."
su - strapontin -c "cd $APP_DIR && pnpm build"

# Démarrer les services
echo "Démarrage des services..."
systemctl restart strapontin-web strapontin-worker

# Healthcheck — retries sur 30s (port 3000)
echo "Healthcheck..."
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
echo "=== Déploiement réussi — strapontin.kwaite.fr ==="
