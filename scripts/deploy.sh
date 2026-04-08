#!/usr/bin/env bash
set -euo pipefail

# deploy.sh — premier déploiement de l'application sur le LXC
# À exécuter depuis le LXC en tant que strapontin (ou root)
# Prérequis : /app/.env configuré, PostgreSQL et Redis démarrés

REPO_URL="${1:-}"
APP_DIR="/opt/strapontin"

if [ -z "$REPO_URL" ]; then
    echo "Usage : deploy.sh <url_du_repo>" >&2
    exit 1
fi

echo "=== Premier déploiement Strapontin ==="

# Cloner le dépôt
if [ -d "$APP_DIR/.git" ]; then
    echo "Dépôt déjà présent dans $APP_DIR, utilisation de git pull"
    cd "$APP_DIR" && git pull
else
    git clone "$REPO_URL" "$APP_DIR"
    cd "$APP_DIR"
fi

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
