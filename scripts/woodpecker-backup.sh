#!/bin/bash
set -euo pipefail

echo "$SSH_KEY" > /tmp/key
chmod 600 /tmp/key
trap 'rm -f /tmp/key' EXIT

SSH="ssh -i /tmp/key -o StrictHostKeyChecking=no"
SCP="scp -i /tmp/key -o StrictHostKeyChecking=no"
HOST="root@${LXC_JAVA_HOST}"

. scripts/deposer-exploitation.sh
deposer_exploitation

echo "[backup] Backup local avant deploiement..."
$SSH "$HOST" bash /opt/strapontin/exploitation/backup-local.sh
echo "[backup] Backup OK"
