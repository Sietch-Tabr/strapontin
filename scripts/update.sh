#!/bin/bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# update.sh — redémarrage des services Strapontin après déploiement de nouveaux JARs
# Usage : /opt/strapontin/update.sh
# Usage : /opt/strapontin/scripts/update.sh
# Appelé par Woodpecker (prod) ou manuellement (dev) après scp des JARs vers *.jar.new.

APP_DIR="/opt/strapontin"

rollback() {
    echo "[update] Rollback en cours..." >&2
    set +e
    [ -f "$APP_DIR/web.jar.prev" ] && mv "$APP_DIR/web.jar.prev" "$APP_DIR/web.jar"
    [ -f "$APP_DIR/worker.jar.prev" ] && mv "$APP_DIR/worker.jar.prev" "$APP_DIR/worker.jar"
    systemctl restart strapontin-web strapontin-worker
    echo "[update] Rollback terminé" >&2
    set -e
}

# Sauvegarde des JARs actifs (absent au premier déploiement)
echo "[update] Sauvegarde des JARs..."
[ -f "$APP_DIR/web.jar" ] && cp "$APP_DIR/web.jar" "$APP_DIR/web.jar.prev"
[ -f "$APP_DIR/worker.jar" ] && cp "$APP_DIR/worker.jar" "$APP_DIR/worker.jar.prev"

# Activation atomique : mv est atomique sur le même filesystem
echo "[update] Activation des nouveaux JARs..."
mv "$APP_DIR/web.jar.new" "$APP_DIR/web.jar"
mv "$APP_DIR/worker.jar.new" "$APP_DIR/worker.jar"

echo "[update] Redémarrage des services..."
systemctl restart strapontin-web strapontin-worker

echo "[update] Attente démarrage web (max 60 s)..."
HEALTH_OK=false
for i in $(seq 1 60); do
    if curl -sf "http://localhost:9090/actuator/health" | grep -q '"status":"UP"'; then
        echo "[update] Service web UP (${i}s)"
        HEALTH_OK=true
        break
    fi
    sleep 1
done

sleep 5

if [ "$HEALTH_OK" = false ]; then
    echo "[update] ERREUR : service web non UP après 60s" >&2
    journalctl -u strapontin-web -n 30 --no-pager >&2
    rollback
    exit 1
fi

echo "[update] Vérification worker..."
sleep 3
if ! systemctl is-active --quiet strapontin-worker; then
    echo "[update] ERREUR : strapontin-worker inactif" >&2
    journalctl -u strapontin-worker -n 20 --no-pager >&2
    rollback
    exit 1
fi
echo "[update] Service worker UP"

echo "[update] Régénération de la baseline AIDE..."
if command -v aide >/dev/null 2>&1 && [ -f /etc/aide/aide.conf ]; then
    # #976 : sans cette régénération, chaque déploiement légitime (web.jar/worker.jar modifiés)
    # déclencherait une fausse alerte d'intégrité au prochain contrôle quotidien.
    aide --config=/etc/aide/aide.conf --init >/dev/null 2>&1 || true
    if [ -f /var/lib/aide/aide.db.new ]; then
        mv /var/lib/aide/aide.db.new /var/lib/aide/aide.db
        echo "[update] Baseline AIDE régénérée"
    else
        echo "[update] AVERTISSEMENT : régénération de la baseline AIDE échouée" >&2
    fi
else
    echo "[update] AIDE non installé — étape ignorée"
fi

echo "[update] Déploiement terminé."
