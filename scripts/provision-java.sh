#!/bin/bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# provision-java.sh — provisionnement LXC "vert" (stack Java/Spring Boot)
# À exécuter depuis le HOST Proxmox en tant que root :
#   scp scripts/provision-java.sh root@192.168.1.99:/tmp/
#   ssh root@192.168.1.99 "bash /tmp/provision-java.sh"
#
# Ce LXC est le "vert" de la stratégie blue-green pour la migration v2.0.0.
# À la bascule prod : la MAC de LXC 113 est assignée à ce LXC,
# qui hérite alors de l'IP 192.168.20.79 via la réservation DHCP.
#
# Pour redéployer proprement : détruire l'ancien LXC d'abord :
#   pct set <VMID> --protection 0
#   pct stop <VMID> && pct destroy <VMID> --destroy-unreferenced-disks 1 --purge 1
#   puis relancer ce script.

# ---------------------------------------------------------------
# Paramètres
# ---------------------------------------------------------------

VMID=$(pvesh get /cluster/nextid)
HOSTNAME="strapontin-java"
IP="192.168.20.80/24"
GW="192.168.20.1"
BRIDGE="vmbr0"
VLAN_TAG="20"
TEMPLATE_STORAGE="local"
DISK_STORAGE="local-lvm"
DISK="20"
MEMORY="2048"
SWAP="512"
CORES="2"
APP_DIR="/opt/strapontin"
APP_USER="strapontin"
DB_NAME="strapontin"
JAVA_VERSION="25"
PG_VERSION="18"

echo "=== Provisionnement LXC $VMID ($HOSTNAME) — stack Java v2.0.0 ==="

# ---------------------------------------------------------------
# Clé SSH développeur
# ---------------------------------------------------------------

printf "Clé SSH publique du Mac (contenu de ~/.ssh/id_*.pub) : "
read -r DEV_SSH_KEY
echo ""

if [ -z "$DEV_SSH_KEY" ]; then
    echo "ERREUR : clé SSH requise" >&2
    exit 1
fi

DB_PASSWORD=$(openssl rand -hex 16)

printf "BETTER_AUTH_SECRET de prod (LXC 113 — /opt/strapontin/.env) : "
read -r APP_CONFIG_SECRET
echo ""

if [ -z "$APP_CONFIG_SECRET" ]; then
    echo "ERREUR : APP_CONFIG_SECRET requis (chiffrement app_config)" >&2
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
    --swap "$SWAP" \
    --rootfs "$DISK_STORAGE:$DISK" \
    --net0 "name=eth0,bridge=$BRIDGE,tag=$VLAN_TAG,ip=$IP,gw=$GW" \
    --unprivileged 1 \
    --features nesting=1 \
    --ostype debian \
    --onboot 1 \
    --start 0

echo "[2] Montage NAS /mnt/backup..."
pct set "$VMID" --mp0 "/mnt/synology/backup/Strapontin,mp=/mnt/backup"

echo "[2] Protection contre la suppression accidentelle..."
pct set "$VMID" --protection 1

# ---------------------------------------------------------------
# 3. Démarrage
# ---------------------------------------------------------------

echo "[3] Démarrage du LXC..."
pct start "$VMID"
sleep 5

# ---------------------------------------------------------------
# 4. Système de base
# ---------------------------------------------------------------

echo "[4] Locale, fuseau horaire, journald..."
pct exec "$VMID" -- bash -c "
    export LC_ALL=C LANG=C DEBIAN_FRONTEND=noninteractive
    echo 'fr_FR.UTF-8 UTF-8' >> /etc/locale.gen
    echo 'en_US.UTF-8 UTF-8' >> /etc/locale.gen
    locale-gen
    update-locale LANG=fr_FR.UTF-8 LC_ALL=fr_FR.UTF-8
    ln -sf /usr/share/zoneinfo/Europe/Paris /etc/localtime
    echo 'Europe/Paris' > /etc/timezone
    mkdir -p /etc/systemd/journald.conf.d
    printf '[Journal]\nSystemMaxUse=100M\n' > /etc/systemd/journald.conf.d/strapontin.conf
    systemctl restart systemd-journald
    printf 'LANG=fr_FR.UTF-8\nLC_ALL=fr_FR.UTF-8\n' > /etc/environment
