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
pnpm install --frozen-lockfile

# Migrations DB
echo "Migrations..."
set -a; source "$APP_DIR/.env"; set +a
pnpm --filter @strapontin/core db:migrate

# Build
echo "Build..."
pnpm build

# Démarrer les services
echo "Démarrage des services..."
systemctl restart strapontin-web strapontin-worker

# Healthcheck
sleep 3
if curl -sf http://localhost:3000/health > /dev/null; then
    echo "=== Déploiement réussi — strapontin.kwaite.fr ==="
else
    echo "ERREUR : healthcheck échoué" >&2
    exit 1
fi
