#!/usr/bin/env bash
set -euo pipefail

# aide-check.sh — contrôle d'intégrité quotidien (AIDE) des fichiers déployés (#976)
# Usage : bash /opt/strapontin/exploitation/aide-check.sh
# À exécuter en tant que root depuis le LXC (timer strapontin-integrity.timer).
# Périmètre surveillé : voir aide.conf (application/, exploitation/, /etc/opt/strapontin et unités
# systemd de l'application). La logique est dans le JAR exploitation-controle (commande aide-check).

. "$(dirname "$0")/chemins.sh"
lancer_controle aide-check "$@"
