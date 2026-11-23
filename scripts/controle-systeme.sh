#!/bin/bash
set -euo pipefail

# controle-systeme.sh — contrôle quotidien du système du LXC (timer strapontin-controle-systeme) :
# réglages PostgreSQL, accès réseau (sshd, fail2ban, nftables) et retard des mises à jour du
# système, de PostgreSQL et de Java. Un écart est signalé à l'application (endpoint interne du
# contrôle d'intégrité), qui le journalise en erreur et alerte l'administrateur ; sans écart,
# rien n'est envoyé.
# Usage (root) : bash /opt/strapontin/exploitation/controle-systeme.sh

. "$(dirname "$0")/chemins.sh"

RETARD_MAX_JOURS="${RETARD_MAX_JOURS:-14}"
ETAT_RETARD="${ETAT_RETARD:-$ETAT_DIR/retard-systeme}"
EXPLOITATION="$(cd "$(dirname "$0")" && pwd)"

detail=""
ajouter_ecarts() {
    detail+="$1"$'\n'
}

# Sortie d'un contrôle : seules les lignes d'écart sont retenues.
controler() {
    local intitule="$1" sortie
    shift
    if ! sortie="$("$@" 2>&1)"; then
        ajouter_ecarts "$intitule :"
        ajouter_ecarts "$(printf '%s\n' "$sortie" | grep '^\[ECART\]' || printf '%s\n' "$sortie" | tail -n 3)"
    fi
}

# Paquets en attente (apt-get -s ne modifie rien) et ancienneté de la première constatation.
controler_retard() {
    local en_attente maintenant depuis jours
    en_attente="$(apt-get -s upgrade 2> /dev/null | grep -c '^Inst' || true)"
    mkdir -p "$(dirname "$ETAT_RETARD")"
    if [ "$en_attente" -eq 0 ]; then
        rm -f "$ETAT_RETARD"
        return 0
    fi
    maintenant="$(date +%s)"
    [ -f "$ETAT_RETARD" ] || echo "$maintenant" > "$ETAT_RETARD"
    depuis="$(cat "$ETAT_RETARD")"
    jours=$(((maintenant - depuis) / 86400))
    if [ "$jours" -ge "$RETARD_MAX_JOURS" ]; then
        ajouter_ecarts "mises à jour du système en retard : $en_attente paquet(s) en attente depuis $jours jour(s)"
    fi
}

controler "réglages PostgreSQL" bash "$EXPLOITATION/reglages-postgresql.sh" verifier
controler "accès réseau" bash "$EXPLOITATION/durcissement-acces.sh" verifier
controler_retard

if [ -z "$detail" ]; then
    echo "[controle-systeme] aucun écart"
    exit 0
fi

echo "[controle-systeme] écarts :"
printf '%s' "$detail"
secret="$(lire_env INTEGRITY_INTERNAL_SECRET)"
if [ -z "$secret" ]; then
    echo "[controle-systeme] INTEGRITY_INTERNAL_SECRET absent : écart non signalé à l'application" >&2
    exit 1
fi
curl -sf --max-time 20 -X POST \
    -H "Authorization: Bearer $secret" \
    --data-urlencode "statut=ecart_systeme" \
    --data-urlencode "detail=$detail" \
    "http://127.0.0.1:$PORT_WEB/api/interne/integrite-statut" > /dev/null \
    || { echo "[controle-systeme] application injoignable : écart non signalé" >&2; exit 1; }
