#!/usr/bin/env bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# provision-lxc-dev.sh — création et provisioning du LXC de développement
# À exécuter depuis le HOST Proxmox (192.168.1.99) en tant que root
# Idempotent : peut être relancé sans danger

# ---------------------------------------------------------------
# Paramètres
# ---------------------------------------------------------------

VMID=$(pvesh get /cluster/nextid)
HOSTNAME="strapontin-dev"
TEMPLATE_STORAGE="local"
DISK_STORAGE="local-lvm"
BRIDGE="vmbr0"
VLAN_TAG="20"
IP="192.168.20.78/24"
GW="192.168.20.1"
MAC="02:00:00:00:00:14"
MEMORY="2048"
CORES="2"
DISK="20"
APP_DIR="/opt/strapontin"
APP_USER="strapontin"
DB_NAME="strapontin"
DB_USER="strapontin"
NODE_VERSION="22"
PG_VERSION="18"
PUBLIC_BASE_URL="http://192.168.20.78:3000"

echo "=== Provisioning LXC strapontin-dev (VMID : $VMID) ==="

# ---------------------------------------------------------------
# 0. Clé SSH du développeur
# ---------------------------------------------------------------

echo ""
printf "Clé SSH publique du Mac (contenu de ~/.ssh/id_*.pub) : "
read -r DEV_SSH_KEY
echo ""

if [ -z "$DEV_SSH_KEY" ]; then
    echo "ERREUR : clé SSH requise pour accéder au LXC" >&2
    exit 1
fi

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
    --features nesting=1 \
    --ostype debian \
    --onboot 0 \
    --start 0

# ---------------------------------------------------------------
# 3. Paramètres kernel host
# ---------------------------------------------------------------

echo "[3] Paramètre réseau vm.overcommit_memory sur le host (requis par Redis)..."
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
    GETTY_OVERRIDE=/etc/systemd/system/container-getty@1.service.d/override.conf
    mkdir -p \"\$(dirname \"\$GETTY_OVERRIDE\")\"
    printf '[Service]\nExecStart=\nExecStart=-/sbin/agetty --autologin root --noclear --keep-baud tty%%I 115200,38400,9600 \$TERM\n' \
        > \"\$GETTY_OVERRIDE\"
    systemctl daemon-reload
    systemctl restart container-getty@1.service
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
        curl git gnupg2 ca-certificates lsb-release build-essential tmux
"

echo "[5] Installation Node $NODE_VERSION..."
pct exec "$VMID" -- bash -c "
    curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION}.x | bash -
    DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs
"

echo "[5] Installation pnpm..."
pct exec "$VMID" -- bash -c "NPM_CONFIG_UPDATE_NOTIFIER=false npm install -g pnpm --quiet"

echo "[5] Installation PostgreSQL $PG_VERSION..."
pct exec "$VMID" -- bash -c "
    CODENAME=\$(lsb_release -cs)
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/postgresql.gpg
    echo \"deb [signed-by=/usr/share/keyrings/postgresql.gpg] https://apt.postgresql.org/pub/repos/apt \${CODENAME}-pgdg main\" > /etc/apt/sources.list.d/pgdg.list
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql-${PG_VERSION}
"

echo "[5] Installation Redis 8..."
pct exec "$VMID" -- bash -c "
    CODENAME=\$(lsb_release -cs)
    curl -fsSL https://packages.redis.io/gpg | gpg --dearmor -o /usr/share/keyrings/redis.gpg
    echo \"deb [signed-by=/usr/share/keyrings/redis.gpg] https://packages.redis.io/deb \${CODENAME} main\" > /etc/apt/sources.list.d/redis.list
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y redis
"

echo "[5] Configuration Redis (policy noeviction)..."
pct exec "$VMID" -- bash -c "
    sed -i 's/^# maxmemory-policy noeviction/maxmemory-policy noeviction/' /etc/redis/redis.conf
"

# ---------------------------------------------------------------
# 6. Accès SSH, utilisateur, dépôt, base de données
# ---------------------------------------------------------------

echo "[6] Ajout de la clé SSH développeur..."
pct exec "$VMID" -- bash -c "
    mkdir -p /root/.ssh
    echo '$DEV_SSH_KEY' >> /root/.ssh/authorized_keys
    chmod 700 /root/.ssh
    chmod 600 /root/.ssh/authorized_keys
