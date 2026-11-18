#!/usr/bin/env bash
set -euo pipefail

# rotation-secrets.sh — régénère un ou plusieurs secrets du fichier d'environnement
# (/etc/opt/strapontin/strapontin.env) sur une instance
# déjà en service (après une fuite du fichier), sans recréer le LXC.
# Usage : rotation-secrets.sh [--db] [--app-config] [--backup] [--integrite] [--mise-a-jour] | --tous
# À exécuter en tant que root depuis le LXC. Procédure complète et checklist : wiki Sécurité.
# La logique est dans le JAR exploitation-controle (commande rotation-secrets) : verrou
# d'exploitation, contrôle AIDE préalable, copie du .env, retour arrière si les services ne repartent pas.

. "$(dirname "$0")/chemins.sh"
lancer_controle rotation-secrets "$@"
