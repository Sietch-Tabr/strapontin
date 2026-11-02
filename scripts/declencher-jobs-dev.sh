#!/usr/bin/env bash
set -euo pipefail

# declencher-jobs-dev.sh — déclenche des jobs Quartz sur le LXC dev, un par un (jamais en parallèle :
# Ticketmaster refuse plus de 2 jobs simultanés), et relève après chacun le journal du worker,
# `logs_admin` (warn/error) et `fetch_jobs_historique`. À lancer depuis le poste de travail.
#
# Usage :
#   bash scripts/declencher-jobs-dev.sh                 les 12 types de jobs, dans l'ordre
#   bash scripts/declencher-jobs-dev.sh purger:concerts suivi:cycles-jobs     ceux indiqués
# Variables : HOTE_DEV (défaut root@192.168.20.78), ATTENTE_MAX_S (défaut 900 par job).
#
# S'appuie sur /opt/strapontin/scripts/declencher-job.sh du LXC (déployé par /deploy-dev), qui
# calcule le jeton interne sans jamais afficher le secret : ce script ne lit pas le `.env`.
# Le LXC est à l'heure de Paris : journalctl reçoit l'heure locale sans fuseau, SQL l'heure
# avec fuseau (`journalctl --since` rejette « …+00 »).

HOTE="${HOTE_DEV:-root@192.168.20.78}"
ATTENTE_MAX="${ATTENTE_MAX_S:-900}"
JOBS_PAR_DEFAUT="fetch:artistes:batch fetch:region fetch:salles:all purger:concerts purger:salles \
purger:artistes purger:logs-admin revoquer:admins recalculer:matches notifier:digest \
reconciliation:nocturne suivi:cycles-jobs"
JOBS="${*:-$JOBS_PAR_DEFAUT}"

# Le corps est exécuté sur le LXC ; les variables passent en arguments, jamais par le shell local.
ssh "$HOTE" bash -s -- "$ATTENTE_MAX" "$JOBS" <<'DISTANT'
set -u
attente_max="$1"
jobs="$2"
declencheur=/opt/strapontin/scripts/declencher-job.sh

psql_dev() { su - postgres -c "psql -d strapontin -At -F ' | ' -c \"$1\""; }

# Contrôles préalables : mieux vaut échouer ici qu'attendre 15 minutes par job.
[ -x "$declencheur" ] || { echo "ERREUR : $declencheur absent ou non exécutable" >&2; exit 2; }
journalctl -u strapontin-worker --since "$(date '+%Y-%m-%d %H:%M:%S')" --no-pager >/dev/null 2>&1 \
  || { echo "ERREUR : journalctl illisible pour strapontin-worker" >&2; exit 2; }
psql_dev "select 1" >/dev/null || { echo "ERREUR : base du dev injoignable" >&2; exit 2; }

echo "job | statut | durée | warn/error logs_admin | warn/error journal" > /tmp/bilan-jobs.txt

for job in $jobs; do
  debut_sql=$(date '+%Y-%m-%d %H:%M:%S%z')
  debut_local=$(date '+%Y-%m-%d %H:%M:%S')
  debut_s=$(date +%s)
  echo "=================== $job (début $debut_sql)"
  reponse=$("$declencheur" "$job" 2>&1 | head -3)
  echo "$reponse"
  statut="terminé"
  case "$reponse" in *déclenché*) ;; *) statut="REFUS : $reponse" ;; esac

  # Fin du job : journal du worker sans nouvelle ligne pendant 25 s, après au moins une ligne.
  dernier=-1
  calme=0
  ecoule=0
  while [ "$statut" = "terminé" ] && [ "$ecoule" -lt "$attente_max" ]; do
    sleep 5
    ecoule=$((ecoule + 5))
    n=$(journalctl -u strapontin-worker --since "$debut_local" --no-pager 2>/dev/null | wc -l)
    if [ "$n" -eq "$dernier" ]; then calme=$((calme + 1)); else calme=0; fi
    dernier=$n
    if [ "$calme" -ge 5 ] && [ "$n" -gt 0 ]; then break; fi
  done
  [ "$statut" = "terminé" ] && [ "$ecoule" -ge "$attente_max" ] && statut="DÉLAI DÉPASSÉ (${attente_max}s)"

  echo "-- journal worker : WARN, ERROR, exceptions"
  journalctl -u strapontin-worker --since "$debut_local" --no-pager \
    | grep -E ' (WARN|ERROR) |Exception' | cut -c1-260 | head -15
  echo "-- logs_admin : warn et error"
  psql_dev "select niveau, source, left(message, 220) from logs_admin where ts >= '$debut_sql' and niveau in ('warn','error') order by ts" | head -15
  echo "-- fetch_jobs_historique"
  psql_dev "select type_job, duree_ms, nb_inseres, nb_mis_a_jour, coalesce(left(erreur,160),'-') from fetch_jobs_historique where exec_le >= '$debut_sql' order by exec_le" | head -8
  echo "-- dernières lignes du journal"
  journalctl -u strapontin-worker --since "$debut_local" --no-pager | tail -3 | cut -c1-220

  nb_logs=$(psql_dev "select count(*) from logs_admin where ts >= '$debut_sql' and niveau in ('warn','error')")
  nb_journal=$(journalctl -u strapontin-worker --since "$debut_local" --no-pager | grep -cE ' (WARN|ERROR) |Exception')
  echo "$job | $statut | $(( $(date +%s) - debut_s )) s | $nb_logs | $nb_journal" >> /tmp/bilan-jobs.txt
done

echo "=================== BILAN"
column -t -s '|' /tmp/bilan-jobs.txt 2>/dev/null || cat /tmp/bilan-jobs.txt
DISTANT
