#!/usr/bin/env bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# system-update.sh — mise à jour système du LXC strapontin
# À exécuter depuis le HOST Proxmox (192.168.1.99) en tant que root
#
# Ordre : snapshot → apt upgrade → vérifier services → healthcheck
# En cas d'échec : rollback via la commande affichée

VMID="${1:?Usage: system-update.sh <VMID>}"
DATE=$(date +%Y%m%d)
NOM_SNAPSHOT="pre-sysupdate-${DATE}"

echo "=== Mise à jour système LXC $VMID ==="

# 1. Snapshot Proxmox avant toute modification
echo "[1/4] Snapshot : $NOM_SNAPSHOT..."
if pct listsnapshot "$VMID" | grep -q "^.*${NOM_SNAPSHOT}"; then
    echo "  Snapshot du jour déjà présent, ignoré"
else
    pct snapshot "$VMID" "$NOM_SNAPSHOT" --description "Avant apt upgrade ${DATE}"
    echo "  Snapshot créé — rollback disponible :"
    echo "    pct rollback $VMID $NOM_SNAPSHOT"
fi

# 2. apt update + upgrade (hors paquets blacklistés dans 50unattended-upgrades)
echo "[2/4] apt update + upgrade..."
pct exec "$VMID" -- bash -c "
    DEBIAN_FRONTEND=noninteractive apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y
    DEBIAN_FRONTEND=noninteractive apt-get autoremove -y --purge
"

# 3. Vérifier que les services sont toujours actifs
echo "[3/4] Vérification des services..."
SERVICES="postgresql strapontin-web strapontin-worker"
TOUT_OK=1

for SERVICE in $SERVICES; do
    STATUT=$(pct exec "$VMID" -- systemctl is-active "$SERVICE" 2>/dev/null || echo "inactif")
    if [ "$STATUT" != "active" ]; then
        echo "  [AVERTISSEMENT] $SERVICE : $STATUT — tentative de redémarrage..."
        pct exec "$VMID" -- systemctl restart "$SERVICE" || true
        STATUT=$(pct exec "$VMID" -- systemctl is-active "$SERVICE" 2>/dev/null || echo "inactif")
        if [ "$STATUT" != "active" ]; then
            echo "  [ECHEC] $SERVICE toujours inactif après redémarrage"
            TOUT_OK=0
        else
            echo "  [OK] $SERVICE redémarré"
        fi
    else
        echo "  [OK] $SERVICE : actif"
    fi
done

if [ "$TOUT_OK" -eq 0 ]; then
    echo "" >&2
    echo "ERREUR : un ou plusieurs services sont inactifs" >&2
    echo "Pour restaurer l'état précédent :" >&2
    echo "  pct rollback $VMID $NOM_SNAPSHOT" >&2
    exit 1
fi

# 4. Healthcheck applicatif
echo "[4/4] Healthcheck..."
RETRIES=10
until pct exec "$VMID" -- curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1 || [ "$RETRIES" -eq 0 ]; do
    sleep 3
    RETRIES=$((RETRIES - 1))
done

if [ "$RETRIES" -eq 0 ]; then
    echo "" >&2
    echo "ERREUR : healthcheck échoué après mise à jour" >&2
    echo "Pour restaurer l'état précédent :" >&2
    echo "  pct rollback $VMID $NOM_SNAPSHOT" >&2
    exit 1
fi

echo ""
echo "=== Mise à jour système réussie ==="
echo ""
echo "Snapshot conservé : $NOM_SNAPSHOT"
echo "Supprimer après 48h si tout est stable :"
echo "  pct delsnapshot $VMID $NOM_SNAPSHOT"
