#!/usr/bin/env bash
set -uo pipefail

# lancer-e2e.sh — lance la suite E2E complète (5 navigateurs, jamais --project) contre le LXC dev,
# dans les mêmes conditions à chaque fois, et rapporte les chiffres exacts.
#
# Le run est DÉTACHÉ du terminal (nohup) et protégé de la veille d'inactivité (caffeinate -dis) : il
# se termine même si la session ou le terminal se ferme. Les navigateurs tournent sur le poste, qui
# doit donc rester allumé : un capot refermé sur batterie suspend quand même le run (il reprend au
# réveil, mais des délais peuvent alors faire échouer des tests : refaire le run).
#
# Avant le run : arrête les serveurs playwright-mcp du poste (global-setup.ts refuse de démarrer
# tant qu'un seul subsiste) et vérifie que le dev répond (actuator :9090, pas :8080).
#
# Usage :
#   bash e2e/lancer-e2e.sh              lance le run et le suit jusqu'au bilan
#   bash e2e/lancer-e2e.sh --suivre     se rattache au run en cours (ou relit le dernier)
# Variable : IP_DEV (défaut 192.168.20.78). Fichiers : /tmp/e2e-run.log (journal Playwright),
# /tmp/e2e-run.fin (code de Playwright, créé en dernier), /tmp/e2e-run.pid.
# Code de sortie : 0 seulement sans échec ni test instable ; le bilan affiche
# « N réussis, N échecs, N instables, N ignorés ».

IP="${IP_DEV:-192.168.20.78}"
dossier="$(cd "$(dirname "$0")" && pwd)"
journal=/tmp/e2e-run.log
fin=/tmp/e2e-run.fin
verrou=/tmp/e2e-run.pid
charge=/tmp/e2e-charge.log

# Relevé de la charge (moyenne sur 1 minute) du poste et du dev, toutes les minutes tant que le run
# dure : un run anormalement lent (40 minutes et 1 heure constatés sans cause établie) peut ainsi
# se rapporter à la charge de l'un ou de l'autre.
releve_charge() {
  : > "$charge"
  while [ ! -f "$fin" ]; do
    poste=$(uptime | sed -E 's/.*load averages?: *//' | awk '{gsub(",", ".", $1); print $1}')
    dev=$(ssh -o BatchMode=yes -o ConnectTimeout=5 "root@$IP" "cut -d' ' -f1 /proc/loadavg" 2>/dev/null || echo "?")
    echo "$(date +%H:%M) poste=${poste:-?} dev=${dev:-?}" >> "$charge"
    sleep "${INTERVALLE_CHARGE_S:-60}"
  done
}

# Charge maximale relevée pendant le run (poste et dev).
resume_charge() {
  [ -s "$charge" ] || return 0
  LC_ALL=C awk '{ split($2, a, "="); split($3, b, "="); if (a[2] + 0 > p) p = a[2] + 0; if (b[2] + 0 > d) d = b[2] + 0 }
       END { printf "Charge maximale relevée : poste %.1f, dev %.1f (journal %s)\n", p, d, FILENAME }' "$charge"
}

