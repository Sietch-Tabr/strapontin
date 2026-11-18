#!/usr/bin/env bash
set -euo pipefail

# backup.sh — dump PostgreSQL compressé à la volée vers le NAS, et copie du .env à côté
# Usage : bash /opt/strapontin/exploitation/backup.sh
# À exécuter en tant que root depuis le LXC.
#
# Une sauvegarde = deux fichiers de même préfixe : strapontin-<env>_<horodatage>.sql.gz (le dump,
# écrit une seule fois, sans fichier intermédiaire) et strapontin-<env>_<horodatage>.env (secrets
# de l'application, nécessaires à une restauration complète), <env> valant dev ou prod : les deux
# environnements partagent le dossier du NAS sans se mélanger. Les journaux ne sont pas archivés :
# journald et les journaux d'accès les conservent, aucune restauration n'en a besoin. Rétention de
# 30 jours. La logique est dans le JAR exploitation-controle (commande backup).

. "$(dirname "$0")/chemins.sh"
lancer_controle backup "$@"