"

echo "[4] Autologin root sur console Proxmox..."
pct exec "$VMID" -- bash -c "
    OVERRIDE=/etc/systemd/system/container-getty@1.service.d/override.conf
    mkdir -p \"\$(dirname \"\$OVERRIDE\")\"
    printf '[Service]\nExecStart=\nExecStart=-/sbin/agetty --autologin root --noclear --keep-baud tty%%I 115200,38400,9600 \$TERM\n' > \"\$OVERRIDE\"
    systemctl daemon-reload
"

echo "[4] Dépendances de base..."
pct exec "$VMID" -- bash -c "
    export LC_ALL=C LANG=C DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get upgrade -y -qq
    apt-get install -y \
        curl wget gnupg2 ca-certificates lsb-release \
        unattended-upgrades
"

echo "[4] Nettoyage systemd (Debian 13 LXC)..."
pct exec "$VMID" -- bash -c "
    systemctl mask ssh.socket
    systemctl mask systemd-networkd systemd-networkd.socket
"

echo "[4] Mises à jour automatiques (sécurité uniquement)..."
pct exec "$VMID" -- bash -c "
    printf 'APT::Periodic::Update-Package-Lists \"1\";\nAPT::Periodic::Unattended-Upgrade \"1\";\nAPT::Periodic::AutocleanInterval \"7\";\n' \
        > /etc/apt/apt.conf.d/20auto-upgrades
    cat > /etc/apt/apt.conf.d/51strapontin-upgrades << 'APT_CONF'
Unattended-Upgrade::Allowed-Origins {
    \"\${distro_id}:\${distro_codename}-security\";
};
Unattended-Upgrade::Package-Blacklist {
    \"postgresql-*\";
    \"temurin-*\";
};
Unattended-Upgrade::Automatic-Reboot \"false\";
APT_CONF
"

# ---------------------------------------------------------------
# 5. Java 25 (Adoptium Temurin)
# ---------------------------------------------------------------

echo "[5] Installation Java $JAVA_VERSION (Adoptium Temurin)..."
pct exec "$VMID" -- bash -c "
    export LC_ALL=C LANG=C DEBIAN_FRONTEND=noninteractive
    wget -qO /usr/share/keyrings/adoptium.asc \
        https://packages.adoptium.net/artifactory/api/gpg/key/public
    CODENAME=\$(lsb_release -cs)
    echo \"deb [signed-by=/usr/share/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb \${CODENAME} main\" \
        > /etc/apt/sources.list.d/adoptium.list
    apt-get update -qq
    apt-get install -y temurin-${JAVA_VERSION}-jdk
    java -version
"

# ---------------------------------------------------------------
# 6. PostgreSQL 18
# ---------------------------------------------------------------

echo "[6] Installation PostgreSQL $PG_VERSION..."
pct exec "$VMID" -- bash -c "
    export LC_ALL=C LANG=C DEBIAN_FRONTEND=noninteractive
    CODENAME=\$(lsb_release -cs)
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
        | gpg --dearmor -o /usr/share/keyrings/postgresql.gpg
    echo \"deb [signed-by=/usr/share/keyrings/postgresql.gpg] https://apt.postgresql.org/pub/repos/apt \${CODENAME}-pgdg main\" \
        > /etc/apt/sources.list.d/pgdg.list
    apt-get update -qq
    apt-get install -y postgresql-${PG_VERSION}
    systemctl enable postgresql
"

echo "[6] Création de la base $DB_NAME + extensions..."
pct exec "$VMID" -- bash -c "
    systemctl start postgresql
    sleep 2
    su - postgres -c \"psql -c \\\"CREATE USER $APP_USER WITH PASSWORD '$DB_PASSWORD';\\\" 2>/dev/null || true\"
    su - postgres -c \"psql -c \\\"CREATE DATABASE $DB_NAME OWNER $APP_USER;\\\" 2>/dev/null || true\"
    su - postgres -c \"psql -d $DB_NAME -c 'CREATE EXTENSION IF NOT EXISTS dblink;'\"
    su - postgres -c \"psql -d $DB_NAME -c 'CREATE EXTENSION IF NOT EXISTS unaccent;'\"
    su - postgres -c \"psql -d $DB_NAME -c '\dx'\"
"