# Durée cumulée des tests de chaque projet (lignes « ✓/✘ N [projet] … (durée) » du journal) ; un
# projet qui dépasse deux fois la médiane des projets signale un run anormal (poste ou dev).
durees_par_projet() {
  awk '
    function secondes(d,   v) {
      v = d + 0
      if (d ~ /ms$/) return v / 1000
      if (d ~ /m$/) return v * 60
      return v
    }
    match($0, /^ +(✓|✘) +[0-9]+ \[[a-z-]+\]/) {
      projet = $0; sub(/^[^\[]*\[/, "", projet); sub(/\].*$/, "", projet)
      if (match($0, /\([0-9.]+(ms|s|m)\)$/)) {
        duree = substr($0, RSTART + 1, RLENGTH - 2)
        somme[projet] += secondes(duree); nombre[projet]++
      }
    }
    END {
      n = 0
      for (p in somme) { noms[++n] = p; valeurs[n] = somme[p] }
      for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (valeurs[j] < valeurs[i]) { t = valeurs[i]; valeurs[i] = valeurs[j]; valeurs[j] = t }
      mediane = n ? valeurs[int((n + 1) / 2)] : 0
      for (p in somme) {
        alerte = (mediane > 0 && somme[p] > 2 * mediane) ? "  <- plus de deux fois la médiane, run anormal ?" : ""
        printf "  %-14s %3d tests %6.1f min%s\n", p, nombre[p], somme[p] / 60, alerte
      }
    }
  ' "$journal" | sort
}

bilan() {
  local passes echecs instables ignores code
  code="$(cat "$fin" 2>/dev/null || echo 99)"
  passes=$(grep -Eo '^ +[0-9]+ passed' "$journal" | tail -1 | grep -Eo '[0-9]+' || echo 0)
  echecs=$(grep -Eo '^ +[0-9]+ failed' "$journal" | tail -1 | grep -Eo '[0-9]+' || echo 0)
  instables=$(grep -Eo '^ +[0-9]+ flaky' "$journal" | tail -1 | grep -Eo '[0-9]+' || echo 0)
  ignores=$(grep -Eo '^ +[0-9]+ skipped' "$journal" | tail -1 | grep -Eo '[0-9]+' || echo 0)
  echo "E2E : $passes réussis, $echecs échecs, $instables instables, $ignores ignorés (code $code)"
  echo "Durée cumulée par projet :"
  durees_par_projet
  resume_charge
  [ "${echecs:-0}" = "0" ] && [ "${instables:-0}" = "0" ] && [ "$code" = "0" ]
}

# Attend la fin du run en affichant le journal (une ligne de progression suffit, le journal complet
# reste dans $journal) puis rend le bilan.
suivre() {
  local derniere=""
  while [ ! -f "$fin" ]; do
    if [ -f "$verrou" ] && ! kill -0 "$(cat "$verrou")" 2>/dev/null && [ ! -f "$fin" ]; then
      echo "ERREUR : le run s'est arrêté sans bilan (voir $journal)" >&2
      return 2
    fi
    nouvelle=$(grep -E '^ +(✓|✘|-) +[0-9]+ ' "$journal" 2>/dev/null | tail -1 | cut -c1-110)
    if [ -n "$nouvelle" ] && [ "$nouvelle" != "$derniere" ]; then echo "$nouvelle"; derniere="$nouvelle"; fi
    sleep 20
  done
  bilan
}

if [ "${1:-}" = "--suivre" ]; then
  [ -f "$journal" ] || { echo "aucun run à suivre" >&2; exit 2; }
  suivre
  exit $?
fi

if [ -f "$verrou" ] && kill -0 "$(cat "$verrou")" 2>/dev/null && [ ! -f "$fin" ]; then
  echo "ERREUR : un run est déjà en cours (bash e2e/lancer-e2e.sh --suivre)" >&2
  exit 3
fi

if pgrep -f "playwright-mcp|@playwright/mcp" >/dev/null; then
  echo "arrêt des serveurs playwright-mcp du poste"
  pkill -f "playwright-mcp" || true
  pkill -f "@playwright/mcp" || true
  sleep 3
  if pgrep -f "playwright-mcp|@playwright/mcp" >/dev/null; then
    echo "ERREUR : un serveur playwright-mcp subsiste" >&2
    exit 2
  fi
fi

if ! curl -s --max-time 5 "http://$IP:9090/actuator/health" | grep -q '"status":"UP"'; then
  echo "ERREUR : le dev ne répond pas (bash scripts/dev.sh demarrer)" >&2
  exit 2
fi

rm -f "$fin"
nohup bash -c 'echo $$ > "$1"; cd "$2" && caffeinate -dis npx playwright test > "$3" 2>&1; echo $? > "$4"' \
  _ "$verrou" "$dossier" "$journal" "$fin" >/dev/null 2>&1 &
nohup bash -c "$(declare -f releve_charge); IP=\"$IP\" fin=\"$fin\" charge=\"$charge\" releve_charge" >/dev/null 2>&1 &
echo "run E2E lancé (détaché) ; journal : $journal"
sleep 2
suivre
