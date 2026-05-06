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
COMMIT_PRECEDENT=$(su - strapontin -c "git -C $APP_DIR rev-parse HEAD")
su - strapontin -c "GIT_SSH_COMMAND='ssh -i ~/.ssh/id_ed25519' git -C $APP_DIR fetch origin && git -C $APP_DIR reset --hard origin/main"

rollback() {
    trap - ERR  # désarmer pour éviter récursion si le rollback lui-même échoue
    echo "=== ROLLBACK vers $COMMIT_PRECEDENT ===" >&2
    if [ "${MIGRATION_LANCEE:-0}" = "1" ]; then
        echo "AVERTISSEMENT : la migration DB a peut-être été partiellement appliquée — vérifier l'état de la base avant de relancer" >&2
    fi
    su - strapontin -c "git -C $APP_DIR reset --hard $COMMIT_PRECEDENT"
    rm -rf "$APP_DIR/apps/web/build" "$APP_DIR/apps/web/.svelte-kit"
    set -a; source "$APP_DIR/.env"; set +a
    pnpm build
    systemctl restart strapontin-web strapontin-worker
    echo "=== Rollback terminé — version précédente restaurée ===" >&2
}

# À partir d'ici, tout échec déclenche le rollback automatique vers $COMMIT_PRECEDENT
trap 'rollback || echo "ERREUR CRITIQUE : le rollback a également échoué — intervention manuelle requise" >&2' ERR

# 3. Installer les dépendances
# Nettoyage préalable de .svelte-kit : svelte-kit sync (script prepare de pnpm install)
# échouerait avec EACCES si le répertoire appartient à root suite au build précédent
echo "[3/6] pnpm install..."
rm -rf "$APP_DIR/apps/web/.svelte-kit"
su - strapontin -c "cd $APP_DIR && pnpm install --frozen-lockfile"

# 4. Build — nettoyage préalable du dossier de sortie pour éviter EACCES
echo "[4/6] build..."
rm -rf "$APP_DIR/apps/web/build"
set -a; source "$APP_DIR/.env"; set +a
pnpm build

# 5. Migrations DB
# Si la migration échoue après avoir démarré, le schéma DB peut être partiellement modifié.
# Le rollback restaure uniquement le code — vérifier l'état de la base manuellement.
MIGRATION_LANCEE=1
echo "[5/6] migrations..."
su - strapontin -c "set -a; source $APP_DIR/.env; set +a; cd $APP_DIR && NO_COLOR=1 pnpm --filter @strapontin/core db:migrate"

# 6. Redémarrage des services
echo "[6/6] redémarrage..."
systemctl restart strapontin-web strapontin-worker

# Healthcheck — retries sur 30s
RETRIES=10
until curl -sf http://localhost:3000/health > /dev/null 2>&1 || [ "$RETRIES" -eq 0 ]; do
    sleep 3
    RETRIES=$((RETRIES - 1))
done
if [ "$RETRIES" -eq 0 ]; then
    echo "ERREUR : le serveur ne répond pas après le redémarrage" >&2
    systemctl status strapontin-web --no-pager >&2
    rollback || echo "ERREUR CRITIQUE : le rollback a également échoué — intervention manuelle requise" >&2
    exit 1
fi

echo "=== Mise à jour réussie ==="
