#!/usr/bin/env bash
set -euo pipefail

# provision-lxc.sh — création et provisioning du LXC strapontin
# À exécuter depuis le HOST Proxmox (192.168.1.99) en tant que root
# Idempotent : peut être relancé sans danger
#
# Prérequis :
#   - /volume1/backup/Strapontin/ créé dans DSM (Synology File Station)
#   - /mnt/synology/backup/ monté sur le host Proxmox (NFS4)

# ---------------------------------------------------------------
# Paramètres
# ---------------------------------------------------------------

VMID=$(pvesh get /cluster/nextid)
HOSTNAME="strapontin"
TEMPLATE_STORAGE="local"
DISK_STORAGE="local-lvm"
BRIDGE="vmbr0"
VLAN_TAG="20"
IP="192.168.20.79/24"
GW="192.168.20.1"
MAC="02:00:00:00:00:13"
MEMORY="2048"
CORES="2"
DISK="20"
APP_DIR="/opt/strapontin"
APP_USER="strapontin"
DB_NAME="strapontin"
DB_USER="strapontin"
NODE_VERSION="22"
PG_VERSION="18"

echo "=== Provisioning LXC strapontin (VMID : $VMID) ==="

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
    --unprivileged 1 \
    --features "nesting=1" \
    --ostype debian \
    --onboot 1 \
    --start 0

# ---------------------------------------------------------------
# 3. Bind mount NAS
# ---------------------------------------------------------------

echo "[3] Montage NAS /mnt/backup..."
pct set "$VMID" --mp0 "/mnt/synology/backup/Strapontin,mp=/mnt/backup"

echo "[3] Paramètre noyau vm.overcommit_memory sur le host (requis par Redis)..."
grep -qxF 'vm.overcommit_memory = 1' /etc/sysctl.d/99-redis.conf 2>/dev/null \
    || echo 'vm.overcommit_memory = 1' >> /etc/sysctl.d/99-redis.conf
sysctl -w vm.overcommit_memory=1

# ---------------------------------------------------------------
# 4. Démarrage
# ---------------------------------------------------------------

echo "[4] Démarrage du LXC..."
pct start "$VMID"
sleep 5

# ---------------------------------------------------------------
# 5. Système de base
# ---------------------------------------------------------------

echo "[5] Configuration locale et fuseau horaire..."
pct exec "$VMID" -- bash -c "
    echo 'en_US.UTF-8 UTF-8' >> /etc/locale.gen
    echo 'fr_FR.UTF-8 UTF-8' >> /etc/locale.gen
    locale-gen
    update-locale LANG=fr_FR.UTF-8 LC_ALL=fr_FR.UTF-8
    ln -sf /usr/share/zoneinfo/Europe/Paris /etc/localtime
    echo 'Europe/Paris' > /etc/timezone
    DEBIAN_FRONTEND=noninteractive dpkg-reconfigure -f noninteractive tzdata
"

echo "[5] Autologin root sur la console Proxmox..."
pct exec "$VMID" -- bash -c "
    mkdir -p /etc/systemd/system/getty@tty1.service.d
    printf '[Service]\nExecStart=\nExecStart=-/sbin/agetty --autologin root --noclear %%I linux\n' \
        > /etc/systemd/system/getty@tty1.service.d/autologin.conf
    systemctl daemon-reload
"

echo "[5] Paramètre réseau net.core.somaxconn dans le LXC..."
pct exec "$VMID" -- bash -c "
    echo 'net.core.somaxconn = 511' > /etc/sysctl.d/99-strapontin.conf
    sysctl -p /etc/sysctl.d/99-strapontin.conf
"

echo "[5] Configuration journald..."
pct exec "$VMID" -- bash -c "
    mkdir -p /etc/systemd/journald.conf.d
    printf '[Journal]\nSystemMaxUse=100M\n' > /etc/systemd/journald.conf.d/strapontin.conf
    systemctl restart systemd-journald
"

