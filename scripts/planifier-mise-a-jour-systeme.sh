#!/bin/bash
set -euo pipefail

# planifier-mise-a-jour-systeme.sh — planifie la mise à jour système hebdomadaire d'un LXC depuis
# l'hôte Proxmox (root) : une minuterie systemd de l'hôte lance system-update.sh (snapshot, apt
# upgrade, contrôle des services et de la santé de l'application, retour arrière indiqué).
# Le retard est contrôlé de son côté, dans le LXC, par controle-systeme.sh.
# Usage (hôte Proxmox) : bash planifier-mise-a-jour-systeme.sh <VMID> [OnCalendar]
#   exemple : bash planifier-mise-a-jour-systeme.sh 113 "Sun *-*-* 03:30:00"
#   retrait : systemctl disable --now strapontin-system-update-<VMID>.timer

VMID="${1:?Usage: planifier-mise-a-jour-systeme.sh <VMID> [OnCalendar]}"
[[ "$VMID" =~ ^[0-9]+$ ]] || { echo "ERREUR : VMID numérique attendu" >&2; exit 1; }
CALENDRIER="${2:-Sun *-*-* 03:30:00}"
SOURCE="$(cd "$(dirname "$0")" && pwd)/system-update.sh"
INSTALLE="${SCRIPT_HOTE:-/usr/local/sbin/strapontin-system-update.sh}"
UNITES="${UNITES_HOTE:-/etc/systemd/system}"

install -m 0755 "$SOURCE" "$INSTALLE"
cat > "$UNITES/strapontin-system-update-$VMID.service" <<UNITE
[Unit]
Description=Strapontin — mise à jour système du LXC $VMID

[Service]
Type=oneshot
ExecStart=$INSTALLE $VMID
UNITE
cat > "$UNITES/strapontin-system-update-$VMID.timer" <<UNITE
[Unit]
Description=Mise à jour système planifiée du LXC $VMID

[Timer]
OnCalendar=$CALENDRIER
Persistent=true

[Install]
WantedBy=timers.target
UNITE
systemctl daemon-reload
systemctl enable --now "strapontin-system-update-$VMID.timer"
systemctl list-timers "strapontin-system-update-$VMID.timer" --no-pager
