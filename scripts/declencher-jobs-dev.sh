#!/usr/bin/env bash
set -euo pipefail

# declencher-jobs-dev.sh — déclenche des jobs Quartz sur le LXC dev, un par un (jamais en parallèle :
# Ticketmaster refuse plus de 2 jobs simultanés), et relève après chacun le journal du worker,
# `logs_admin` (warn/error) et `fetch_jobs_historique`.
#
# La boucle tourne DÉTACHÉE sur le LXC (setsid) : elle va à son terme même si le poste se met en
# veille ou perd la connexion. Le poste ne fait que suivre le journal ; il peut se rattacher.
#
# Usage :
#   bash scripts/declencher-jobs-dev.sh                       les 12 types de jobs, dans l'ordre
#   bash scripts/declencher-jobs-dev.sh purger:concerts suivi:cycles-jobs     ceux indiqués
#   bash scripts/declencher-jobs-dev.sh --suivre              se rattacher à un run en cours ou
#                                                             relire le dernier
# Variables : HOTE_DEV (défaut root@192.168.20.78), ATTENTE_MAX_S (défaut 900 par job).
# Code de sortie : nombre de jobs refusés ou dépassés (0 = tous terminés).
#
# S'appuie sur /opt/strapontin/scripts/declencher-job.sh du LXC (déployé par /deploy-dev), qui
# calcule le jeton interne sans jamais afficher le secret : ce script ne lit pas le `.env`.
# Le LXC est à l'heure de Paris : journalctl reçoit l'heure locale sans fuseau, SQL l'heure avec
# fuseau (`journalctl --since` rejette « …+00 »). Fichiers sur le LXC : /tmp/jobs-dev.log (journal
# du run), /tmp/jobs-dev.fin (code final, créé en dernier), /tmp/jobs-dev.verrou.

HOTE="${HOTE_DEV:-root@192.168.20.78}"
ATTENTE_MAX="${ATTENTE_MAX_S:-900}"
JOBS_PAR_DEFAUT="fetch:artistes:batch fetch:region fetch:salles:all purger:concerts purger:salles \
purger:artistes purger:logs-admin revoquer:admins recalculer:matches notifier:digest \
reconciliation:nocturne suivi:cycles-jobs"

# Affiche le journal du run au fur et à mesure et rend le code final. Chaque lecture est une
# connexion ssh indépendante : une veille du poste ne fait que retarder la suivante.
suivre() {
  local position=0 morceau echecs=0 code
  morceau=$(mktemp)
  trap 'rm -f "$morceau"' RETURN
  while true; do
    if ssh -o ConnectTimeout=10 "$HOTE" "tail -c +$((position + 1)) /tmp/jobs-dev.log 2>/dev/null" >"$morceau"; then
      echecs=0
      cat "$morceau"
      position=$((position + $(wc -c <"$morceau")))
      if code=$(ssh -o ConnectTimeout=10 "$HOTE" "cat /tmp/jobs-dev.fin 2>/dev/null") && [ -n "$code" ]; then
        # Dernière lecture : ce qui a été écrit entre la lecture et la fin.
        ssh -o ConnectTimeout=10 "$HOTE" "tail -c +$((position + 1)) /tmp/jobs-dev.log" || true
        return "$code"
      fi
    else
      echecs=$((echecs + 1))
      if [ "$echecs" -ge 120 ]; then
        echo "suivi : LXC injoignable depuis 10 minutes (le run continue sur le LXC : --suivre)" >&2
        return 99
      fi
    fi
    sleep 5
  done
}

if [ "${1:-}" = "--suivre" ]; then
  suivre
  exit $?
fi

JOBS="${*:-$JOBS_PAR_DEFAUT}"
# ssh recompose la ligne de commande distante : la liste est protégée par printf %q, sinon seul le
# premier type de job arriverait au script.
jobs_protege=$(printf '%q' "$JOBS")

ssh -o ConnectTimeout=10 "$HOTE" "bash -s -- $ATTENTE_MAX $jobs_protege" <<'LANCEUR'
set -eu
attente_max="$1"
jobs="$2"

# Un seul run à la fois : deux boucles déclencheraient des jobs en parallèle.
if [ -f /tmp/jobs-dev.verrou ] && kill -0 "$(cat /tmp/jobs-dev.verrou)" 2>/dev/null; then
  echo "ERREUR : un run est déjà en cours (bash scripts/declencher-jobs-dev.sh --suivre)" >&2
  exit 3
fi

# Contrôles préalables : mieux vaut échouer ici qu'attendre 15 minutes par job.
declencheur=/opt/strapontin/scripts/declencher-job.sh
[ -x "$declencheur" ] || { echo "ERREUR : $declencheur absent ou non exécutable" >&2; exit 2; }
journalctl -u strapontin-worker --since "$(date '+%Y-%m-%d %H:%M:%S')" --no-pager >/dev/null 2>&1 \
  || { echo "ERREUR : journalctl illisible pour strapontin-worker" >&2; exit 2; }
su - postgres -c "psql -d strapontin -Atc 'select 1'" >/dev/null \
  || { echo "ERREUR : base du dev injoignable" >&2; exit 2; }

cat > /tmp/jobs-dev-boucle.sh <<'BOUCLE'
set -u
attente_max="$1"
jobs="$2"
declencheur=/opt/strapontin/scripts/declencher-job.sh
psql_dev() { su - postgres -c "psql -d strapontin -At -F ' | ' -c \"$1\""; }
echo "job | statut | durée | warn/error logs_admin | warn/error journal" > /tmp/bilan-jobs.txt
anomalies=0

for job in $jobs; do
  debut_sql=$(date '+%Y-%m-%d %H:%M:%S%z')
  debut_local=$(date '+%Y-%m-%d %H:%M:%S')
  debut_s=$(date +%s)
  echo "=================== $job (début $debut_sql)"
  reponse=$("$declencheur" "$job" 2>&1 | head -3)
  echo "$reponse"
  statut="terminé"
  case "$reponse" in *déclenché*) ;; *) statut="REFUS : $reponse" ;; esac

  # Fin du job : sa ligne « <job> terminé » dans le journal du worker. Le silence du journal ne
  # prouve rien (une réconciliation peut se taire plus d'une minute) : deux jobs se chevaucheraient.
  fin="$job terminé"
  [ "$job" = "purger:concerts" ] && fin="purger:concerts-hors-perimetre terminé"
  ecoule=0
  if [ "$statut" = "terminé" ]; then
    until journalctl -u strapontin-worker --since "$debut_local" --no-pager | grep -qF "$fin"; do
      if [ "$ecoule" -ge "$attente_max" ]; then statut="DÉLAI DÉPASSÉ (${attente_max}s)"; break; fi
      sleep 3
      ecoule=$((ecoule + 3))
    done
  fi
  [ "$statut" = "terminé" ] || anomalies=$((anomalies + 1))

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
echo "$anomalies" > /tmp/jobs-dev.fin
BOUCLE

rm -f /tmp/jobs-dev.fin
setsid nohup bash -c 'echo $$ > /tmp/jobs-dev.verrou; bash /tmp/jobs-dev-boucle.sh "$0" "$1"; rm -f /tmp/jobs-dev.verrou' \
  "$attente_max" "$jobs" > /tmp/jobs-dev.log 2>&1 < /dev/null &
echo "run lancé sur le LXC (jobs : $jobs)"
LANCEUR

suivre
