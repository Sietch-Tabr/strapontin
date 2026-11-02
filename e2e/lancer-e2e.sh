#!/usr/bin/env bash
set -uo pipefail

# lancer-e2e.sh — lance la suite E2E complète (5 navigateurs, jamais --project) contre le LXC dev,
# dans les mêmes conditions à chaque fois, et rapporte les chiffres exacts.
#
# Avant le run : arrête les serveurs playwright-mcp du poste (global-setup.ts refuse de démarrer
# tant qu'un seul subsiste), vérifie que le dev répond (actuator :9090, pas :8080) et empêche la
# veille (caffeinate). Après : une ligne de bilan passed / failed / flaky / skipped.
#
# Usage : bash e2e/lancer-e2e.sh [journal]   (défaut : /tmp/e2e-<horodatage>.log)
# Variable : IP_DEV (défaut 192.168.20.78). Code de sortie : celui de Playwright (0 = run propre
# seulement s'il n'y a ni échec ni test instable : le bilan l'affiche).

IP="${IP_DEV:-192.168.20.78}"
dossier="$(cd "$(dirname "$0")" && pwd)"
journal="${1:-/tmp/e2e-$(date +%Y%m%d-%H%M%S).log}"

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

echo "journal : $journal"
cd "$dossier"
caffeinate -dis npx playwright test >"$journal" 2>&1
statut=$?

passes=$(grep -Eo '^ +[0-9]+ passed' "$journal" | tail -1 | grep -Eo '[0-9]+' || echo 0)
echecs=$(grep -Eo '^ +[0-9]+ failed' "$journal" | tail -1 | grep -Eo '[0-9]+' || echo 0)
instables=$(grep -Eo '^ +[0-9]+ flaky' "$journal" | tail -1 | grep -Eo '[0-9]+' || echo 0)
ignores=$(grep -Eo '^ +[0-9]+ skipped' "$journal" | tail -1 | grep -Eo '[0-9]+' || echo 0)

echo "E2E : $passes réussis, $echecs échecs, $instables instables, $ignores ignorés (code $statut)"
[ "$echecs" = "0" ] && [ "$instables" = "0" ] && [ "$statut" = "0" ]
