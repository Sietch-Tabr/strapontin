#!/usr/bin/env bash
set -euo pipefail

# declencher-job.sh — appel de l'API interne du worker (127.0.0.1, port PORT_WORKER), authentifiée par un
# jeton dérivé d'APP_CONFIG_SECRET (HMAC-SHA256, même calcul que JetonInterneWorker) : le
# secret n'est jamais affiché ni passé en argument.
# Usage, en root sur le LXC :
#   declencher-job.sh <typeJob>              déclenche un job Quartz (ex. purger:concerts)
#   declencher-job.sh --simulation-salles    simulation de la résolution des salles (lecture seule)

. "$(dirname "$0")/chemins.sh"
ARGUMENT="${1:?usage : declencher-job.sh <typeJob> | --simulation-salles}"

SECRET="$(lire_env APP_CONFIG_SECRET)"
[ -n "$SECRET" ] || { echo "APP_CONFIG_SECRET absent de $ENV_FICHIER" >&2; exit 1; }
JETON=$(printf '%s' 'strapontin:api-interne-worker' \
    | openssl dgst -sha256 -hmac "$SECRET" -r | cut -d' ' -f1)
unset SECRET

if [ "$ARGUMENT" = "--simulation-salles" ]; then
    curl -sf --max-time 600 -H "X-Jeton-Worker: $JETON" \
        "http://127.0.0.1:$PORT_WORKER/trigger/simulation-salles"
else
    curl -sf --max-time 30 -X POST -H "X-Jeton-Worker: $JETON" \
        --data-urlencode "typeJob=$ARGUMENT" "http://127.0.0.1:$PORT_WORKER/trigger/job"
fi
echo
