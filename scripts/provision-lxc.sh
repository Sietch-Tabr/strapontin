#!/usr/bin/env bash
set -euo pipefail

# provision-lxc.sh — création et provisioning du LXC concert
# À exécuter depuis le HOST Proxmox (192.168.1.99) en tant que root
# Idempotent : peut être relancé sans danger
#
# Prérequis :
#   - /volume1/backup/concerts/ créé dans DSM (Synology File Station)
#   - /mnt/synology/backup/ monté sur le host Proxmox (NFS4)

# ---------------------------------------------------------------
# Paramètres
# ---------------------------------------------------------------

VMID=$(pvesh get /cluster/nextid)
HOSTNAME="concert"
TEMPLATE_STORAGE="local"
DISK_STORAGE="local-lvm"
BRIDGE="vmbr0"
VLAN_TAG="20"
IP="192.168.20.79/24"
GW="192.168.20.1"
MAC="02:00:00:00:00:13"
NAMESERVER="192.168.1.3"
MEMORY="2048"
CORES="2"
DISK="20"
APP_DIR="/app"
APP_USER="concerts"
DB_NAME="concerts"
DB_USER="concerts"
NODE_VERSION="22"
PG_VERSION="18"

echo "=== Provisioning LXC concerts (VMID : $VMID) ==="

# ---------------------------------------------------------------
# 1. Template Debian 13
# ---------------------------------------------------------------

echo "[1] Résolution du template Debian 13..."
pveam update > /dev/null
TEMPLATE=$(pveam available --section system \
    | awk '{print $2}' \
    | grep "^debian-13-standard" \
    | sort -V \
    | tail -1)

if [ -z "$TEMPLATE" ]; then
    echo "ERREUR : aucun template debian-13 disponible" >&2
    exit 1
fi

TEMPLATE_PATH="/var/lib/vz/template/cache/$TEMPLATE"

if [ ! -f "$TEMPLATE_PATH" ]; then
    echo "[1] Téléchargement : $TEMPLATE"
    pveam download "$TEMPLATE_STORAGE" "$TEMPLATE"
else
    echo "[1] Template déjà présent : $TEMPLATE"
fi

# ---------------------------------------------------------------
# 2. Création du LXC
# ---------------------------------------------------------------

echo "[2] Création du conteneur LXC $VMID..."
pct create "$VMID" "$TEMPLATE_PATH" \
    --hostname "$HOSTNAME" \
    --cores "$CORES" \
    --memory "$MEMORY" \
    --rootfs "$DISK_STORAGE:$DISK" \
    --net0 "name=eth0,bridge=$BRIDGE,tag=$VLAN_TAG,hwaddr=$MAC,ip=$IP,gw=$GW" \
    --nameserver "$NAMESERVER" \
    --unprivileged 1 \
    --features "nesting=1" \
    --ostype debian \
    --start 0

# ---------------------------------------------------------------
# 3. Bind mount NAS
# ---------------------------------------------------------------

echo "[3] Montage NAS /mnt/backup..."
pct set "$VMID" --mp0 "/mnt/synology/backup/Strapontin,mp=/mnt/backup"

# ---------------------------------------------------------------
# 4. Démarrage
# ---------------------------------------------------------------

echo "[4] Démarrage du LXC..."
pct start "$VMID"
sleep 5

# ---------------------------------------------------------------
# 5. Installation des paquets dans le LXC
# ---------------------------------------------------------------

echo "[5] Autologin root sur la console Proxmox..."
pct exec "$VMID" -- bash -c "
    mkdir -p /etc/systemd/system/getty@tty1.service.d
    printf '[Service]\nExecStart=\nExecStart=-/sbin/agetty --autologin root --noclear %%I \$TERM\n' \
        > /etc/systemd/system/getty@tty1.service.d/autologin.conf
    systemctl daemon-reload
"

echo "[5] Mise à jour des paquets..."
pct exec "$VMID" -- bash -c "apt-get update && apt-get upgrade -y"

echo "[5] Installation des dépendances de base..."
pct exec "$VMID" -- apt-get install -y \
    curl git gnupg2 ca-certificates lsb-release \
    build-essential