echo "[6] Configuration pg_hba.conf..."
pct exec "$VMID" -- bash -c "
    PG_HBA=/etc/postgresql/${PG_VERSION}/main/pg_hba.conf
    echo 'host  $DB_NAME  $APP_USER  127.0.0.1/32  scram-sha-256' >> \$PG_HBA
    systemctl reload postgresql
"

# ---------------------------------------------------------------
# 7. Utilisateur applicatif + répertoires
# ---------------------------------------------------------------

echo "[7] Utilisateur '$APP_USER' et répertoires..."
pct exec "$VMID" -- bash -c "
    id $APP_USER &>/dev/null || useradd -m -s /bin/bash $APP_USER
    mkdir -p $APP_DIR/logs
    chown -R $APP_USER:$APP_USER $APP_DIR
"

echo "[7] Fichier de configuration .env..."
pct exec "$VMID" -- bash -c "cat > $APP_DIR/.env << 'ENVFILE'
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/$DB_NAME
SPRING_DATASOURCE_USERNAME=$APP_USER
SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD
APP_CONFIG_SECRET=$APP_CONFIG_SECRET
ENVFILE
chown $APP_USER:$APP_USER $APP_DIR/.env
chmod 600 $APP_DIR/.env
"

echo "[7] Services systemd..."
pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-worker.service << 'UNIT'
[Unit]
Description=Strapontin — worker Quartz
After=network.target postgresql.service

[Service]
Type=simple
User=$APP_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
ExecStart=/usr/bin/java -jar $APP_DIR/worker.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-web.service << 'UNIT'
[Unit]
Description=Strapontin — API REST
After=network.target postgresql.service

[Service]
Type=simple
User=$APP_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
ExecStart=/usr/bin/java -jar $APP_DIR/web.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT"

pct exec "$VMID" -- bash -c "systemctl daemon-reload"

# ---------------------------------------------------------------
# 8. SSH développeur
# ---------------------------------------------------------------

echo "[8] Clé SSH développeur..."
pct exec "$VMID" -- bash -c "
    mkdir -p /root/.ssh
    echo '$DEV_SSH_KEY' >> /root/.ssh/authorized_keys
    chmod 700 /root/.ssh
    chmod 600 /root/.ssh/authorized_keys
"

# ---------------------------------------------------------------
# 9. Vérifications finales
# ---------------------------------------------------------------

echo "[9] Vérifications..."
pct exec "$VMID" -- bash -c "
    echo '--- Java ---'
    java -version 2>&1
    echo '--- PostgreSQL ---'
    su -c \"psql -d $DB_NAME -c 'SELECT version();'\" postgres
    su -c \"psql -d $DB_NAME -c 'SELECT extname FROM pg_extension ORDER BY extname;'\" postgres
"

echo ""
echo "================================================================"
echo "LXC $VMID provisionné avec succès."
echo ""
echo "CREDENTIALS GÉNÉRÉS (à conserver) :"
echo "  DB_PASSWORD        : $DB_PASSWORD"
echo "  APP_CONFIG_SECRET  : (valeur BETTER_AUTH_SECRET de LXC 113)"
echo "  Fichier           : $APP_DIR/.env (chmod 600)"
echo ""
echo "ÉTAPES SUIVANTES :"
echo ""
echo "1. Déployer et démarrer le worker Spring Boot."
echo "   Le schéma sera appliqué automatiquement par Flyway au premier démarrage."
echo ""
echo "2. Autoriser ce LXC à lire la DB de LXC 113 (pour migrate_data.sql) :"
echo "   Sur LXC 113 : echo 'host strapontin postgres 192.168.20.80/32 trust' >> /etc/postgresql/18/main/pg_hba.conf"
echo "   Sur LXC 113 : systemctl reload postgresql"
echo ""
echo "3. Tester le dblink depuis ce LXC :"
echo "   pct exec $VMID -- su -c \"psql -d $DB_NAME -c \\\"SELECT * FROM dblink('host=192.168.20.79 dbname=strapontin user=postgres', 'SELECT 1') AS t(x int);\\\"\" postgres"
echo ""
echo "4. À la bascule prod — noter la MAC de LXC 113 :"
echo "   pct config 113 | grep hwaddr"
echo "   (sera assignée à LXC $VMID lors du swap)"
echo "================================================================"
