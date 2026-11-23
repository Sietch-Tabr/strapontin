#!/bin/bash
set -euo pipefail

# reglages-postgresql.sh — réglages PostgreSQL de Strapontin : une seule définition (conf_attendue),
# appliquée dans un fichier de conf.d du cluster principal, puis contrôlée sur les valeurs réellement
# en vigueur.
# Usage (root, sur le LXC) :
#   reglages-postgresql.sh appliquer   écrit le fichier, active pg_stat_statements, recharge ou
#                                      redémarre PostgreSQL si un paramètre l'exige, puis contrôle
#   reglages-postgresql.sh verifier    code 1 si un réglage n'est pas celui du fichier
#   reglages-postgresql.sh requetes    requêtes les plus coûteuses depuis la dernière remise à zéro
#
# Le contrôle interroge pg_settings : un réglage ne vient pas du fichier (surchargé ailleurs) ou
# attend un redémarrage (pending_restart) est un écart, sans comparer les unités.

. "$(dirname "$0")/chemins.sh"

BASE="${DB_NAME:-strapontin}"
# Commande d'accès administrateur à la base (surchargeable pour les tests).
PSQL_ADMIN="${PSQL_ADMIN:-runuser -u postgres -- psql -X -At -d $BASE}"

# Réglages dimensionnés pour une base de quelques dizaines de Mo sur un LXC de 3 Go partagé avec
# deux JVM. Aucun paramètre de requête n'est journalisé (pg_stat_statements les normalise,
# log_parameter_max_length = 0 les retire du journal des requêtes lentes).
conf_attendue() {
    cat <<'CONF'
shared_buffers = 256MB
work_mem = 16MB
effective_cache_size = 1GB
random_page_cost = 1.1
jit = off
statement_timeout = 30s
idle_in_transaction_session_timeout = 30s
shared_preload_libraries = 'pg_stat_statements'
pg_stat_statements.max = 5000
pg_stat_statements.track = all
track_io_timing = on
log_min_duration_statement = 1s
log_parameter_max_length = 0
log_parameter_max_length_on_error = 0
log_lock_waits = on
log_temp_files = 4MB
CONF
}

# Noms des réglages de conf_attendue.
cles_attendues() {
    conf_attendue | sed 's/[[:space:]]*=.*//'
}

# Version majeure du cluster principal en ligne (« 18 », « 19 »).
version_cluster() {
    if [ -n "${PG_VERSION:-}" ]; then
        echo "$PG_VERSION"
        return 0
    fi
    pg_lsclusters --no-header | awk '$2 == "main" && $4 == "5432" { print $1; exit }'
}

fichier_conf() {
    local version
    version="$(version_cluster)"
    [ -n "$version" ] || { echo "ERREUR : aucun cluster « main » sur le port 5432" >&2; return 1; }
    echo "${PG_CONF_D:-/etc/postgresql/$version/main/conf.d}/strapontin.conf"
}

verifier() {
    local fichier cle ecarts=0 ligne source attente
    fichier="$(fichier_conf)"
    while read -r cle; do
        ligne="$($PSQL_ADMIN -c "SELECT coalesce(sourcefile, ''), pending_restart FROM pg_settings WHERE name = '$cle'")"
        source="${ligne%%|*}"
        attente="${ligne##*|}"
        if [ -z "$ligne" ]; then
            echo "[ECART] $cle : réglage inconnu de PostgreSQL"
            ecarts=$((ecarts + 1))
        elif [ "$source" != "$fichier" ]; then
            echo "[ECART] $cle : la valeur en vigueur ne vient pas de $fichier (source : ${source:-défaut})"
            ecarts=$((ecarts + 1))
        elif [ "$attente" = "t" ]; then
            echo "[ECART] $cle : en attente d'un redémarrage de PostgreSQL"
            ecarts=$((ecarts + 1))
        else
            echo "[OK]    $cle"
        fi
    done < <(cles_attendues)
    if [ "$($PSQL_ADMIN -c "SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'")" != "1" ]; then
        echo "[ECART] extension pg_stat_statements absente de la base $BASE"
        ecarts=$((ecarts + 1))
    fi
    if [ "$ecarts" -ne 0 ]; then
        echo "$ecarts écart(s) entre les réglages attendus et PostgreSQL" >&2
        return 1
    fi
}

appliquer() {
    local fichier temporaire
    fichier="$(fichier_conf)"
    mkdir -p "$(dirname "$fichier")"
    temporaire="$(mktemp "$fichier.XXXXXX")"
    {
        echo "# Géré par reglages-postgresql.sh : ne pas modifier à la main."
        conf_attendue
    } > "$temporaire"
    chmod 0644 "$temporaire"
    if [ -f "$fichier" ] && cmp -s "$temporaire" "$fichier"; then
        rm -f "$temporaire"
        echo "[reglages] $fichier déjà à jour"
    else
        mv "$temporaire" "$fichier"
        echo "[reglages] $fichier écrit"
    fi
    # shared_preload_libraries ne se prend en compte qu'au redémarrage.
    if [ "$($PSQL_ADMIN -c 'SHOW shared_preload_libraries')" = "pg_stat_statements" ]; then
        systemctl reload postgresql
    else
        echo "[reglages] redémarrage de PostgreSQL (shared_preload_libraries)"
        systemctl restart postgresql
    fi
    $PSQL_ADMIN -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements' > /dev/null
    verifier
}

requetes() {
    echo "--- Compteurs de pg_stat_statements depuis : $($PSQL_ADMIN -c 'SELECT stats_reset FROM pg_stat_statements_info')"
    $PSQL_ADMIN -F ' | ' -c "
        SELECT calls, round(total_exec_time::numeric) AS total_ms,
               round(mean_exec_time::numeric, 2) AS moyen_ms, rows AS lignes,
               shared_blks_read AS blocs_lus, temp_blks_written AS blocs_temp,
               left(regexp_replace(query, '\s+', ' ', 'g'), 100) AS requete
        FROM pg_stat_statements
        WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$BASE')
        ORDER BY total_exec_time DESC LIMIT 20"
    echo "--- Fichiers temporaires de la base (work_mem dépassé), depuis : $($PSQL_ADMIN -c "SELECT stats_reset FROM pg_stat_database WHERE datname = '$BASE'")"
    $PSQL_ADMIN -F ' | ' -c "SELECT temp_files AS fichiers, pg_size_pretty(temp_bytes) AS volume FROM pg_stat_database WHERE datname = '$BASE'"
}

case "${1:-}" in
    appliquer) appliquer ;;
    verifier) verifier ;;
    requetes) requetes ;;
    *) echo "Usage : $0 appliquer|verifier|requetes" >&2; exit 1 ;;
esac