# Node 22 (NodeSource)
echo "[5] Installation Node $NODE_VERSION..."
pct exec "$VMID" -- bash -c "
    curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION}.x | bash -
    apt-get install -y nodejs
"

# pnpm
echo "[5] Installation pnpm..."
pct exec "$VMID" -- npm install -g pnpm

# PostgreSQL 18
echo "[5] Installation PostgreSQL $PG_VERSION..."
pct exec "$VMID" -- bash -c "
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/postgresql.gpg
    echo 'deb [signed-by=/usr/share/keyrings/postgresql.gpg] https://apt.postgresql.org/pub/repos/apt \$(lsb_release -cs)-pgdg main' > /etc/apt/sources.list.d/pgdg.list
    apt-get update
    apt-get install -y postgresql-${PG_VERSION}
"

# Redis 8
echo "[5] Installation Redis 8..."
pct exec "$VMID" -- bash -c "
    curl -fsSL https://packages.redis.io/gpg | gpg --dearmor -o /usr/share/keyrings/redis.gpg
    echo 'deb [signed-by=/usr/share/keyrings/redis.gpg] https://packages.redis.io/deb \$(lsb_release -cs) main' > /etc/apt/sources.list.d/redis.list
    apt-get update
    apt-get install -y redis
"

# ---------------------------------------------------------------
# 6. Utilisateur et base de données
# ---------------------------------------------------------------

echo "[6] Création utilisateur système '$APP_USER'..."
pct exec "$VMID" -- bash -c "
    id $APP_USER &>/dev/null || useradd -m -d $APP_DIR -s /bin/bash $APP_USER
    mkdir -p $APP_DIR
    chown $APP_USER:$APP_USER $APP_DIR
"

echo "[6] Création base PostgreSQL..."
pct exec "$VMID" -- bash -c "
    systemctl start postgresql
    su - postgres -c \"psql -c \\\"CREATE USER $DB_USER WITH PASSWORD 'changeme';\\\" 2>/dev/null || true\"
    su - postgres -c \"psql -c \\\"CREATE DATABASE $DB_NAME OWNER $DB_USER;\\\" 2>/dev/null || true\"
"

# ---------------------------------------------------------------
# 7. Unit files systemd
# ---------------------------------------------------------------

echo "[7] Création des services systemd..."

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/concerts-web.service << 'UNIT'
[Unit]
Description=Strapontin — serveur web
After=network.target postgresql.service redis.service

[Service]
Type=simple
User=concerts
WorkingDirectory=/app
EnvironmentFile=/app/.env
ExecStart=/usr/bin/node /app/apps/web/build/index.js
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/concerts-worker.service << 'UNIT'
[Unit]
Description=Strapontin — worker BullMQ
After=network.target redis.service

[Service]
Type=simple
User=concerts
WorkingDirectory=/app
EnvironmentFile=/app/.env
ExecStart=/usr/bin/node /app/apps/worker/dist/index.js
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT"

# Cron systemd backup quotidien
pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/concerts-backup.service << 'UNIT'
[Unit]
Description=Strapontin — backup quotidien

[Service]
Type=oneshot
ExecStart=/app/scripts/backup.sh
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/concerts-backup.timer << 'UNIT'
[Unit]
Description=Strapontin — timer backup quotidien

[Timer]
OnCalendar=*-*-* 02:00:00
Persistent=true

[Install]
WantedBy=timers.target
UNIT"

# ---------------------------------------------------------------
# 8. Activation des services
# ---------------------------------------------------------------

echo "[8] Activation des services..."
pct exec "$VMID" -- bash -c "
    systemctl daemon-reload
    systemctl enable postgresql redis
    systemctl enable concerts-web concerts-worker
    systemctl enable concerts-backup.timer
    systemctl start concerts-backup.timer
"

echo ""
echo "=== Provisioning terminé ==="
echo "LXC $VMID ($HOSTNAME) — 192.168.20.79"
echo ""
echo "Étapes suivantes :"
echo "  1. Créer /app/.env dans le LXC (cp .env.example .env et compléter)"
echo "  2. Lancer : pct exec $VMID -- /app/scripts/deploy.sh <url_repo>"
