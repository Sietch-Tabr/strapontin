#!/bin/bash
set -euo pipefail

# preparer-mise-a-jour.sh — met un LXC en état de recevoir la mise à jour depuis l'administration :
# dossiers de travail avec leurs droits, secret du compte rendu, minuterie qui surveille les demandes.
# Idempotent : appelé par provision.sh (nouveau LXC) et par update.sh (chaque déploiement, donc aussi
# par la mise à jour cliquée), il ne change que ce qui diffère.
# Usage : bash /opt/strapontin/exploitation/preparer-mise-a-jour.sh   (root)

. "$(dirname "$0")/chemins.sh"

[ -f "$ENV_FICHIER" ] || { echo "ERREUR : $ENV_FICHIER introuvable" >&2; exit 1; }

# dossier, droits, propriétaire:groupe
preparer_dossier() {
    mkdir -p "$1"
    chmod "$2" "$1"
    chown "$3" "$1"
}

# Écrit par le web (compte applicatif), lu et retiré par le module de mise à jour (root).
preparer_dossier "$DEMANDES_DIR" 0750 "$APP_USER:$APP_USER"
# Écrit par le module, lu par le web.
preparer_dossier "$ETAT_DIR" 0755 root:root
# Réservés au module : téléchargements en cours et cache de la racine de confiance Sigstore.
preparer_dossier "$TELECHARGEMENTS_DIR" 0700 root:root
preparer_dossier "$SIGSTORE_DIR" 0700 root:root

# Le web lit ce secret au démarrage : un secret créé ici est pris en compte au redémarrage qui suit.
if [ -z "$(lire_env UPDATE_INTERNAL_SECRET)" ]; then
    # Affectation seule : un échec d'openssl arrête le script au lieu d'écrire un secret vide.
    SECRET="$(openssl rand -hex 32)"
    poser_env UPDATE_INTERNAL_SECRET "$SECRET"
    echo "[mise-a-jour] UPDATE_INTERNAL_SECRET créé"
fi

systemctl daemon-reload
systemctl enable --now strapontin-mise-a-jour.path
echo "[mise-a-jour] Dossiers, secret et minuterie prêts"