"

echo "[6] Création utilisateur système '$APP_USER'..."
pct exec "$VMID" -- bash -c "
    id $APP_USER &>/dev/null || useradd -m -s /bin/bash $APP_USER
    mkdir -p $APP_DIR
    chown $APP_USER:$APP_USER $APP_DIR
"

echo "[6] Clé SSH deploy pour git..."
pct exec "$VMID" -- bash -c "
    su - $APP_USER -c 'ssh-keygen -t ed25519 -C deploy@strapontin-dev -f ~/.ssh/id_ed25519 -N \"\"'
    su - $APP_USER -c 'cat ~/.ssh/id_ed25519.pub'
    echo 'Host codeberg.org' > /etc/ssh/ssh_config.d/codeberg.conf
    echo '  StrictHostKeyChecking accept-new' >> /etc/ssh/ssh_config.d/codeberg.conf
"

echo "================================================================"
echo "ACTION REQUISE : ajouter la clé ci-dessus dans"
echo "  Codeberg → Settings → Deploy Keys (lecture seule suffit)"
echo "  https://codeberg.org/Kwaite/strapontin/settings/keys"
echo "================================================================"

while true; do
    printf "Clé ajoutée ? Entrée pour tester la connexion SSH... "; read -r _confirm
    if pct exec "$VMID" -- bash -c \
        "su - $APP_USER -c 'ssh -i ~/.ssh/id_ed25519 -o StrictHostKeyChecking=accept-new git@codeberg.org 2>&1 | grep -q \"successfully authenticated\"'" \
        2>/dev/null; then
        echo "  [OK] Connexion SSH Codeberg vérifiée"
        break
    else
        echo "  [ECHEC] Connexion SSH échouée — clé non reconnue ou non ajoutée"
        echo "  Retente dans Codeberg puis appuie sur Entrée..."
    fi
done

echo "[6] Clone du dépôt..."
pct exec "$VMID" -- bash -c "
    git config --global --add safe.directory $APP_DIR
    su - $APP_USER -c 'GIT_SSH_COMMAND=\"ssh -i ~/.ssh/id_ed25519\" git clone git@codeberg.org:Kwaite/strapontin.git $APP_DIR'
"

echo "[6] Génération du .env..."
DB_PASSWORD=$(openssl rand -hex 16)
AUTH_SECRET=$(openssl rand -hex 32)
pct exec "$VMID" -- bash -c "cat > $APP_DIR/.env << 'ENV'
DATABASE_URL=postgresql://$DB_USER:$DB_PASSWORD@localhost:5432/$DB_NAME
REDIS_URL=redis://localhost:6379
BETTER_AUTH_SECRET=$AUTH_SECRET
PUBLIC_BASE_URL=$PUBLIC_BASE_URL
ENV
chown $APP_USER:$APP_USER $APP_DIR/.env
chmod 600 $APP_DIR/.env
"

echo "[6] Création base PostgreSQL..."
pct exec "$VMID" -- bash -c "
    systemctl start postgresql
    su - postgres -c \"psql -c \\\"CREATE USER $DB_USER WITH PASSWORD '${DB_PASSWORD}';\\\" 2>/dev/null || true\"
    su - postgres -c \"psql -c \\\"CREATE DATABASE $DB_NAME OWNER $DB_USER;\\\" 2>/dev/null || true\"
"

# ---------------------------------------------------------------
# 7. Services systemd
# ---------------------------------------------------------------

echo "[7] Création des services systemd dev..."

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-dev-web.service << 'UNIT'
[Unit]
Description=Strapontin — dev server web (HMR)
After=network.target postgresql.service redis-server.service

[Service]
Type=simple
User=strapontin
WorkingDirectory=/opt/strapontin
EnvironmentFile=/opt/strapontin/.env
ExecStart=/bin/pnpm dev
Restart=on-failure
RestartSec=5
TimeoutStopSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-dev-worker.service << 'UNIT'
[Unit]
Description=Strapontin — dev worker (tsx watch)
After=network.target redis-server.service

[Service]
Type=simple
User=strapontin
WorkingDirectory=/opt/strapontin
EnvironmentFile=/opt/strapontin/.env
ExecStart=/bin/pnpm worker:dev
Restart=on-failure
RestartSec=5
TimeoutStopSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT"

