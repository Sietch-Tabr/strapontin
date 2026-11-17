#!/bin/bash
set -euo pipefail
trap 'echo "ERREUR à la ligne $LINENO : $BASH_COMMAND" >&2' ERR

# update.sh — redémarrage des services Strapontin après déploiement de nouveaux JARs
# Usage : /opt/strapontin/exploitation/update.sh
# Appelé par /deploiement-prod (prod) ou /deploy-dev (dev) après le dépôt des scripts d'exploitation
# (deposer-exploitation.sh) et des JARs dans application/*.jar.new.

. "$(dirname "$0")/chemins.sh"
. "$(dirname "$0")/verrou.sh"
prendre_verrou "update.sh" || exit 1
trap liberer_verrou EXIT
MANIFESTE="$APP_EXPLOITATION/MANIFESTE.sha256"
AIDE_ACTIF=false
CHANGEMENTS_EXTERNES=""

# Sans cette régénération, tout changement légitime (JARs, scripts, retrait des .prev par un
# rollback) déclencherait une fausse alerte d'intégrité au prochain contrôle quotidien.
regenerer_baseline() {
    echo "[update] Régénération de la baseline AIDE..."
    aide --config="$AIDE_CONF" --init >/dev/null 2>&1 || true
    if [ -f "$AIDE_BASE/aide.db.new" ]; then
        mv "$AIDE_BASE/aide.db.new" "$AIDE_BASE/aide.db"
        echo "[update] Baseline AIDE régénérée"
    else
        echo "[update] AVERTISSEMENT : régénération de la baseline AIDE échouée" >&2
    fi
}

rollback() {
    echo "[update] Rollback en cours..." >&2
    set +e
    [ -f "$APP_JARS/web.jar.prev" ] && mv "$APP_JARS/web.jar.prev" "$APP_JARS/web.jar"
    [ -f "$APP_JARS/worker.jar.prev" ] && mv "$APP_JARS/worker.jar.prev" "$APP_JARS/worker.jar"
    systemctl restart strapontin-web strapontin-worker
    # Le rollback retire les .prev que la baseline attend : sans la régénérer, le contrôle
    # suivant alerte à tort. Un changement externe déjà détecté garde la baseline en l'état.
    if [ "$AIDE_ACTIF" = true ] && [ -z "$CHANGEMENTS_EXTERNES" ]; then
        regenerer_baseline
    elif [ "$AIDE_ACTIF" = true ]; then
        echo "[update] Baseline AIDE NON régénérée : changement externe à authentifier" >&2
    fi
    echo "[update] Rollback terminé" >&2
    set -e
}

# sha256 portable (Linux : sha256sum, macOS : shasum).
somme_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi
}

# Les fichiers livrés doivent être ceux du dépôt, dont le manifeste est livré avec eux : un
# fichier absent, différent ou une livraison interrompue arrête le déploiement avant tout
# remplacement.
verifier_livraison() {
    local somme nom ecarts=""
    if [ ! -f "$MANIFESTE" ]; then
        echo "[update] ERREUR : manifeste de livraison absent ($MANIFESTE)" >&2
        return 1
    fi
    while read -r somme nom; do
        if [ ! -f "$APP_EXPLOITATION/$nom" ] \
            || [ "$(somme_sha256 "$APP_EXPLOITATION/$nom" | cut -d' ' -f1)" != "$somme" ]; then
            ecarts+="  $nom"$'\n'
        fi
    done < "$MANIFESTE"
    if [ -n "$ecarts" ]; then
        echo "[update] ERREUR : fichiers livrés différents du dépôt :" >&2
        printf '%s' "$ecarts" >&2
        return 1
    fi
}

# Unités systemd et configuration AIDE installées = celles livrées. Chaque écart est signalé,
# puis corrigé ; daemon-reload n'est lancé que si une unité a changé.
aligner_installation() {
    local source cible unite_changee=false
    for source in "$APP_EXPLOITATION"/systemd/*; do
        cible="$UNITES_DIR/$(basename "$source")"
        if ! cmp -s "$source" "$cible"; then
            echo "[update] DÉRIVE corrigée : $cible différait du dépôt"
            cp "$source" "$cible"
            unite_changee=true
        fi
    done
    if [ -f "$APP_EXPLOITATION/aide.conf" ] && [ -f "$AIDE_CONF" ] \
        && ! cmp -s "$APP_EXPLOITATION/aide.conf" "$AIDE_CONF"; then
        echo "[update] DÉRIVE corrigée : $AIDE_CONF différait du dépôt"
        cp "$APP_EXPLOITATION/aide.conf" "$AIDE_CONF"
    fi
    [ "$unite_changee" = true ] && systemctl daemon-reload
    return 0
}

# Chemins qu'un déploiement légitime a déjà pu modifier avant cet appel : les fichiers du
# manifeste (déposés par deposer-exploitation.sh) et les JARs .new, qui ne sont activés qu'après
# le pré-contrôle ci-dessous.
FICHIERS_DEPLOIEMENT=(
    "$APP_JARS" "$APP_JARS/web.jar.new" "$APP_JARS/worker.jar.new"
    "$APP_JARS/mise-a-jour.jar" "$APP_JARS/mise-a-jour.jar.new"
    "$APP_EXPLOITATION" "$APP_EXPLOITATION/systemd" "$MANIFESTE"
)
if [ -f "$MANIFESTE" ]; then
    while read -r _ nom; do FICHIERS_DEPLOIEMENT+=("$APP_EXPLOITATION/$nom"); done < "$MANIFESTE"
fi
# Chemins réellement modifiés selon le rapport AIDE : ajoutés, supprimés, ou changés dans leur
# contenu ou leurs droits. Un fichier recopié à l'identique (livraison répétée à l'identique) ne change
# que de dates (Mtime/Ctime/Atime/Inode) et n'est pas une modification.
chemins_modifies() {
    awk '
        /^(Added|Removed) entries:/ { section = "ajout" ; next }
        /^Changed entries:/ { section = "" ; next }
        /^Detailed information about changes:/ { section = "detail" ; next }
        /^The attributes of the/ { section = "" ; next }
        section == "ajout" && match($0, /: (\/opt\/strapontin|\/etc\/opt\/strapontin|\/etc\/systemd)[^ ]*/) {
            print substr($0, RSTART + 2, RLENGTH - 2) ; next
        }
        section == "detail" && /^(File|Directory|Link): / { chemin = $2 ; next }
        section == "detail" && chemin != "" && match($0, /^ [A-Za-z0-9\/_-]+ *:/) {
            attribut = substr($0, 2, RLENGTH - 2) ; gsub(/ /, "", attribut)
            if (attribut !~ /^(Mtime|Ctime|Atime|Inode)$/ && chemin ~ /^(\/opt\/strapontin|\/etc\/opt\/strapontin|\/etc\/systemd)/) {
                print chemin
            }
        }
    ' | sort -u
}

