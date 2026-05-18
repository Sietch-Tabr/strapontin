#!/bin/bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# update.sh — redémarrage des services Strapontin après déploiement de nouveaux JARs
# Usage : /opt/strapontin-java/update.sh
# Appelé par Woodpecker (prod) ou manuellement (dev) après scp des JARs.

APP_DIR="/opt/strapontin"

echo "[update] Redémarrage des services..."
systemctl restart strapontin-web strapontin-worker

echo "[update] Attente démarrage web (max 30 s)..."
for i in $(seq 1 30); do
    if curl -sf "http://localhost:8080/actuator/health" | grep -q '"status":"UP"'; then
        echo "[update] Service web UP (${i}s)"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "[update] ERREUR : service web non UP après 30s" >&2
        journalctl -u strapontin-web -n 30 --no-pager >&2
        exit 1
    fi
    sleep 1
done

echo "[update] Déploiement terminé."