echo "[5] Mise à jour des paquets..."
pct exec "$VMID" -- bash -c "
    DEBIAN_FRONTEND=noninteractive apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y
"

echo "[5] Installation des dépendances de base..."
pct exec "$VMID" -- bash -c "
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        curl git gnupg2 ca-certificates lsb-release build-essential \
        unattended-upgrades
    printf 'APT::Periodic::Update-Package-Lists \"1\";\nAPT::Periodic::Unattended-Upgrade \"1\";\n' \
        > /etc/apt/apt.conf.d/20auto-upgrades
"

# Node 22 (NodeSource)
echo "[5] Installation Node $NODE_VERSION..."
pct exec "$VMID" -- bash -c "
    curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION}.x | bash -
    DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs
"

# pnpm
echo "[5] Installation pnpm..."
pct exec "$VMID" -- bash -c "NPM_CONFIG_UPDATE_NOTIFIER=false npm install -g pnpm --quiet"

# PostgreSQL 18
echo "[5] Installation PostgreSQL $PG_VERSION..."
pct exec "$VMID" -- bash -c "
    CODENAME=\$(lsb_release -cs)
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/postgresql.gpg
    echo \"deb [signed-by=/usr/share/keyrings/postgresql.gpg] https://apt.postgresql.org/pub/repos/apt \${CODENAME}-pgdg main\" > /etc/apt/sources.list.d/pgdg.list
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql-${PG_VERSION}
"

# Redis 8
echo "[5] Installation Redis 8..."
pct exec "$VMID" -- bash -c "
    CODENAME=\$(lsb_release -cs)
    curl -fsSL https://packages.redis.io/gpg | gpg --dearmor -o /usr/share/keyrings/redis.gpg
    echo \"deb [signed-by=/usr/share/keyrings/redis.gpg] https://packages.redis.io/deb \${CODENAME} main\" > /etc/apt/sources.list.d/redis.list
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y redis
"

# ---------------------------------------------------------------
# 6. Utilisateur, clé SSH, dépôt, base de données
# ---------------------------------------------------------------

echo "[6] Création utilisateur système '$APP_USER'..."
pct exec "$VMID" -- bash -c "
    id $APP_USER &>/dev/null || useradd -m -s /bin/bash $APP_USER
    mkdir -p $APP_DIR
    chown $APP_USER:$APP_USER $APP_DIR
"

echo "[6] Clé SSH deploy pour git..."
pct exec "$VMID" -- bash -c "
    su - $APP_USER -c 'ssh-keygen -t ed25519 -C deploy@strapontin -f ~/.ssh/id_ed25519 -N \"\"'
    su - $APP_USER -c 'cat ~/.ssh/id_ed25519.pub'
    echo 'Host codeberg.org' > /etc/ssh/ssh_config.d/codeberg.conf
    echo '  StrictHostKeyChecking accept-new' >> /etc/ssh/ssh_config.d/codeberg.conf
"

echo "================================================================"
echo "ACTION REQUISE : ajouter la clé ci-dessus dans"
echo "  Codeberg → Settings → Deploy Keys (lecture seule suffit)"
echo "  https://codeberg.org/Kwaite/strapontin/settings/keys"
echo "================================================================"
printf "Clé ajoutée dans Codeberg ? [o pour continuer] " > /dev/tty; read -r _confirm < /dev/tty

echo "[6] Clone du dépôt..."
pct exec "$VMID" -- bash -c "
    git config --global --add safe.directory $APP_DIR
    su - $APP_USER -c 'GIT_SSH_COMMAND=\"ssh -i ~/.ssh/id_ed25519\" git clone git@codeberg.org:Kwaite/strapontin.git $APP_DIR'
"

echo "[6] Génération du .env..."
DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
AUTH_SECRET=$(openssl rand -base64 48 | tr -d '\n')
pct exec "$VMID" -- bash -c "
    cp $APP_DIR/.env.example $APP_DIR/.env
    sed -i 's|changeme|${DB_PASSWORD}|' $APP_DIR/.env
    sed -i 's|BETTER_AUTH_SECRET=|BETTER_AUTH_SECRET=${AUTH_SECRET}|' $APP_DIR/.env
    chown $APP_USER:$APP_USER $APP_DIR/.env
    chmod 600 $APP_DIR/.env
