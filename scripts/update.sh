#!/bin/bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# update.sh — redémarrage des services Strapontin après déploiement de nouveaux JARs
# Usage : /opt/strapontin/update.sh
# Usage : /opt/strapontin/scripts/update.sh
# Appelé par Woodpecker (prod) ou manuellement (dev) après scp des JARs vers *.jar.new.

APP_DIR="/opt/strapontin"

rollback() {
    echo "[update] Rollback en cours..." >&2
    set +e
    [ -f "$APP_DIR/web.jar.prev" ] && mv "$APP_DIR/web.jar.prev" "$APP_DIR/web.jar"
    [ -f "$APP_DIR/worker.jar.prev" ] && mv "$APP_DIR/worker.jar.prev" "$APP_DIR/worker.jar"
    systemctl restart strapontin-web strapontin-worker
    echo "[update] Rollback terminé" >&2
    set -e
}

# Fichiers qu'un déploiement légitime a déjà pu modifier avant cet appel (copiés par
# woodpecker-deploy.sh) — les JARs ne sont remplacés qu'après le pré-contrôle ci-dessous.
# La règle AIDE sur web.jar couvre aussi web.jar.new (préfixe) ; le dossier scripts change
# dès qu'un script y est ajouté, chaque fichier ajouté restant listé individuellement.
FICHIERS_DEPLOIEMENT=(
    "$APP_DIR/web.jar.new"
    "$APP_DIR/worker.jar.new"
    "$APP_DIR/scripts"
    "$APP_DIR/scripts/backup.sh"
    "$APP_DIR/scripts/restore.sh"
    "$APP_DIR/scripts/update.sh"
    "$APP_DIR/scripts/rotation-secrets.sh"
    "$APP_DIR/scripts/backup-local.sh"
    "$APP_DIR/scripts/declencher-job.sh"
)
# Chemins réellement modifiés selon le rapport AIDE : ajoutés, supprimés, ou changés dans leur
# contenu ou leurs droits. Un fichier recopié à l'identique (step Woodpecker backup) ne change
# que de dates (Mtime/Ctime/Atime/Inode) et n'est pas une modification.
chemins_modifies() {
    awk '
        /^(Added|Removed) entries:/ { section = "ajout" ; next }
        /^Changed entries:/ { section = "" ; next }
        /^Detailed information about changes:/ { section = "detail" ; next }
        /^The attributes of the/ { section = "" ; next }
        section == "ajout" && match($0, /: (\/opt\/strapontin|\/etc\/systemd)[^ ]*/) {
            print substr($0, RSTART + 2, RLENGTH - 2) ; next
        }
        section == "detail" && /^(File|Directory|Link): / { chemin = $2 ; next }
        section == "detail" && chemin != "" && match($0, /^ [A-Za-z0-9\/_-]+ *:/) {
            attribut = substr($0, 2, RLENGTH - 2) ; gsub(/ /, "", attribut)
            if (attribut !~ /^(Mtime|Ctime|Atime|Inode)$/ && chemin ~ /^(\/opt\/strapontin|\/etc\/systemd)/) {
                print chemin
            }
        }
    ' | sort -u
}

AIDE_ACTIF=false
command -v aide >/dev/null 2>&1 && [ -f /etc/aide/aide.conf ] && AIDE_ACTIF=true

# Pré-contrôle AIDE avant tout remplacement : un changement hors déploiement (unit systemd,
# .env, autre script) ne doit jamais être absorbé silencieusement par la régénération de la
# baseline en fin de déploiement.
CHANGEMENTS_EXTERNES=""
if [ "$AIDE_ACTIF" = true ]; then
    echo "[update] Pré-contrôle d'intégrité AIDE..."
    CODE_AIDE=0
    SORTIE_AIDE=$(aide --config=/etc/aide/aide.conf --check 2>&1) || CODE_AIDE=$?
    if [ "$CODE_AIDE" -ne 0 ]; then
        while read -r chemin; do
            [ -z "$chemin" ] && continue
            attendu=false
            for fichier in "${FICHIERS_DEPLOIEMENT[@]}"; do
                [ "$chemin" = "$fichier" ] && attendu=true
            done
            [ "$attendu" = false ] && CHANGEMENTS_EXTERNES+="$chemin"$'\n'
        done < <(echo "$SORTIE_AIDE" | chemins_modifies)
    fi
    if [ -n "$CHANGEMENTS_EXTERNES" ]; then
        echo "[update] AVERTISSEMENT : changement(s) hors déploiement détecté(s) :" >&2
        echo "$CHANGEMENTS_EXTERNES" >&2
    fi
