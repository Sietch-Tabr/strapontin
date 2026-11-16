#!/bin/bash
set -euo pipefail

# restore-test.sh — Test à la demande de restauration backup (#748)
#
# À exécuter directement SUR Proxmox (root@192.168.1.99), jamais à distance :
# copier le script sur Proxmox puis l'exécuter là-bas. Jamais automatisé en
# cron/CI — le LXC Woodpecker n'a volontairement pas accès au réseau de
# management Proxmox, frontière de sécurité à ne pas franchir pour ce confort.
#
# Séquence : vzdump prod (snapshot, non disruptif) → restore → démarrage →
# restauration applicative → health check → destruction
#
# pct clone est délibérément évité : il refuse purement et simplement de
# cloner un conteneur ayant un point de montage bind (mp0, le partage NAS de
# la prod — "unable to clone mountpoint 'mp0' (type bind)"). vzdump gère ce
# cas nativement en excluant le bind mount ("not a volume"), puisque c'est
# le mécanisme déjà utilisé sans incident pour les sauvegardes régulières
# de la prod.
#
# Variable : LXC_PROD_ID (défaut 113), LXC_TEMP_ID (défaut 199)

LXC_PROD_ID="${LXC_PROD_ID:-113}"
LXC_TEMP_ID="${LXC_TEMP_ID:-199}"
WAIT_DEMARRAGE="${WAIT_DEMARRAGE:-60}"
WAIT_RESTORE="${WAIT_RESTORE:-30}"
VZDUMP_ARCHIVE=""

cleanup() {
    echo "[restore-test] Nettoyage LXC temporaire ${LXC_TEMP_ID}..."
    pct set "${LXC_TEMP_ID}" --protection 0 2>/dev/null || true
    pct destroy "${LXC_TEMP_ID}" --force 2>/dev/null || true
    [ -n "$VZDUMP_ARCHIVE" ] && rm -f "$VZDUMP_ARCHIVE" "${VZDUMP_ARCHIVE%.tar.zst}.log"
}
trap cleanup EXIT

echo "[restore-test] Suppression de l'éventuel LXC temporaire ${LXC_TEMP_ID} existant..."
pct set "${LXC_TEMP_ID}" --protection 0 2>/dev/null || true
pct destroy "${LXC_TEMP_ID}" --force 2>/dev/null || true

echo "[restore-test] Sauvegarde snapshot de la prod ${LXC_PROD_ID} (non disruptif)..."
vzdump "${LXC_PROD_ID}" --mode snapshot --storage local --compress zstd
VZDUMP_ARCHIVE=$(ls -t /var/lib/vz/dump/vzdump-lxc-${LXC_PROD_ID}-*.tar.zst | head -1)
echo "[restore-test] Archive vzdump : ${VZDUMP_ARCHIVE}"

echo "[restore-test] Restauration en LXC temporaire ${LXC_TEMP_ID}..."
pct restore "${LXC_TEMP_ID}" "${VZDUMP_ARCHIVE}" --storage local-lvm --hostname strapontin-restore-test --force

echo "[restore-test] Désactivation de la protection héritée de la prod..."
pct set "${LXC_TEMP_ID}" --protection 0

echo "[restore-test] Réattribution réseau (MAC neuve + DHCP — évite tout conflit avec l'IP statique de la prod)..."
pct set "${LXC_TEMP_ID}" --net0 name=eth0,bridge=vmbr0,tag=20,ip=dhcp

echo "[restore-test] Démarrage LXC ${LXC_TEMP_ID}..."
pct start "${LXC_TEMP_ID}"

echo "[restore-test] Attente du démarrage des services (${WAIT_DEMARRAGE}s)..."
sleep "${WAIT_DEMARRAGE}"

echo "[restore-test] Recherche du dernier backup local..."
ARCHIVE=$(pct exec "${LXC_TEMP_ID}" -- bash -c 'ls -t /var/opt/strapontin/sauvegardes/strapontin*_*.sql.gz /var/opt/strapontin/sauvegardes/strapontin*_*.tar.gz 2>/dev/null | head -1' || true)
if [ -z "$ARCHIVE" ]; then
    echo "[restore-test] ERREUR : aucun backup trouvé dans le LXC ${LXC_TEMP_ID}" >&2
    exit 1
fi
echo "[restore-test] Archive : ${ARCHIVE}"

echo "[restore-test] Exécution de restore.sh sur le LXC ${LXC_TEMP_ID}..."
pct exec "${LXC_TEMP_ID}" -- bash /opt/strapontin/exploitation/restore.sh "${ARCHIVE}"

echo "[restore-test] Attente post-restore (${WAIT_RESTORE}s)..."
sleep "${WAIT_RESTORE}"

echo "[restore-test] Health check web..."
HEALTH=$(pct exec "${LXC_TEMP_ID}" -- curl -sf http://localhost:9090/actuator/health || echo "ECHEC")
echo "[restore-test] Réponse : ${HEALTH}"
if ! echo "$HEALTH" | grep -q '"status":"UP"'; then
    echo "[restore-test] ERREUR : health check web échoué — restauration invalide" >&2
    exit 1
fi

echo "[restore-test] Health check worker..."
HEALTH_WORKER=$(pct exec "${LXC_TEMP_ID}" -- curl -sf http://localhost:9091/actuator/health || echo "ECHEC")
echo "[restore-test] Réponse worker : ${HEALTH_WORKER}"
if ! echo "$HEALTH_WORKER" | grep -q '"status":"UP"'; then
    echo "[restore-test] ERREUR : health check worker échoué" >&2
    exit 1
fi

echo "[restore-test] Test de restauration OK — backup validé"
