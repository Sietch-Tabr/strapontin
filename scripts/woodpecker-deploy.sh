#!/bin/bash
set -euo pipefail

trap 'rm -f /tmp/key' EXIT

echo "$SSH_KEY" > /tmp/key
chmod 600 /tmp/key

SSH="ssh -i /tmp/key -o StrictHostKeyChecking=no"
SCP="scp -i /tmp/key -o StrictHostKeyChecking=no"
HOST="root@${LXC_JAVA_HOST}"

$SSH "$HOST" "mkdir -p /opt/strapontin/scripts"
$SCP scripts/backup.sh scripts/restore.sh scripts/update.sh scripts/rotation-secrets.sh "$HOST":/opt/strapontin/scripts/
$SSH "$HOST" "chmod +x /opt/strapontin/scripts/backup.sh /opt/strapontin/scripts/restore.sh /opt/strapontin/scripts/update.sh /opt/strapontin/scripts/rotation-secrets.sh"

echo "[deploy] Backup NAS avant deploiement..."
$SSH "$HOST" /opt/strapontin/scripts/backup.sh || echo "[deploy] Backup NAS ignoré (NAS non monté)"
echo "[deploy] Backup NAS OK"

WEB_JAR=$(find java/web/target -maxdepth 1 -name "web-*.jar" -not -name "*.original" -type f)
WORKER_JAR=$(find java/worker/target -maxdepth 1 -name "worker-*.jar" -not -name "*.original" -type f)

echo "[deploy] JAR web    : ${WEB_JAR}"
echo "[deploy] JAR worker : ${WORKER_JAR}"

if [[ -z "$WEB_JAR" || ! -f "$WEB_JAR" ]]; then
  echo "JAR web introuvable dans java/web/target/" >&2
  exit 1
fi
if [[ -z "$WORKER_JAR" || ! -f "$WORKER_JAR" ]]; then
  echo "JAR worker introuvable dans java/worker/target/" >&2
  exit 1
fi

$SCP "$WEB_JAR"    "$HOST":/opt/strapontin/web.jar.new
$SCP "$WORKER_JAR" "$HOST":/opt/strapontin/worker.jar.new
$SSH "$HOST" /opt/strapontin/scripts/update.sh
