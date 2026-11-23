#!/bin/bash
set -euo pipefail

# migrer-postgresql.sh — passage du cluster PostgreSQL principal à une version majeure plus récente
# (pg_upgradecluster de postgresql-common, méthode « dump » : refait les index, sans réserve sur les
# collations ; la base fait quelques dizaines de Mo). L'ancien cluster reste installé, arrêté, sur un
# autre port : il n'est supprimé que par « supprimer-ancien ».
# Usage (root, sur le LXC) :
#   migrer-postgresql.sh verifier <ancienne> <nouvelle>        état des clusters et des paquets
#   migrer-postgresql.sh migrer <ancienne> <nouvelle>          migration (snapshot Proxmox exigé)
#   migrer-postgresql.sh supprimer-ancien <ancienne> <nouvelle>
#
# Avant « migrer », prendre un snapshot du LXC depuis Proxmox (pct snapshot <VMID> avant-pg<nouvelle>),
# seul retour arrière complet, puis exporter SNAPSHOT_FAIT=oui. Les services applicatifs sont
# arrêtés pendant la migration (quelques minutes d'indisponibilité).

. "$(dirname "$0")/chemins.sh"
. "$(dirname "$0")/verrou.sh"

COMMANDE="${1:-}"
ANCIENNE="${2:-}"
NOUVELLE="${3:-}"
EXPLOITATION="$(cd "$(dirname "$0")" && pwd)"

utilisation() {
    echo "Usage : $0 verifier|migrer|supprimer-ancien <ancienne> <nouvelle>" >&2
    exit 1
}

[[ "$ANCIENNE" =~ ^[0-9]+$ && "$NOUVELLE" =~ ^[0-9]+$ ]] || utilisation
[ "$NOUVELLE" -gt "$ANCIENNE" ] || { echo "ERREUR : la nouvelle version doit être supérieure à l'ancienne" >&2; exit 1; }

etat_cluster() {
    pg_lsclusters --no-header | awk -v v="$1" '$1 == v && $2 == "main" { print $4 " (port " $3 ", " $5 ")" }'
}

verifier() {
    echo "[migration] clusters :"
    pg_lsclusters
    [ -n "$(etat_cluster "$ANCIENNE")" ] || { echo "ERREUR : cluster $ANCIENNE/main introuvable" >&2; return 1; }
    [ -z "$(etat_cluster "$NOUVELLE")" ] || { echo "ERREUR : un cluster $NOUVELLE/main existe déjà" >&2; return 1; }
    dpkg -s "postgresql-$NOUVELLE" > /dev/null 2>&1 \
        || { echo "ERREUR : paquet postgresql-$NOUVELLE non installé (apt-get install postgresql-$NOUVELLE)" >&2; return 1; }
    pg_upgradecluster --check -v "$NOUVELLE" "$ANCIENNE" main
    echo "[migration] prêt : $ANCIENNE -> $NOUVELLE"
}

attendre_sante() {
    local essais=60
    until curl -sf --max-time 5 "http://127.0.0.1:$PORT_WEB_SANTE/actuator/health" | grep -q '"status":"UP"'; do
        essais=$((essais - 1))
        [ "$essais" -gt 0 ] || return 1
        sleep 3
    done
}

migrer() {
    [ "${SNAPSHOT_FAIT:-}" = "oui" ] \
        || { echo "ERREUR : snapshot Proxmox exigé avant la migration (SNAPSHOT_FAIT=oui)" >&2; exit 1; }
    verifier
    prendre_verrou "migration-postgresql"
    trap liberer_verrou EXIT
    echo "[migration] sauvegarde locale avant migration"
    bash "$EXPLOITATION/backup-local.sh"
    echo "[migration] arrêt des services applicatifs"
    systemctl stop strapontin-web strapontin-worker
    echo "[migration] pg_upgradecluster $ANCIENNE -> $NOUVELLE (méthode dump)"
    pg_upgradecluster -v "$NOUVELLE" -m dump "$ANCIENNE" main
    # Le nouveau cluster reprend le port 5432 ; les réglages sont posés dans son propre conf.d.
    PG_VERSION="$NOUVELLE" bash "$EXPLOITATION/reglages-postgresql.sh" appliquer
    echo "[migration] redémarrage des services applicatifs"
    systemctl start strapontin-web strapontin-worker
    attendre_sante || { echo "ERREUR : l'application ne répond pas après la migration (snapshot à restaurer)" >&2; exit 1; }
    pg_lsclusters
    echo "[migration] terminée ; contrôler l'application puis lancer « supprimer-ancien »"
}

supprimer_ancien() {
    [ "$(etat_cluster "$NOUVELLE" | cut -d' ' -f1)" = "online" ] \
        || { echo "ERREUR : le cluster $NOUVELLE/main n'est pas en ligne" >&2; exit 1; }
    pg_dropcluster --stop "$ANCIENNE" main
    echo "[migration] cluster $ANCIENNE/main supprimé (paquet postgresql-$ANCIENNE à retirer par apt si voulu)"
}

case "$COMMANDE" in
    verifier) verifier ;;
    migrer) migrer ;;
    supprimer-ancien) supprimer_ancien ;;
    *) utilisation ;;
esac