"

echo ""
echo "================================================================"
echo "  Clés API (Entrée pour ignorer, valeur sera laissée vide)"
echo "================================================================"
printf "  TICKETMASTER_API_KEY  : " > /dev/tty; read -r TM_KEY < /dev/tty
printf "  BANDSINTOWN_APP_ID    : " > /dev/tty; read -r BST_ID < /dev/tty
printf "  RESEND_API_KEY        : " > /dev/tty; read -r RESEND_KEY < /dev/tty
echo ""

if [ -n "$TM_KEY" ] || [ -n "$BST_ID" ] || [ -n "$RESEND_KEY" ]; then
    pct exec "$VMID" -- bash -c "
        [ -n '${TM_KEY}' ]     && sed -i 's|TICKETMASTER_API_KEY=|TICKETMASTER_API_KEY=${TM_KEY}|' $APP_DIR/.env
        [ -n '${BST_ID}' ]     && sed -i 's|BANDSINTOWN_APP_ID=|BANDSINTOWN_APP_ID=${BST_ID}|' $APP_DIR/.env
        [ -n '${RESEND_KEY}' ] && sed -i 's|RESEND_API_KEY=|RESEND_API_KEY=${RESEND_KEY}|' $APP_DIR/.env
    "
fi

echo "[6] Création base PostgreSQL..."
pct exec "$VMID" -- bash -c "
    systemctl start postgresql
    su - postgres -c \"psql -c \\\"CREATE USER $DB_USER WITH PASSWORD '${DB_PASSWORD}';\\\" 2>/dev/null || true\"
    su - postgres -c \"psql -c \\\"CREATE DATABASE $DB_NAME OWNER $DB_USER;\\\" 2>/dev/null || true\"
"

# ---------------------------------------------------------------
# 7. Unit files systemd
# ---------------------------------------------------------------

echo "[7] Création des services systemd..."

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-web.service << 'UNIT'
[Unit]
Description=Strapontin — serveur web
After=network.target postgresql.service redis-server.service

[Service]
Type=simple
User=strapontin
WorkingDirectory=/opt/strapontin
EnvironmentFile=/opt/strapontin/.env
ExecStart=/usr/bin/node /opt/strapontin/apps/web/build/index.js
Restart=on-failure
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-worker.service << 'UNIT'
[Unit]
Description=Strapontin — worker BullMQ
After=network.target redis-server.service

[Service]
Type=simple
User=strapontin
WorkingDirectory=/opt/strapontin
EnvironmentFile=/opt/strapontin/.env
ExecStart=/usr/bin/node /opt/strapontin/apps/worker/dist/index.js
Restart=on-failure
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-backup.service << 'UNIT'
[Unit]
Description=Strapontin — backup quotidien

[Service]
Type=oneshot
ExecStart=/opt/strapontin/scripts/backup.sh
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-backup.timer << 'UNIT'
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

echo "[8] Protection du LXC contre la suppression accidentelle..."
pct set "$VMID" --protection 1

echo "[8] Activation des services..."
pct exec "$VMID" -- bash -c "
    systemctl daemon-reload
    systemctl enable postgresql redis-server
    systemctl enable strapontin-web strapontin-worker
    systemctl enable strapontin-backup.timer
    systemctl start strapontin-backup.timer
"

echo "[9] Premier déploiement..."
pct exec "$VMID" -- "$APP_DIR/scripts/deploy.sh"

echo ""
echo "================================================================"
echo "  Provisioning terminé — LXC $VMID ($HOSTNAME) 192.168.20.79"
echo "================================================================"
echo ""
echo "  URL : https://strapontin.kwaite.fr"
echo ""
echo "Pour supprimer ce LXC :"
echo "  pct set $VMID --protection 0 && pct destroy $VMID"
echo ""
