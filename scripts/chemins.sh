#!/bin/bash
# chemins.sh — emplacements de l'installation (FHS 3.0, paquet de /opt), lus par les scripts
# d'exploitation. Chaque valeur est surchargeable par l'environnement, pour les tests.
#
#   /opt/strapontin/application     JARs actifs, .jar.new, .jar.prev        (root)
#   /opt/strapontin/exploitation    scripts, unités et configuration livrés (root)
#   /etc/opt/strapontin             strapontin.env, lu par EnvironmentFile= (root, 0600)
#   /var/opt/strapontin/sauvegardes sauvegardes locales                     (root seul)
#   /var/opt/strapontin/verrou      verrou d'exploitation (voir verrou.sh)  (root seul)
#   /var/opt/strapontin/demandes    demandes de mise à jour de l'application (écrit par le web)
#   /var/opt/strapontin/etat        état de la mise à jour, lu par l'application
#   /var/log/strapontin             journaux d'accès, LogsDirectory= de l'unité web
#
# Les mêmes noms et valeurs par défaut sont lus par la bibliothèque Java exploitation-commun
# (classe Chemins) : une modification ici se répercute là-bas.

APP_RACINE="${APP_RACINE:-/opt/strapontin}"
APP_JARS="${APP_JARS:-$APP_RACINE/application}"
APP_EXPLOITATION="${APP_EXPLOITATION:-$APP_RACINE/exploitation}"
ENV_FICHIER="${ENV_FICHIER:-/etc/opt/strapontin/strapontin.env}"
VAR_RACINE="${VAR_RACINE:-/var/opt/strapontin}"
SAUVEGARDES_LOCALES="${SAUVEGARDES_LOCALES:-$VAR_RACINE/sauvegardes}"
VERROU_DIR="${VERROU_DIR:-$VAR_RACINE/verrou}"
DEMANDES_DIR="${DEMANDES_DIR:-$VAR_RACINE/demandes}"
ETAT_DIR="${ETAT_DIR:-$VAR_RACINE/etat}"
TELECHARGEMENTS_DIR="${TELECHARGEMENTS_DIR:-$VAR_RACINE/telechargements}"
SIGSTORE_DIR="${SIGSTORE_DIR:-$VAR_RACINE/sigstore}"
# Ports locaux : application, actuator du web, API interne du worker, actuator du worker.
PORT_WEB="${PORT_WEB:-8080}"
PORT_WEB_SANTE="${PORT_WEB_SANTE:-9090}"
PORT_WORKER="${PORT_WORKER:-8081}"
PORT_WORKER_SANTE="${PORT_WORKER_SANTE:-9091}"
JOURNAUX_DIR="${JOURNAUX_DIR:-/var/log/strapontin}"
SAUVEGARDES_NAS="${SAUVEGARDES_NAS:-/mnt/backup}"
UNITES_DIR="${UNITES_DIR:-/etc/systemd/system}"
AIDE_CONF="${AIDE_CONF:-/etc/aide/aide.conf}"
AIDE_BASE="${AIDE_BASE:-/var/lib/aide}"
APP_USER="${APP_USER:-strapontin}"

# Valeur d'une clé du fichier d'environnement (vide si absente).
lire_env() {
    [ -f "$ENV_FICHIER" ] || return 0
    grep "^$1=" "$ENV_FICHIER" | cut -d'=' -f2- || true
}

# Pose CLÉ=valeur dans le fichier d'environnement : remplace la ligne existante ou ajoute la clé.
# Le fichier est réécrit en place (propriétaire et droits conservés), sans option propre à GNU.
poser_env() {
    local cle="$1" valeur="$2" temporaire
    temporaire="$(mktemp)"
    { grep -v "^$cle=" "$ENV_FICHIER" || true; echo "$cle=$valeur"; } > "$temporaire"
    cat "$temporaire" > "$ENV_FICHIER"
    rm -f "$temporaire"
}

# Environnement de l'instance (dev ou prod), d'après SPRING_PROFILES_ACTIVE : identifie ses
# sauvegardes, qui partagent le dossier du NAS avec celles de l'autre environnement.
environnement() {
    local profil
    profil="${ENVIRONNEMENT:-$(lire_env SPRING_PROFILES_ACTIVE)}"
    case "$profil" in
        dev | prod) echo "$profil" ;;
        *) echo "ERREUR : SPRING_PROFILES_ACTIVE absent ou inconnu dans $ENV_FICHIER" >&2; return 1 ;;
    esac
}
