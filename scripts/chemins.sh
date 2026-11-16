#!/bin/bash
# chemins.sh — emplacements de l'installation (FHS 3.0, paquet de /opt), lus par les scripts
# d'exploitation. Chaque valeur est surchargeable par l'environnement, pour les tests.
#
#   /opt/strapontin/application     JARs actifs, .jar.new, .jar.prev        (root)
#   /opt/strapontin/exploitation    scripts, unités et configuration livrés (root)
#   /etc/opt/strapontin             strapontin.env, lu par EnvironmentFile= (root, 0600)
#   /var/opt/strapontin/sauvegardes sauvegardes locales                     (root seul)
#   /var/log/strapontin             journaux d'accès, LogsDirectory= de l'unité web

APP_RACINE="${APP_RACINE:-/opt/strapontin}"
APP_JARS="${APP_JARS:-$APP_RACINE/application}"
APP_EXPLOITATION="${APP_EXPLOITATION:-$APP_RACINE/exploitation}"
ENV_FICHIER="${ENV_FICHIER:-/etc/opt/strapontin/strapontin.env}"
SAUVEGARDES_LOCALES="${SAUVEGARDES_LOCALES:-/var/opt/strapontin/sauvegardes}"
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