verifier_livraison || exit 1

command -v aide >/dev/null 2>&1 && [ -f "$AIDE_CONF" ] && AIDE_ACTIF=true

# Pré-contrôle AIDE avant tout remplacement : un changement hors déploiement (unit systemd,
# .env, autre script) ne doit jamais être absorbé silencieusement par la régénération de la
# baseline en fin de déploiement.
if [ "$AIDE_ACTIF" = true ]; then
    echo "[update] Pré-contrôle d'intégrité AIDE..."
    CODE_AIDE=0
    SORTIE_AIDE=$(aide --config="$AIDE_CONF" --check 2>&1) || CODE_AIDE=$?
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

aligner_installation

# Dossiers, secret et minuterie de la mise à jour depuis l'administration : convergence à chaque
# déploiement. Un échec est signalé sans arrêter le déploiement, que cette fonction n'engage pas.
bash "$(dirname "$0")/preparer-mise-a-jour.sh" \
    || echo "[update] AVERTISSEMENT : préparation de la mise à jour depuis l'administration échouée" >&2

# Sauvegarde des JARs actifs (absent au premier déploiement)
echo "[update] Sauvegarde des JARs..."
[ -f "$APP_JARS/web.jar" ] && cp "$APP_JARS/web.jar" "$APP_JARS/web.jar.prev"
[ -f "$APP_JARS/worker.jar" ] && cp "$APP_JARS/worker.jar" "$APP_JARS/worker.jar.prev"

# Activation atomique : mv est atomique sur le même filesystem
echo "[update] Activation des nouveaux JARs..."
mv "$APP_JARS/web.jar.new" "$APP_JARS/web.jar"
mv "$APP_JARS/worker.jar.new" "$APP_JARS/worker.jar"

echo "[update] Redémarrage des services..."
systemctl restart strapontin-web strapontin-worker

echo "[update] Attente démarrage web (max 60 s)..."
HEALTH_OK=false
for i in $(seq 1 60); do
    if curl -sf "http://localhost:$PORT_WEB_SANTE/actuator/health" | grep -q '"status":"UP"'; then
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

# Le module de mise à jour se remplace en dernier, une fois le déploiement validé : un rollback ne
# le touche pas, et la baseline AIDE ci-dessous le prend en compte. Absent, rien à faire (livraison
# par deploy-dev ou /deploiement-prod). Le processus qui nous lance garde l'ancien fichier ouvert.
if [ -f "$APP_JARS/mise-a-jour.jar.new" ]; then
    mv "$APP_JARS/mise-a-jour.jar.new" "$APP_JARS/mise-a-jour.jar"
    echo "[update] mise-a-jour.jar mis en place"
fi

if [ "$AIDE_ACTIF" = true ] && [ -n "$CHANGEMENTS_EXTERNES" ]; then
    # Baseline conservée : les JARs du déploiement apparaîtront aussi dans l'alerte
    # quotidienne tant qu'un humain n'aura pas authentifié le changement externe puis
    # régénéré la baseline (aide --init, cf. wiki Sécurité).
    echo "[update] Baseline AIDE NON régénérée : changement externe à authentifier" >&2
    INTEGRITY_SECRET="$(lire_env INTEGRITY_INTERNAL_SECRET)"
    curl -sf -X POST \
        -H "Authorization: Bearer $INTEGRITY_SECRET" \
        --data-urlencode "statut=modifie" \
        --data-urlencode "detail=Déploiement : baseline conservée, changement hors déploiement : $CHANGEMENTS_EXTERNES" \
        "http://localhost:$PORT_WEB/api/interne/integrite-statut" \
        >/dev/null 2>&1 || echo "[update] AVERTISSEMENT : notification d'intégrité non envoyée" >&2
elif [ "$AIDE_ACTIF" = true ]; then
    regenerer_baseline
else
    echo "[update] AIDE non installé — étape ignorée"
fi

echo "[update] Déploiement terminé."
