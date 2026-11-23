#!/usr/bin/env bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# system-update.sh — mise à jour système du LXC strapontin
# À exécuter depuis le HOST Proxmox (192.168.1.99) en tant que root
#
# Ordre : sauvegarde vzdump → apt upgrade → vérifier services → healthcheck
# En cas d'échec : retour arrière via la commande affichée
# Pas de « pct snapshot » : un LXC dont un dossier du NAS est monté (mp0, bind) n'a pas cette
# fonction. vzdump en mode snapshot exclut le montage et ne coupe pas le conteneur.

VMID="${1:?Usage: system-update.sh <VMID>}"
# Port de l'actuator du web (le port applicatif sert la SPA et répond 200 à tout chemin inconnu).
PORT_WEB_SANTE="${PORT_WEB_SANTE:-9090}"
STOCKAGE_SAUVEGARDE="${STOCKAGE_SAUVEGARDE:-local}"
DOSSIER_SAUVEGARDE="${DOSSIER_SAUVEGARDE:-/var/lib/vz/dump}"

echo "=== Mise à jour système LXC $VMID ==="

# 1. Sauvegarde Proxmox avant toute modification (les deux plus récentes sont conservées)
echo "[1/4] Sauvegarde vzdump..."
vzdump "$VMID" --mode snapshot --storage "$STOCKAGE_SAUVEGARDE" --compress zstd \
    --prune-backups keep-last=2 > /dev/null
ARCHIVE="$(ls -t "$DOSSIER_SAUVEGARDE"/vzdump-lxc-"$VMID"-*.tar.zst | head -1)"
echo "  Archive : $ARCHIVE"
RETOUR_ARRIERE="pct set $VMID --protection 0; pct stop $VMID; pct restore $VMID $ARCHIVE --force"

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
    echo "  $RETOUR_ARRIERE" >&2
    exit 1
fi

# 4. Healthcheck applicatif
echo "[4/4] Healthcheck..."
RETRIES=10
until pct exec "$VMID" -- curl -sf "http://localhost:$PORT_WEB_SANTE/actuator/health" 2>/dev/null \
    | grep -q '"status":"UP"' || [ "$RETRIES" -eq 0 ]; do
    sleep 3
    RETRIES=$((RETRIES - 1))
done

if [ "$RETRIES" -eq 0 ]; then
    echo "" >&2
    echo "ERREUR : healthcheck échoué après mise à jour" >&2
    echo "Pour restaurer l'état précédent :" >&2
    echo "  $RETOUR_ARRIERE" >&2
    exit 1
fi

echo ""
echo "=== Mise à jour système réussie ==="
echo ""
echo "Sauvegarde conservée : $ARCHIVE"
echo "Retour arrière, si besoin :"
echo "  $RETOUR_ARRIERE"
