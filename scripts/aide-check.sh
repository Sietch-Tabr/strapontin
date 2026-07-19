#!/usr/bin/env bash
set -euo pipefail

# aide-check.sh — contrôle d'intégrité quotidien (AIDE) des fichiers déployés (#976)
# Usage : bash /opt/strapontin/scripts/aide-check.sh
# À exécuter en tant que root depuis le LXC (timer strapontin-integrity.timer).
# Périmètre surveillé : /opt/strapontin/{web.jar,worker.jar,.env,scripts/} + units systemd
# strapontin-web.service/strapontin-worker.service (voir /etc/aide/aide.conf).

APP_DIR="/opt/strapontin"

INTEGRITY_SECRET=""
if [ -f "$APP_DIR/.env" ]; then
    INTEGRITY_SECRET=$(grep '^INTEGRITY_INTERNAL_SECRET=' "$APP_DIR/.env" | cut -d'=' -f2- || true)
fi

_appel_statut() {
    curl -sf -X POST \
        -H "Authorization: Bearer $INTEGRITY_SECRET" \
        --data-urlencode "statut=$1" \
        --data-urlencode "detail=${2:-}" \
        "http://localhost:8080/api/interne/integrite-statut" \
        >/dev/null 2>&1 || true
}

SORTIE=$(aide --config=/etc/aide/aide.conf --check 2>&1) || CODE=$?
CODE="${CODE:-0}"

# Codes de sortie AIDE : 0 = aucun changement, 1 = fichiers ajoutés, 2 = supprimés, 4 = modifiés
# (cumulables par addition, ex. 6 = supprimés + modifiés). Tout code != 0 signale une différence.
if [ "$CODE" -eq 0 ]; then
    _appel_statut "ok" ""
    echo "aide-check : aucune modification détectée"
else
    # Le regex précédent (^(added|removed|changed)) ne matchait jamais la sortie réelle d'AIDE
    # (en-têtes de section capitalisés "Added entries:", lignes de fichier au format
    # "f++++++++++++++++++: /chemin", jamais en minuscule en début de ligne) — DETAIL restait
    # toujours vide, l'email d'alerte n'indiquait jamais quel fichier était concerné.
    DETAIL=$(echo "$SORTIE" | grep -E "entries:|: /opt/strapontin|: /etc/systemd" | head -30 || true)
    _appel_statut "modifie" "$DETAIL"
    echo "aide-check : modification détectée (code $CODE)" >&2
    echo "$SORTIE" >&2
fi
