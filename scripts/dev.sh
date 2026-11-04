#!/usr/bin/env bash
set -euo pipefail

# dev.sh — cycle de vie du LXC dev (VMID 114), depuis le poste, via Proxmox.
# Usage : bash scripts/dev.sh demarrer | arreter | etat
# Variables : PROXMOX (défaut root@192.168.1.99), VMID_DEV (défaut 114), IP_DEV (défaut 192.168.20.78).
# `demarrer` ne rend la main qu'une fois web (:9090) et worker (:9091) en bonne santé ;
# l'actuator est sur 9090/9091, pas sur 8080 (port applicatif).

PROXMOX="${PROXMOX:-root@192.168.1.99}"
VMID="${VMID_DEV:-114}"
IP="${IP_DEV:-192.168.20.78}"

sante() {
  curl -s --max-time 3 "http://$IP:$1/actuator/health" | grep -q '"status":"UP"'
}

case "${1:-}" in
  demarrer)
    ssh -o ConnectTimeout=10 "$PROXMOX" "pct status $VMID | grep -q running || pct start $VMID"
    for _ in $(seq 1 45); do
      if sante 9090 && sante 9091; then echo "dev : web et worker en bonne santé"; exit 0; fi
      sleep 4
    done
    echo "dev : web ou worker toujours indisponible après 3 minutes" >&2
    exit 1
    ;;
  arreter)
    ssh -o ConnectTimeout=10 "$PROXMOX" "pct stop $VMID; pct status $VMID"
    ;;
  etat)
    ssh -o ConnectTimeout=10 "$PROXMOX" "pct status $VMID"
    if sante 9090 && sante 9091; then echo "web et worker : en bonne santé"; else echo "web ou worker : indisponible"; fi
    ;;
  *)
    echo "usage : dev.sh demarrer | arreter | etat" >&2
    exit 2
    ;;
esac
