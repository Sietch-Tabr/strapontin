#!/usr/bin/env bash
set -euo pipefail

# rotation-secrets.sh — régénère un ou plusieurs secrets du fichier d'environnement
# (/etc/opt/strapontin/strapontin.env) sur une instance
# déjà en service (après une fuite du fichier), sans recréer le LXC.
# Usage : rotation-secrets.sh [--db] [--app-config] [--backup] [--integrite] | --tous
# À exécuter en tant que root depuis le LXC. Procédure complète et checklist : wiki Sécurité.

. "$(dirname "$0")/chemins.sh"

ROTATION_DB=false
ROTATION_APP_CONFIG=false
ROTATION_BACKUP=false
ROTATION_INTEGRITE=false

usage() {
    echo "Usage : $0 [--db] [--app-config] [--backup] [--integrite] | --tous" >&2
    exit 1
}

[ "$#" -gt 0 ] || usage
for argument in "$@"; do
    case "$argument" in
        --db) ROTATION_DB=true ;;
        --app-config) ROTATION_APP_CONFIG=true ;;
        --backup) ROTATION_BACKUP=true ;;
        --integrite) ROTATION_INTEGRITE=true ;;
        --tous) ROTATION_DB=true; ROTATION_APP_CONFIG=true; ROTATION_BACKUP=true; ROTATION_INTEGRITE=true ;;
        *) usage ;;
    esac
done

[ -f "$ENV_FICHIER" ] || { echo "ERREUR : $ENV_FICHIER introuvable" >&2; exit 1; }

lire() {
    grep "^$1=" "$ENV_FICHIER" | cut -d'=' -f2- || true
}

remplacer() {
    local cle="$1" valeur="$2"
    if grep -q "^$cle=" "$ENV_FICHIER"; then
        sed -i "s|^$cle=.*|$cle=$valeur|" "$ENV_FICHIER"
    else
        echo "$cle=$valeur" >> "$ENV_FICHIER"
    fi
}

supprimer() {
    sed -i "/^$1=/d" "$ENV_FICHIER"
}

