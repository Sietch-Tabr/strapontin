#!/bin/bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# provision.sh — provisionnement LXC (stack Java/Spring Boot)
# Usage :
#   bash /tmp/provision.sh dev   → LXC dev (SPRING_PROFILES_ACTIVE=dev, IP 192.168.20.78)
#   bash /tmp/provision.sh prod  → LXC prod (IP 192.168.20.79)
#
# À exécuter depuis le HOST Proxmox en tant que root :
#   scp scripts/provision.sh scripts/update.sh root@192.168.1.99:/tmp/
#   ssh root@192.168.1.99 "bash /tmp/provision.sh dev|prod"
#
# Pour redéployer proprement : détruire l'ancien LXC d'abord :
#   pct set <VMID> --protection 0
#   pct stop <VMID> && pct destroy <VMID> --destroy-unreferenced-disks 1 --purge 1
#   puis relancer ce script.

# ---------------------------------------------------------------
# Paramètres
# ---------------------------------------------------------------

PROFIL="${1:-}"
if [ "$PROFIL" != "dev" ] && [ "$PROFIL" != "prod" ]; then
    echo "Usage : $0 dev|prod" >&2
    exit 1
fi

VMID=$(pvesh get /cluster/nextid)
if [ "$PROFIL" = "dev" ]; then
    HOSTNAME="strapontin-dev"
    IP="192.168.20.78/24"
    MAC="02:00:00:00:00:14"
else
    HOSTNAME="strapontin"
    IP="192.168.20.79/24"
    MAC="02:00:00:00:00:13"
fi
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

printf "APP_CONFIG_SECRET (laisser vide pour en générer un nouveau) : "
read -r APP_CONFIG_SECRET
echo ""

if [ -z "$APP_CONFIG_SECRET" ]; then
    APP_CONFIG_SECRET=$(openssl rand -hex 32)
    echo "APP_CONFIG_SECRET généré automatiquement : $APP_CONFIG_SECRET"
    echo "  --> Conservez cette valeur pour restaurer les app_config chiffrées."
fi

# Secret partagé protégeant l'endpoint interne /api/interne/backup-statut.
BACKUP_INTERNAL_SECRET=$(openssl rand -hex 32)

# Secret partagé protégeant l'endpoint interne /api/interne/integrite-statut (#976).
INTEGRITY_INTERNAL_SECRET=$(openssl rand -hex 32)

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
    --net0 "name=eth0,bridge=$BRIDGE,tag=$VLAN_TAG,hwaddr=$MAC,ip=$IP,gw=$GW" \
    --unprivileged 1 \
    --features nesting=1 \
    --ostype debian \
    --onboot "$([ "$PROFIL" = "prod" ] && echo 1 || echo 0)" \
    --start 0

if [ "$PROFIL" = "prod" ]; then
    echo "[2] Montage NAS /mnt/backup..."
    pct set "$VMID" --mp0 "/mnt/synology/backup/Strapontin,mp=/mnt/backup"
fi

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

echo "[6] Tuning postgresql.conf..."
pct exec "$VMID" -- bash -c "
    PG_CONF=/etc/postgresql/${PG_VERSION}/main/postgresql.conf
    sed -i 's/^#*shared_buffers\s*=.*/shared_buffers = 400MB/' \$PG_CONF
    sed -i 's/^#*work_mem\s*=.*/work_mem = 16MB/' \$PG_CONF
    sed -i 's/^#*effective_cache_size\s*=.*/effective_cache_size = 2GB/' \$PG_CONF
    sed -i 's/^#*statement_timeout\s*=.*/statement_timeout = 30s/' \$PG_CONF
    sed -i 's/^#*idle_in_transaction_session_timeout\s*=.*/idle_in_transaction_session_timeout = 30s/' \$PG_CONF
    systemctl restart postgresql
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

echo "[7] Scripts d'exploitation..."
pct exec "$VMID" -- mkdir -p "$APP_DIR/scripts"
pct push "$VMID" "$(dirname "$0")/update.sh"        "$APP_DIR/scripts/update.sh"
pct push "$VMID" "$(dirname "$0")/backup.sh"        "$APP_DIR/scripts/backup.sh"
pct push "$VMID" "$(dirname "$0")/backup-local.sh"  "$APP_DIR/scripts/backup-local.sh"
pct push "$VMID" "$(dirname "$0")/restore.sh"       "$APP_DIR/scripts/restore.sh"
pct push "$VMID" "$(dirname "$0")/aide-check.sh"    "$APP_DIR/scripts/aide-check.sh"
pct exec "$VMID" -- chmod +x \
    "$APP_DIR/scripts/update.sh" \
    "$APP_DIR/scripts/backup.sh" \
    "$APP_DIR/scripts/backup-local.sh" \
    "$APP_DIR/scripts/restore.sh" \
    "$APP_DIR/scripts/aide-check.sh"