fi

# Sauvegarde des JARs actifs (absent au premier déploiement)
echo "[update] Sauvegarde des JARs..."
[ -f "$APP_DIR/web.jar" ] && cp "$APP_DIR/web.jar" "$APP_DIR/web.jar.prev"
[ -f "$APP_DIR/worker.jar" ] && cp "$APP_DIR/worker.jar" "$APP_DIR/worker.jar.prev"

# Activation atomique : mv est atomique sur le même filesystem
echo "[update] Activation des nouveaux JARs..."
mv "$APP_DIR/web.jar.new" "$APP_DIR/web.jar"
mv "$APP_DIR/worker.jar.new" "$APP_DIR/worker.jar"

echo "[update] Redémarrage des services..."
systemctl restart strapontin-web strapontin-worker

echo "[update] Attente démarrage web (max 60 s)..."
HEALTH_OK=false
for i in $(seq 1 60); do
    if curl -sf "http://localhost:9090/actuator/health" | grep -q '"status":"UP"'; then
        echo "[update] Service web UP (${i}s)"
        HEALTH_OK=true
        break
    fi
    sleep 1
done

sleep 5

if [ "$HEALTH_OK" = false ]; then
    echo "[update] ERREUR : service web non UP après 60s" >&2
    journalctl -u strapontin-web -n 30 --no-pager >&2
    rollback
    exit 1
fi

echo "[update] Vérification worker..."
sleep 3
if ! systemctl is-active --quiet strapontin-worker; then
    echo "[update] ERREUR : strapontin-worker inactif" >&2
    journalctl -u strapontin-worker -n 20 --no-pager >&2
    rollback
    exit 1
fi
echo "[update] Service worker UP"

if [ "$AIDE_ACTIF" = true ] && [ -n "$CHANGEMENTS_EXTERNES" ]; then
    # Baseline conservée : les JARs du déploiement apparaîtront aussi dans l'alerte
    # quotidienne tant qu'un humain n'aura pas authentifié le changement externe puis
    # régénéré la baseline (aide --init, cf. wiki Sécurité).
    echo "[update] Baseline AIDE NON régénérée : changement externe à authentifier" >&2
    INTEGRITY_SECRET=$(grep '^INTEGRITY_INTERNAL_SECRET=' "$APP_DIR/.env" | cut -d'=' -f2- || true)
    curl -sf -X POST \
        -H "Authorization: Bearer $INTEGRITY_SECRET" \
        --data-urlencode "statut=modifie" \
        --data-urlencode "detail=Déploiement : baseline conservée, changement hors déploiement : $CHANGEMENTS_EXTERNES" \
        "http://localhost:8080/api/interne/integrite-statut" \
        >/dev/null 2>&1 || echo "[update] AVERTISSEMENT : notification d'intégrité non envoyée" >&2
elif [ "$AIDE_ACTIF" = true ]; then
    echo "[update] Régénération de la baseline AIDE..."
    # Sans cette régénération, chaque déploiement légitime (web.jar/worker.jar modifiés)
    # déclencherait une fausse alerte d'intégrité au prochain contrôle quotidien.
    aide --config=/etc/aide/aide.conf --init >/dev/null 2>&1 || true
    if [ -f /var/lib/aide/aide.db.new ]; then
        mv /var/lib/aide/aide.db.new /var/lib/aide/aide.db
        echo "[update] Baseline AIDE régénérée"
    else
        echo "[update] AVERTISSEMENT : régénération de la baseline AIDE échouée" >&2
    fi
else
    echo "[update] AIDE non installé — étape ignorée"
fi

echo "[update] Déploiement terminé."