attendre_services() {
    for _ in $(seq 1 90); do
        if curl -sf "http://localhost:9090/actuator/health" | grep -q '"status":"UP"' \
            && systemctl is-active --quiet strapontin-worker; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# Une modification non authentifiée des fichiers surveillés doit être examinée avant : la
# baseline AIDE est régénérée en fin de rotation et l'absorberait sinon.
if command -v aide >/dev/null 2>&1 && [ -f "$AIDE_CONF" ]; then
    echo "[rotation] Contrôle d'intégrité préalable..."
    if ! aide --config="$AIDE_CONF" --check >/dev/null 2>&1; then
        echo "ERREUR : AIDE détecte des modifications non authentifiées — à examiner avant toute" \
            "rotation (aide --config="$AIDE_CONF" --check)" >&2
        exit 1
    fi
fi

# Hors des dossiers surveillés : la règle AIDE sur /etc/opt/strapontin couvre tout fichier voisin, et
# la suppression recommandée de cette sauvegarde passerait pour un changement externe.
SAUVEGARDE="/root/strapontin-env-avant-rotation-$(date +%Y%m%d_%H%M%S)"
cp -p "$ENV_FICHIER" "$SAUVEGARDE"
chmod 600 "$SAUVEGARDE"
echo "[rotation] .env sauvegardé : $SAUVEGARDE (à supprimer une fois la rotation validée)"

APP_USER=$(lire SPRING_DATASOURCE_USERNAME)
ANCIEN_MDP_DB=$(lire SPRING_DATASOURCE_PASSWORD)
if [ "$ROTATION_DB" = true ]; then
    # Interpolé tel quel dans du SQL : jamais une valeur hors identifiant PostgreSQL simple.
    if [[ ! "$APP_USER" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
        echo "ERREUR : SPRING_DATASOURCE_USERNAME invalide : $APP_USER" >&2
        exit 1
    fi
fi

changer_mdp_db() {
    su postgres -c "psql -v ON_ERROR_STOP=1 -q -d postgres" <<SQL
ALTER ROLE "$APP_USER" WITH PASSWORD '$1';
SQL
}

restaurer() {
    echo "[rotation] ÉCHEC — restauration de l'état précédent..." >&2
    set +e
    cp -p "$SAUVEGARDE" "$ENV_FICHIER"
    [ "$ROTATION_DB" = true ] && changer_mdp_db "$ANCIEN_MDP_DB"
    systemctl restart strapontin-web strapontin-worker
    set -e
    exit 1
}

if [ "$ROTATION_DB" = true ]; then
    NOUVEAU_MDP_DB=$(openssl rand -hex 16)
    changer_mdp_db "$NOUVEAU_MDP_DB"
    remplacer SPRING_DATASOURCE_PASSWORD "$NOUVEAU_MDP_DB"
    echo "[rotation] Mot de passe PostgreSQL du rôle $APP_USER changé"
fi
if [ "$ROTATION_APP_CONFIG" = true ]; then
    # L'application rechiffre app_config et les secrets TOTP au démarrage, puis la variable
    # APP_CONFIG_SECRET_PRECEDENT est retirée (voir RechiffrementSecretConfig).
    remplacer APP_CONFIG_SECRET_PRECEDENT "$(lire APP_CONFIG_SECRET)"
    remplacer APP_CONFIG_SECRET "$(openssl rand -hex 32)"
    echo "[rotation] APP_CONFIG_SECRET régénéré"
fi
if [ "$ROTATION_BACKUP" = true ]; then
    remplacer BACKUP_INTERNAL_SECRET "$(openssl rand -hex 32)"
    echo "[rotation] BACKUP_INTERNAL_SECRET régénéré"
fi
if [ "$ROTATION_INTEGRITE" = true ]; then
    remplacer INTEGRITY_INTERNAL_SECRET "$(openssl rand -hex 32)"
    echo "[rotation] INTEGRITY_INTERNAL_SECRET régénéré"
fi
# sed -i recrée le fichier : propriétaire et droits d'origine à rétablir.
chown --reference="$SAUVEGARDE" "$ENV_FICHIER"
chmod 600 "$ENV_FICHIER"

DEBUT=$(date '+%Y-%m-%d %H:%M:%S')
echo "[rotation] Redémarrage des services..."
systemctl restart strapontin-web strapontin-worker
attendre_services || restaurer

if [ "$ROTATION_APP_CONFIG" = true ]; then
    if ! journalctl -u strapontin-web --since "$DEBUT" --no-pager \
        | grep -q "rechiffrement APP_CONFIG_SECRET terminé"; then
        restaurer
    fi
    journalctl -u strapontin-web --since "$DEBUT" --no-pager \
        | grep "rechiffrement APP_CONFIG_SECRET terminé" | tail -1
    supprimer APP_CONFIG_SECRET_PRECEDENT
    systemctl restart strapontin-web strapontin-worker
    attendre_services || { echo "ERREUR : services non repartis sans APP_CONFIG_SECRET_PRECEDENT" >&2; exit 1; }
fi

if command -v aide >/dev/null 2>&1 && [ -f "$AIDE_CONF" ]; then
    aide --config="$AIDE_CONF" --init >/dev/null 2>&1 || true
    [ -f "$AIDE_BASE/aide.db.new" ] && mv "$AIDE_BASE/aide.db.new" "$AIDE_BASE/aide.db"
    echo "[rotation] Baseline AIDE régénérée (.env modifié par cette rotation)"
fi

echo ""
echo "[rotation] Terminé. Si la base de données a aussi pu fuiter, faire tourner hors de ce script :"
echo "  - clé API Ticketmaster (portail développeur) puis /admin > Configuration"
echo "  - clé API Resend et secret de signature du webhook (dashboard Resend) puis /admin"
echo "  - clés VAPID (/admin) : invalide tous les abonnements push, prévenir les utilisateurs avant"
echo "  - mots de passe des comptes : changement forcé depuis /admin > Communication d'incident"