echo "[7] Fichier de configuration .env..."
if [ "$PROFIL" = "dev" ]; then
pct exec "$VMID" -- bash -c "cat > $APP_DIR/.env << 'ENVFILE'
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/$DB_NAME
SPRING_DATASOURCE_USERNAME=$APP_USER
SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD
APP_CONFIG_SECRET=$APP_CONFIG_SECRET
BACKUP_INTERNAL_SECRET=$BACKUP_INTERNAL_SECRET
INTEGRITY_INTERNAL_SECRET=$INTEGRITY_INTERNAL_SECRET
SPRING_PROFILES_ACTIVE=dev
ENVFILE
chown $APP_USER:$APP_USER $APP_DIR/.env
chmod 600 $APP_DIR/.env
"
else
pct exec "$VMID" -- bash -c "cat > $APP_DIR/.env << 'ENVFILE'
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/$DB_NAME
SPRING_DATASOURCE_USERNAME=$APP_USER
SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD
APP_CONFIG_SECRET=$APP_CONFIG_SECRET
BACKUP_INTERNAL_SECRET=$BACKUP_INTERNAL_SECRET
INTEGRITY_INTERNAL_SECRET=$INTEGRITY_INTERNAL_SECRET
SPRING_PROFILES_ACTIVE=prod
ENVFILE
chown $APP_USER:$APP_USER $APP_DIR/.env
chmod 600 $APP_DIR/.env
"
fi

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
ExecStart=/usr/bin/java -Xms64m -Xmx384m -XX:+UseZGC -jar $APP_DIR/worker.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
# #886 : laisse le temps à wait-for-jobs-to-complete-on-shutdown (application.properties) de
# terminer un cycle fetch en cours plutôt que de le tuer silencieusement lors d'un déploiement.
TimeoutStopSec=90
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
ExecStart=/usr/bin/java -Xms128m -Xmx768m -XX:+UseZGC -jar $APP_DIR/web.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-backup.service << 'UNIT'
[Unit]
Description=Strapontin — backup PostgreSQL vers NAS
After=network.target postgresql.service

[Service]
Type=oneshot
User=root
ExecStart=/opt/strapontin/scripts/backup.sh
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-backup.timer << 'UNIT'
[Unit]
Description=Backup quotidien Strapontin
After=network.target

[Timer]
OnCalendar=daily
Persistent=true

[Install]
WantedBy=timers.target
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-backup-local.service << 'UNIT'
[Unit]
Description=Strapontin — backup local (dump + .env + logs)
After=network.target postgresql.service

[Service]
Type=oneshot
User=root
ExecStart=/opt/strapontin/scripts/backup-local.sh
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-backup-local.timer << 'UNIT'
[Unit]
Description=Backup local quotidien Strapontin
After=network.target

[Timer]
OnCalendar=*-*-* 01:00:00
Persistent=true

[Install]
WantedBy=timers.target
UNIT"

echo "[7] Contrôle d'intégrité AIDE (#976)..."
# Le paquet Debian aide embarque son propre timer (dailyaidecheck.timer, scan complet de l'OS +
# alerte par mail local root, jamais lu sur cette infra) et l'active automatiquement à
# l'installation — désactivé au profit du timer/périmètre dédiés ci-dessous (redondant sinon,
# deux mécanismes de contrôle d'intégrité indépendants sur la même base AIDE).
pct exec "$VMID" -- bash -c "
    export LC_ALL=C LANG=C DEBIAN_FRONTEND=noninteractive
    apt-get install -y -qq aide
    systemctl disable --now dailyaidecheck.timer
    systemctl mask dailyaidecheck.timer
"
pct exec "$VMID" -- bash -c "mkdir -p /etc/aide /var/lib/aide
cat > /etc/aide/aide.conf << 'UNIT'
# Strapontin — périmètre restreint (pas de scan complet de l'OS, cf. #976) :
# JARs, .env, scripts d'exploitation, units systemd web/worker (vecteur de persistance classique).
# database (sans suffixe) supprimé en AIDE v0.19 — database_in/database_out obligatoires.
database_in=file:/var/lib/aide/aide.db
database_out=file:/var/lib/aide/aide.db.new
gzip_dbout=no

Checksums = p+i+n+u+g+s+m+c+sha256

