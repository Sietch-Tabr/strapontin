#!/bin/bash
set -euo pipefail

echo "$SSH_KEY" > /tmp/key
chmod 600 /tmp/key

SSH="ssh -i /tmp/key -o StrictHostKeyChecking=no"
SCP="scp -i /tmp/key -o StrictHostKeyChecking=no"
HOST="root@${LXC_JAVA_HOST}"

$SSH "$HOST" "mkdir -p /opt/strapontin/scripts"
$SCP scripts/backup-local.sh "$HOST":/opt/strapontin/scripts/backup-local.sh
$SSH "$HOST" "chmod +x /opt/strapontin/scripts/backup-local.sh"

echo "[backup] Backup local avant deploiement..."
$SSH "$HOST" bash /opt/strapontin/scripts/backup-local.sh
echo "[backup] Backup OK"

rm /tmp/key
