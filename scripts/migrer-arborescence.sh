#!/bin/bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# migrer-arborescence.sh — passe un LXC de l'ancienne arborescence (tout sous /opt/strapontin,
# propriété de l'utilisateur applicatif) à celle du FHS 3.0 décrite dans chemins.sh : code et
# scripts à root, fichier d'environnement sous /etc/opt, sauvegardes sous /var/opt, journaux
# sous /var/log.
# Usage, en root sur le LXC, depuis exploitation/ (livré avant par deposer-exploitation.sh) :
#   migrer-arborescence.sh              migre ; sans effet sur un LXC déjà migré
#   migrer-arborescence.sh --annuler    revient à l'ancienne arborescence
#   migrer-arborescence.sh --nettoyer   supprime les copies gardées pour l'annulation
# Les services sont arrêtés pendant la migration ; si le web ne repart pas, elle s'annule seule.

. "$(dirname "$0")/chemins.sh"

AVANT_MIGRATION="${AVANT_MIGRATION:-$(dirname "$SAUVEGARDES_LOCALES")/avant-migration}"
ANCIEN_ENV="$APP_RACINE/.env"
ANCIENS_SCRIPTS="$APP_RACINE/scripts"
ANCIENNES_SAUVEGARDES="$APP_RACINE/backup"
ANCIENS_JOURNAUX="$APP_RACINE/logs"
FICHIERS_JARS=(web.jar worker.jar web.jar.prev worker.jar.prev web.jar.new worker.jar.new)
MINUTERIES=(strapontin-backup-local.timer strapontin-integrity.timer)
ATTENTE_WEB_S="${ATTENTE_WEB_S:-60}"

# Déplace SOURCE vers CIBLE si SOURCE existe et pas CIBLE : reprise possible après une interruption.
deplacer() {
    if [ -e "$1" ] && [ ! -e "$2" ]; then
        mkdir -p "$(dirname "$2")"
        mv "$1" "$2"
    fi
}

web_en_ligne() {
    local i
    for i in $(seq 1 "$ATTENTE_WEB_S"); do
        if curl -sf "http://localhost:9090/actuator/health" | grep -q '"status":"UP"'; then return 0; fi
        sleep 1
    done
    return 1
}

regenerer_baseline() {
    command -v aide >/dev/null 2>&1 && [ -f "$AIDE_CONF" ] || return 0
    aide --config="$AIDE_CONF" --init >/dev/null 2>&1 || true
    [ -f "$AIDE_BASE/aide.db.new" ] && mv "$AIDE_BASE/aide.db.new" "$AIDE_BASE/aide.db"
    return 0
}

# Profil lu dans le fichier d'environnement, où qu'il soit selon le sens de la migration.
est_dev() {
    local fichier
    for fichier in "$ENV_FICHIER" "$ANCIEN_ENV"; do
        [ -f "$fichier" ] && grep -q '^SPRING_PROFILES_ACTIVE=dev$' "$fichier" && return 0
    done
    return 1
}

demarrer() {
    systemctl daemon-reload
    systemctl start strapontin-web strapontin-worker
    systemctl start "${MINUTERIES[@]}"
    # Sur le dev, la sauvegarde NAS se lance à la demande, jamais à son démarrage.
    est_dev || systemctl start strapontin-backup.timer
}

