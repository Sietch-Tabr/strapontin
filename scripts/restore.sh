#!/usr/bin/env bash
set -euo pipefail

# restore.sh — restaure la base strapontin depuis une archive backup tar.gz
# Usage : restore.sh <strapontin_YYYYMMDD_HHMMSS.tar.gz>
# À exécuter en tant que root depuis le LXC prod/dev.

FICHIER="${1:?Usage: restore.sh <strapontin_YYYYMMDD_HHMMSS.tar.gz>}"
APP_DIR="/opt/strapontin"
APP_USER_DEFAUT="strapontin"

[ -f "$FICHIER" ] || { echo "ERREUR : fichier introuvable : $FICHIER" >&2; exit 1; }
[[ "$FICHIER" == *.tar.gz ]] || { echo "ERREUR : fichier attendu en .tar.gz" >&2; exit 1; }

echo "=== Restauration Strapontin ==="
echo "Fichier : $FICHIER"
echo ""

echo "[1/6] Arrêt des services..."
systemctl stop strapontin-web strapontin-worker

echo "[2/6] Extraction de l'archive..."
# Capturer le contenu une seule fois : grep -q sur un pipe direct depuis tar
# quitte dès la première correspondance, tar reçoit alors un SIGPIPE en
# tentant d'écrire la suite et pipefail remonte à tort cet échec plutôt que
# le succès de grep — bug constaté en conditions réelles, jamais détecté
# avant faute d'exécution de ce script.
CONTENU_ARCHIVE=$(tar -tzf "$FICHIER")
if grep -q "^dump\.sql$" <<< "$CONTENU_ARCHIVE"; then
    tar -xzf "$FICHIER" -C /tmp dump.sql
    DUMP=/tmp/dump.sql
elif grep -q "^dump_local\.sql$" <<< "$CONTENU_ARCHIVE"; then
    tar -xzf "$FICHIER" -C /tmp dump_local.sql
    DUMP=/tmp/dump_local.sql
else
    echo "ERREUR : aucun dump SQL trouvé dans l'archive" >&2
    exit 1
fi
echo "  $DUMP extrait"

echo "[3/6] Lecture du .env de l'archive (si présent)..."
# Extrait avant de toucher à la base : le propriétaire de la base recréée à
# l'étape suivante doit être ce compte applicatif, pas 'postgres' (voir #4).
ENV_PRESENT=0
APP_USER="$APP_USER_DEFAUT"
NOUVEAU_MDP=""
if grep -q "^\.env$" <<< "$CONTENU_ARCHIVE"; then
    tar -xzf "$FICHIER" -C /tmp .env
    APP_USER=$(grep '^SPRING_DATASOURCE_USERNAME=' /tmp/.env | cut -d'=' -f2-)
    NOUVEAU_MDP=$(grep '^SPRING_DATASOURCE_PASSWORD=' /tmp/.env | cut -d'=' -f2-)
    if [ -z "$APP_USER" ] || [ -z "$NOUVEAU_MDP" ]; then
        echo "ERREUR : SPRING_DATASOURCE_USERNAME/PASSWORD absents du .env de l'archive" >&2
        exit 1
    fi
    # APP_USER est interpolé tel quel dans des commandes shell/SQL ci-dessous (createdb -O,
    # ALTER USER) : un .env d'archive compromis pourrait sinon y injecter du shell/SQL arbitraire.
    if [[ ! "$APP_USER" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
        echo "ERREUR : SPRING_DATASOURCE_USERNAME invalide dans le .env de l'archive : $APP_USER" >&2
        exit 1
    fi
    ENV_PRESENT=1
    echo "  .env trouvé, utilisateur applicatif : $APP_USER"
else
    echo "  ATTENTION : aucun .env dans l'archive — utilisateur par défaut ($APP_USER_DEFAUT)"
    echo "  conservé, le .env actuel n'est pas modifié."
fi

echo "[4/6] Réinitialisation de la base (propriétaire : $APP_USER)..."
# createdb sans -O appartient au rôle exécutant (postgres) : depuis PG15,
# les droits CREATE sur le schéma public sont révoqués par défaut pour tout
# rôle autre que le propriétaire — Flyway, connecté en tant que $APP_USER,
# échouait alors sur toute migration avec "droit refusé pour le schéma
# public". Bug pré-existant, jamais détecté faute d'exécution réelle de ce
# script.
su - postgres -c "dropdb --if-exists strapontin && createdb -O '$APP_USER' strapontin"

echo "[5/6] Restauration du dump..."
su - postgres -c "psql strapontin" < "$DUMP"
rm "$DUMP"

echo "[6/6] Finalisation..."
if [ "$ENV_PRESENT" = "1" ]; then
    # Le .env restauré peut porter un mot de passe différent de celui du rôle
    # PostgreSQL actuel (ex. instance neuve provisionnée avec un mot de passe
    # aléatoire) — sans ce réalignement, l'application ne peut plus se
    # connecter à sa propre base malgré une restauration par ailleurs réussie.
    #
    # Passé par fichier temporaire + substitution psql -v (échappement SQL natif de :'pass')
    # plutôt qu'interpolé directement dans la commande shell : le mot de passe peut contenir
    # n'importe quel caractère (guillemet, $, backtick...), qui casserait sinon la commande
    # imbriquée dans su -c ou permettrait une injection SQL dans l'ALTER USER.
    MDP_TMP=$(mktemp)
    trap 'rm -f "$MDP_TMP"' EXIT
    printf '%s' "$NOUVEAU_MDP" > "$MDP_TMP"
    chmod 600 "$MDP_TMP"
    su - postgres -c 'psql -v pass="$(cat '"$MDP_TMP"')" -c "ALTER USER '"$APP_USER"' WITH PASSWORD :'"'"'pass'"'"';"'
    rm -f "$MDP_TMP"
    mv /tmp/.env "$APP_DIR/.env"
    chown "$APP_USER:$APP_USER" "$APP_DIR/.env"
    chmod 600 "$APP_DIR/.env"
    echo "  .env restauré, mot de passe PostgreSQL réaligné"
fi

echo "Redémarrage des services..."
systemctl start strapontin-web strapontin-worker

echo ""
echo "================================================================"
echo "  Restauration terminée"
echo "  Vérifiez l'application : curl -sf http://localhost:9090/actuator/health"
echo "================================================================"
