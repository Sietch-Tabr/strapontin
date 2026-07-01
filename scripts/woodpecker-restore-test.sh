#!/bin/bash
set -euo pipefail

# woodpecker-restore-test.sh — Test hebdomadaire de restauration backup
# Séquence : clone LXC prod → démarrage → restauration → health check → destruction
# Secrets requis : ssh_key, proxmox_host
# Variable : LXC_PROD_ID (défaut 113), LXC_TEMP_ID (défaut 199)

LXC_PROD_ID="${LXC_PROD_ID:-113}"
LXC_TEMP_ID="${LXC_TEMP_ID:-199}"
PROXMOX_HOST="${PROXMOX_HOST:-192.168.1.99}"
WAIT_DEMARRAGE="${WAIT_DEMARRAGE:-60}"
WAIT_RESTORE="${WAIT_RESTORE:-30}"

echo "$SSH_KEY" > /tmp/key
chmod 600 /tmp/key

SSH="ssh -i /tmp/key -o StrictHostKeyChecking=no -o ConnectTimeout=10"
PROXMOX="root@${PROXMOX_HOST}"

cleanup() {
    echo "[restore-test] Nettoyage LXC temporaire ${LXC_TEMP_ID}..."
    $SSH "$PROXMOX" "pct stop ${LXC_TEMP_ID} --force 2>/dev/null; pct destroy ${LXC_TEMP_ID} 2>/dev/null" || true
    rm -f /tmp/key
}
trap cleanup EXIT

echo "[restore-test] Suppression de l'éventuel LXC temporaire ${LXC_TEMP_ID} existant..."
$SSH "$PROXMOX" "pct stop ${LXC_TEMP_ID} --force 2>/dev/null; pct destroy ${LXC_TEMP_ID} 2>/dev/null" || true

echo "[restore-test] Clonage LXC ${LXC_PROD_ID} → ${LXC_TEMP_ID}..."
$SSH "$PROXMOX" "pct clone ${LXC_PROD_ID} ${LXC_TEMP_ID} --full 1 --hostname strapontin-restore-test"

echo "[restore-test] Démarrage LXC ${LXC_TEMP_ID}..."
$SSH "$PROXMOX" "pct start ${LXC_TEMP_ID}"

echo "[restore-test] Attente du démarrage des services (${WAIT_DEMARRAGE}s)..."
sleep "${WAIT_DEMARRAGE}"

echo "[restore-test] Recherche du dernier backup local..."
ARCHIVE=$($SSH "$PROXMOX" "pct exec ${LXC_TEMP_ID} -- bash -c 'ls -t /opt/strapontin/backup/strapontin_*.tar.gz 2>/dev/null | head -1'" || true)
if [ -z "$ARCHIVE" ]; then
    echo "[restore-test] ERREUR : aucun backup trouvé dans le LXC ${LXC_TEMP_ID}" >&2
    exit 1
fi
echo "[restore-test] Archive : ${ARCHIVE}"

echo "[restore-test] Exécution de restore.sh sur le LXC ${LXC_TEMP_ID}..."
$SSH "$PROXMOX" "pct exec ${LXC_TEMP_ID} -- bash /opt/strapontin/scripts/restore.sh '${ARCHIVE}'"

echo "[restore-test] Attente post-restore (${WAIT_RESTORE}s)..."
sleep "${WAIT_RESTORE}"

echo "[restore-test] Health check web..."
HEALTH=$($SSH "$PROXMOX" "pct exec ${LXC_TEMP_ID} -- curl -sf http://localhost:8080/actuator/health" || echo "ECHEC")
echo "[restore-test] Réponse : ${HEALTH}"
if ! echo "$HEALTH" | grep -q '"status":"UP"'; then
    echo "[restore-test] ERREUR : health check web échoué — restauration invalide" >&2
    exit 1
fi

echo "[restore-test] Health check worker..."
HEALTH_WORKER=$($SSH "$PROXMOX" "pct exec ${LXC_TEMP_ID} -- curl -sf http://localhost:9091/actuator/health" || echo "ECHEC")
echo "[restore-test] Réponse worker : ${HEALTH_WORKER}"
if ! echo "$HEALTH_WORKER" | grep -q '"status":"UP"'; then
    echo "[restore-test] ERREUR : health check worker échoué" >&2
    exit 1
fi

echo "[restore-test] Test de restauration OK — backup validé"