/opt/strapontin/web.jar Checksums
/opt/strapontin/worker.jar Checksums
/opt/strapontin/.env Checksums
/opt/strapontin/scripts Checksums
/etc/systemd/system/strapontin-web.service Checksums
/etc/systemd/system/strapontin-worker.service Checksums
UNIT
aide --config=/etc/aide/aide.conf --init
mv /var/lib/aide/aide.db.new /var/lib/aide/aide.db
"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-integrity.service << 'UNIT'
[Unit]
Description=Strapontin — contrôle d'intégrité AIDE
After=network.target strapontin-web.service

[Service]
Type=oneshot
User=root
ExecStart=/opt/strapontin/scripts/aide-check.sh
UNIT"

pct exec "$VMID" -- bash -c "cat > /etc/systemd/system/strapontin-integrity.timer << 'UNIT'
[Unit]
Description=Contrôle d'intégrité AIDE quotidien Strapontin

[Timer]
OnCalendar=*-*-* 04:00:00
Persistent=true

[Install]
WantedBy=timers.target
UNIT"

pct exec "$VMID" -- bash -c "systemctl daemon-reload && systemctl enable strapontin-web strapontin-worker strapontin-backup.timer strapontin-backup-local.timer strapontin-integrity.timer && systemctl start strapontin-backup.timer strapontin-backup-local.timer strapontin-integrity.timer"

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
# 9. Clé SSH Woodpecker (prod uniquement)
# ---------------------------------------------------------------

WOODPECKER_PRIVKEY=""
if [ "$PROFIL" = "prod" ]; then
    echo "[9] Génération clé SSH Woodpecker..."
    pct exec "$VMID" -- bash -c "
        mkdir -p /root/.ssh
        ssh-keygen -t ed25519 -C 'woodpecker@strapontin' -f /root/.ssh/woodpecker_strapontin -N ''
        cat /root/.ssh/woodpecker_strapontin.pub >> /root/.ssh/authorized_keys
        chmod 700 /root/.ssh
        chmod 600 /root/.ssh/authorized_keys
    "
    WOODPECKER_PRIVKEY=$(pct exec "$VMID" -- cat /root/.ssh/woodpecker_strapontin)
fi

# ---------------------------------------------------------------
# 10. Vérifications finales
# ---------------------------------------------------------------

echo "[10] Vérifications..."
pct exec "$VMID" -- bash -c "
    echo '--- Java ---'
    java -version 2>&1
    echo '--- PostgreSQL ---'
    su -c \"psql -d $DB_NAME -c 'SELECT version();'\" postgres
    su -c \"psql -d $DB_NAME -c 'SELECT extname FROM pg_extension ORDER BY extname;'\" postgres
"

LIBRE=$(pct exec "$VMID" -- df /opt --output=avail -m | tail -1 | tr -d ' ')
if [ "$LIBRE" -lt 2048 ]; then
    echo "  [ECHEC] Espace disque insuffisant : ${LIBRE} Mo (minimum 2048)" >&2
    exit 1
else
    echo "  [OK]    Espace disque : ${LIBRE} Mo"
fi

if [ "$PROFIL" = "prod" ]; then
    if pct exec "$VMID" -- mountpoint -q /mnt/backup 2>/dev/null; then
        echo "  [OK]    NAS /mnt/backup monté"
    else
        echo "  [ECHEC] NAS /mnt/backup non monté" >&2
        exit 1
    fi
fi

echo ""
echo "================================================================"
echo "  LXC $VMID ($PROFIL) provisionné avec succès."
echo ""
echo "  DB_PASSWORD  : $DB_PASSWORD"
echo "  .env         : $APP_DIR/.env (chmod 600)"
echo ""
echo "  Étapes suivantes :"
echo "  1. cd front && npm run build && cd .."
echo "     mvn -f java/pom.xml -pl web,worker -am package"
echo "  2. scp \"\$(ls -t java/web/target/web-*.jar | grep -v '.original' | head -1)\" root@${IP%%/*}:$APP_DIR/web.jar"
echo "     scp \"\$(ls -t java/worker/target/worker-*.jar | grep -v '.original' | head -1)\" root@${IP%%/*}:$APP_DIR/worker.jar"
echo "  3. ssh root@${IP%%/*} '$APP_DIR/scripts/update.sh'"
echo "  4. Ouvrir http://${IP%%/*}:8080/setup"
if [ "$PROFIL" = "prod" ]; then
echo ""
echo "  Secrets Woodpecker à configurer :"
echo "    lxc_java_host = ${IP%%/*}"
echo "    ssh_key       ="
echo "$WOODPECKER_PRIVKEY"
fi
echo "================================================================"
