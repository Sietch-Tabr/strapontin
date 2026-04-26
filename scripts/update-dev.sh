#!/usr/bin/env bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# update-dev.sh — déploiement de la branche courante sur le LXC dev
# À exécuter depuis le Mac, à la racine du dépôt

LXC_DEV="192.168.20.78"
APP_USER="strapontin"
APP_DIR="/opt/strapontin"

BRANCHE=$(git rev-parse --abbrev-ref HEAD)

echo "=== Déploiement dev — branche : $BRANCHE ==="

ssh root@"$LXC_DEV" "
    su - $APP_USER -c \"GIT_SSH_COMMAND='ssh -i ~/.ssh/id_ed25519' git -C $APP_DIR fetch origin 2>&1\"
    su - $APP_USER -c \"git -C $APP_DIR checkout $BRANCHE 2>&1\"
    su - $APP_USER -c \"git -C $APP_DIR reset --hard origin/$BRANCHE 2>&1\"
    su - $APP_USER -c \"cd $APP_DIR && pnpm install --frozen-lockfile 2>&1\"
    su - $APP_USER -c \"set -a; source $APP_DIR/.env; set +a; cd $APP_DIR && NO_COLOR=1 pnpm --filter @strapontin/core db:migrate 2>&1\"
    systemctl restart strapontin-dev-web strapontin-dev-worker
"

echo "Healthcheck..."
RETRIES=20
until ssh root@"$LXC_DEV" "curl -sf http://localhost:3000/health" > /dev/null 2>&1 || [ "$RETRIES" -eq 0 ]; do
    sleep 3
    RETRIES=$((RETRIES - 1))
done

if [ "$RETRIES" -eq 0 ]; then
    echo "ERREUR : le dev server ne répond pas" >&2
    ssh root@"$LXC_DEV" "journalctl -u strapontin-dev-web --no-pager -n 30" >&2
    exit 1
fi

echo ""
echo "================================================================"
echo "  Déploiement terminé — http://$LXC_DEV:3000"
echo "  Branche : $BRANCHE"
echo "================================================================"