echo "[7] Activation des services..."
pct exec "$VMID" -- bash -c "
    systemctl daemon-reload
    systemctl mask systemd-networkd-wait-online.service
    systemctl enable postgresql redis-server strapontin-dev-web strapontin-dev-worker
"

# ---------------------------------------------------------------
# 8. Protection + vérifications
# ---------------------------------------------------------------

echo "[8] Protection du LXC contre la suppression accidentelle..."
pct set "$VMID" --protection 1

echo "[8] Vérifications..."
CHECKS_OK=1

LIBRE=$(pct exec "$VMID" -- df /opt --output=avail -m | tail -1 | tr -d ' ')
if [ "$LIBRE" -lt 2048 ]; then
    echo "  [ECHEC] Espace disque : ${LIBRE} Mo (minimum 2048)"
    CHECKS_OK=0
else
    echo "  [OK]    Espace disque : ${LIBRE} Mo"
fi

if pct exec "$VMID" -- bash -c "command -v pnpm > /dev/null 2>&1"; then
    echo "  [OK]    pnpm disponible"
else
    echo "  [ECHEC] pnpm introuvable"
    CHECKS_OK=0
fi

for VAR in DATABASE_URL REDIS_URL BETTER_AUTH_SECRET PUBLIC_BASE_URL; do
    VAL=$(pct exec "$VMID" -- bash -c "source $APP_DIR/.env 2>/dev/null && echo \${$VAR}" 2>/dev/null || true)
    if [ -z "$VAL" ]; then
        echo "  [ECHEC] $VAR vide dans .env"
        CHECKS_OK=0
    else
        echo "  [OK]    $VAR défini"
    fi
done

if pct exec "$VMID" -- pg_isready -q 2>/dev/null; then
    echo "  [OK]    PostgreSQL actif"
else
    echo "  [ECHEC] PostgreSQL inactif"
    CHECKS_OK=0
fi

REDIS_PONG=$(pct exec "$VMID" -- redis-cli ping 2>/dev/null || true)
if [ "$REDIS_PONG" = "PONG" ]; then
    echo "  [OK]    Redis accessible"
else
    echo "  [ECHEC] Redis inaccessible"
    CHECKS_OK=0
fi

if [ "$CHECKS_OK" -eq 0 ]; then
    echo ""
    echo "ERREUR : vérifications échouées — corriger les erreurs ci-dessus" >&2
    exit 1
fi

# ---------------------------------------------------------------
# 9. Installation des dépendances + migrations
# ---------------------------------------------------------------

echo "[9] Installation des dépendances..."
pct exec "$VMID" -- bash -c "
    su - $APP_USER -c 'cd $APP_DIR && pnpm install --frozen-lockfile'
"

echo "[9] Migrations..."
pct exec "$VMID" -- bash -c "
    su - $APP_USER -c 'set -a; source $APP_DIR/.env; set +a; cd $APP_DIR && pnpm --filter @strapontin/core db:migrate'
"

echo "[9] Démarrage des services..."
pct exec "$VMID" -- bash -c "
    systemctl start postgresql redis-server
    systemctl start strapontin-dev-web strapontin-dev-worker
"

echo "[9] Healthcheck..."
RETRIES=20
until pct exec "$VMID" -- curl -sf http://localhost:3000/health > /dev/null 2>&1 || [ "$RETRIES" -eq 0 ]; do
    sleep 3
    RETRIES=$((RETRIES - 1))
done
if [ "$RETRIES" -eq 0 ]; then
    echo "  [ECHEC] Le dev server ne répond pas sur le port 3000" >&2
    pct exec "$VMID" -- systemctl status strapontin-dev-web --no-pager >&2
    exit 1
fi
echo "  [OK]    Dev server opérationnel"

echo ""
echo "================================================================"
echo "  Provisioning terminé — LXC $VMID ($HOSTNAME) ${IP%%/*}"
echo "================================================================"
echo ""
echo "  Application : http://192.168.20.78:3000"
echo "  Premier login : http://192.168.20.78:3000/setup"
echo "  SSH          : ssh root@192.168.20.78"
echo ""
echo "Pour supprimer ce LXC :"
echo "  pct set $VMID --protection 0 && pct stop $VMID && pct destroy $VMID"
echo ""
