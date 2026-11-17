#!/usr/bin/env bash
set -euo pipefail

# declencher-job.sh — appel de l'API interne du worker (127.0.0.1, port PORT_WORKER), authentifiée par un
# jeton dérivé d'APP_CONFIG_SECRET : le secret n'est jamais affiché ni passé en argument.
# Usage, en root sur le LXC :
#   declencher-job.sh <typeJob>              déclenche un job Quartz (ex. purger:concerts)
#   declencher-job.sh --simulation-salles    simulation de la résolution des salles (lecture seule)
# La logique est dans le JAR exploitation-controle (commande declencher-job).

. "$(dirname "$0")/chemins.sh"
lancer_controle declencher-job "$@"