migrer() {
    if [ ! -f "$ANCIEN_ENV" ] && [ -f "$ENV_FICHIER" ]; then
        echo "[migration] Arborescence déjà migrée, rien à faire"
        return 0
    fi
    [ -f "$ANCIEN_ENV" ] || { echo "ERREUR : ni $ANCIEN_ENV ni $ENV_FICHIER" >&2; return 1; }

    echo "[migration] Arrêt des services et des minuteries..."
    systemctl stop strapontin-web strapontin-worker
    systemctl stop "${MINUTERIES[@]}" strapontin-backup.timer

    echo "[migration] Copies pour l'annulation : $AVANT_MIGRATION"
    mkdir -p "$AVANT_MIGRATION/unites"
    for unite in "$UNITES_DIR"/strapontin-*; do
        [ -e "$unite" ] && [ ! -e "$AVANT_MIGRATION/unites/$(basename "$unite")" ] \
            && cp -a "$unite" "$AVANT_MIGRATION/unites/"
    done
    [ -f "$AIDE_CONF" ] && [ ! -e "$AVANT_MIGRATION/aide.conf" ] && cp -a "$AIDE_CONF" "$AVANT_MIGRATION/aide.conf"

    echo "[migration] Déplacement des fichiers..."
    mkdir -p "$APP_JARS" "$(dirname "$ENV_FICHIER")" "$(dirname "$SAUVEGARDES_LOCALES")" "$JOURNAUX_DIR"
    for fichier in "${FICHIERS_JARS[@]}"; do deplacer "$APP_RACINE/$fichier" "$APP_JARS/$fichier"; done
    deplacer "$ANCIEN_ENV" "$ENV_FICHIER"
    deplacer "$ANCIENNES_SAUVEGARDES" "$SAUVEGARDES_LOCALES"
    if [ -d "$ANCIENS_JOURNAUX" ]; then
        find "$ANCIENS_JOURNAUX" -mindepth 1 -maxdepth 1 -exec mv -n {} "$JOURNAUX_DIR"/ \;
        rmdir "$ANCIENS_JOURNAUX" 2>/dev/null || true
    fi
    deplacer "$ANCIENS_SCRIPTS" "$AVANT_MIGRATION/scripts"

    echo "[migration] Propriétaires et droits..."
    chown -R root:root "$APP_RACINE" "$APP_JARS" "$(dirname "$ENV_FICHIER")" "$SAUVEGARDES_LOCALES"
    chmod 755 "$APP_RACINE" "$APP_JARS" "$(dirname "$ENV_FICHIER")"
    chmod 600 "$ENV_FICHIER"
    chmod 700 "$SAUVEGARDES_LOCALES"
    chown -R "$APP_USER:$APP_USER" "$JOURNAUX_DIR"

    echo "[migration] Unités systemd, configuration AIDE, compte de service..."
    cp "$APP_EXPLOITATION"/systemd/* "$UNITES_DIR"/
    [ -f "$APP_EXPLOITATION/aide.conf" ] && mkdir -p "$(dirname "$AIDE_CONF")" && cp "$APP_EXPLOITATION/aide.conf" "$AIDE_CONF"
    usermod -s /usr/sbin/nologin "$APP_USER"
    if est_dev; then systemctl disable --now strapontin-backup.timer 2>/dev/null || true; fi

    echo "[migration] Redémarrage..."
    demarrer
    if ! web_en_ligne; then
        echo "ERREUR : web non UP après la migration, annulation" >&2
        annuler
        return 1
    fi
    regenerer_baseline
    echo "[migration] Terminée. Copies d'annulation gardées dans $AVANT_MIGRATION (--nettoyer pour les supprimer)"
}

annuler() {
    [ -d "$AVANT_MIGRATION" ] || { echo "ERREUR : rien à annuler ($AVANT_MIGRATION absent)" >&2; return 1; }
    echo "[annulation] Arrêt des services et des minuteries..."
    systemctl stop strapontin-web strapontin-worker || true
    systemctl stop "${MINUTERIES[@]}" strapontin-backup.timer || true

    for fichier in "${FICHIERS_JARS[@]}"; do deplacer "$APP_JARS/$fichier" "$APP_RACINE/$fichier"; done
    deplacer "$ENV_FICHIER" "$ANCIEN_ENV"
    deplacer "$SAUVEGARDES_LOCALES" "$ANCIENNES_SAUVEGARDES"
    deplacer "$AVANT_MIGRATION/scripts" "$ANCIENS_SCRIPTS"
    if [ -d "$JOURNAUX_DIR" ]; then
        mkdir -p "$ANCIENS_JOURNAUX"
        find "$JOURNAUX_DIR" -mindepth 1 -maxdepth 1 -exec mv -n {} "$ANCIENS_JOURNAUX"/ \;
    fi
    rmdir "$APP_JARS" 2>/dev/null || true

    for unite in "$AVANT_MIGRATION"/unites/*; do
        [ -e "$unite" ] && cp -a "$unite" "$UNITES_DIR"/
    done
    [ -f "$AVANT_MIGRATION/aide.conf" ] && cp -a "$AVANT_MIGRATION/aide.conf" "$AIDE_CONF"
    chown -R "$APP_USER:$APP_USER" "$APP_RACINE"
    chown root:root "$ANCIENS_SCRIPTS" 2>/dev/null || true
    usermod -s /bin/bash "$APP_USER"

    systemctl daemon-reload
    systemctl start strapontin-web strapontin-worker
    systemctl start "${MINUTERIES[@]}" strapontin-backup.timer || true
    if est_dev; then systemctl enable strapontin-backup.timer 2>/dev/null || true; fi
    regenerer_baseline
    echo "[annulation] Ancienne arborescence rétablie"
}

nettoyer() {
    rm -rf "$AVANT_MIGRATION"
    echo "[migration] Copies d'annulation supprimées"
}

case "${1:-}" in
    "") migrer ;;
    --annuler) annuler ;;
    --nettoyer) nettoyer ;;
    *) echo "Usage : $0 [--annuler | --nettoyer]" >&2; exit 1 ;;
esac
