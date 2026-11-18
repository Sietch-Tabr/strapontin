#!/usr/bin/env bash
# tester-sauvegardes.sh — contrôle, sans PostgreSQL ni systemd, que restore.sh restaure une sauvegarde
# comme attendu en lançant le VRAI JAR exploitation-controle (JAR_CONTROLE) : sauvegarde .sql.gz comme
# ancienne archive .tar.gz (vrai tar), refus d'un dump corrompu avant tout arrêt des services, première
# erreur SQL fatale, libération du verrou. L'écriture des sauvegardes est contrôlée par SauvegardeTest.
# Seuls su, systemctl, dropdb... sont remplacés par des substituts placés devant le PATH.
# Usage : JAR_CONTROLE=java/exploitation-controle/target/exploitation-controle-<version>.jar tester-sauvegardes.sh
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
[ -n "${JAR_CONTROLE:-}" ] && [ -f "$JAR_CONTROLE" ] \
    || { echo "ERREUR : JAR_CONTROLE doit désigner le JAR exploitation-controle construit" >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export VERROU_DIR="$TMP/verrou" APP_JARS="$TMP/application"
mkdir -p "$APP_JARS" "$TMP/nas" "$TMP/entree"
cp "$JAR_CONTROLE" "$APP_JARS/exploitation-controle.jar"
FAUX="$TMP/faux"
mkdir -p "$FAUX"

# su : le dump est enregistré tel que reçu, une erreur SQL simulée fait échouer psql comme ON_ERROR_STOP.
cat > "$FAUX/su" <<'FIN'
#!/usr/bin/env bash
commande="${*: -1}"
case "$commande" in
    "psql -v ON_ERROR_STOP=1 -q strapontin")
        cat > "$TMP_ENTREE/psql.sql"
        if grep -q ERREUR_SQL "$TMP_ENTREE/psql.sql"; then
            echo "psql:<stdin>:3: ERROR:  erreur simulée"
            exit 3
        fi ;;
    "psql -v ON_ERROR_STOP=1 -q -d postgres") cat > "$TMP_ENTREE/alter.sql" ;;
    dropdb*) echo "$commande" > "$TMP_ENTREE/reinit.txt" ;;
    *) exit 0 ;;
esac
FIN
# systemctl : journalise chaque appel (arrêt et démarrage des services).
printf '#!/usr/bin/env bash\necho "$*" >> "$TMP_ENTREE/systemctl.txt"\n' > "$FAUX/systemctl"
chmod +x "$FAUX"/*
export PATH="$FAUX:$PATH" TMP_ENTREE="$TMP/entree"

export ENV_FICHIER="$TMP/strapontin.env"
printf 'SPRING_DATASOURCE_USERNAME=strapontin\nSPRING_DATASOURCE_PASSWORD=secret\nSPRING_PROFILES_ACTIVE=prod\n' > "$ENV_FICHIER"
echecs=0
verifier() { if ! "$@"; then echo "ECHEC : $*" >&2; echecs=$((echecs + 1)); fi; }
repartir() { rm -f "$TMP"/entree/*; }

# 1. Une sauvegarde telle qu'écrite par backup.sh : un dump compressé et le .env à côté.
DUMP="$TMP/nas/strapontin-prod_20260101_000000.sql.gz"
printf -- '-- dump de test\nCREATE TABLE t (a int);\n' | gzip -c > "$DUMP"
cp "$ENV_FICHIER" "${DUMP%.sql.gz}.env"

# 2. restore.sh sur la sauvegarde .sql.gz : services arrêtés puis démarrés, base recréée pour le
# compte du .env, dump rejoué, mot de passe réaligné, .env restauré en 0600, verrou libéré.
repartir
printf 'SPRING_DATASOURCE_PASSWORD=ancien\n' > "$ENV_FICHIER"
bash "$RACINE/restore.sh" "$DUMP" > /dev/null
verifier grep -q "CREATE TABLE t" "$TMP/entree/psql.sql"
verifier grep -q "SPRING_DATASOURCE_PASSWORD=secret" "$ENV_FICHIER"
verifier grep -q "createdb -O 'strapontin' strapontin" "$TMP/entree/reinit.txt"
verifier grep -q "ALTER ROLE \"strapontin\" WITH PASSWORD E'secret'" "$TMP/entree/alter.sql"
verifier test "$(stat -c %a "$ENV_FICHIER" 2>/dev/null || stat -f %Lp "$ENV_FICHIER")" = 600
verifier test "$(sed -n 1p "$TMP/entree/systemctl.txt")" = "stop strapontin-web strapontin-worker"
verifier test "$(sed -n 2p "$TMP/entree/systemctl.txt")" = "start strapontin-web strapontin-worker"
verifier test ! -e "$VERROU_DIR"

# 3. restore.sh sur une ancienne archive .tar.gz (dump + .env + journaux) : toujours restaurable.
printf -- '-- ancien dump\nCREATE TABLE ancien (a int);\n' > "$TMP/dump.sql"
cp "$ENV_FICHIER" "$TMP/.env"
tar -czf "$TMP/strapontin_ancien.tar.gz" -C "$TMP" dump.sql .env
repartir
bash "$RACINE/restore.sh" "$TMP/strapontin_ancien.tar.gz" > /dev/null
verifier grep -q "CREATE TABLE ancien" "$TMP/entree/psql.sql"
verifier test ! -e "$VERROU_DIR"

# 4. Un dump compressé corrompu est refusé AVANT l'arrêt des services : l'application reste en marche.
printf 'pas du gzip' > "$TMP/strapontin_casse.sql.gz"
repartir
if bash "$RACINE/restore.sh" "$TMP/strapontin_casse.sql.gz" > /dev/null 2>&1; then
    echo "ECHEC : un dump corrompu a été accepté" >&2
    echecs=$((echecs + 1))
fi
verifier test ! -e "$TMP/entree/systemctl.txt"
verifier test ! -e "$VERROU_DIR"

# 5. Une erreur SQL arrête la restauration : code de sortie non nul, message de psql, services
# arrêtés puis jamais redémarrés, .env actuel intact.
printf -- 'CREATE TABLE t (a int);\nERREUR_SQL;\n' | gzip -c > "$TMP/strapontin-prod_erreur.sql.gz"
repartir
cp "$ENV_FICHIER" "$TMP/env-avant"
if bash "$RACINE/restore.sh" "$TMP/strapontin-prod_erreur.sql.gz" > /dev/null 2> "$TMP/sortie-erreur"; then
    echo "ECHEC : une erreur SQL a été ignorée" >&2
    echecs=$((echecs + 1))
fi
verifier grep -q "erreur simulée" "$TMP/sortie-erreur"
verifier grep -q "Les services restent arrêtés" "$TMP/sortie-erreur"
verifier test "$(wc -l < "$TMP/entree/systemctl.txt" | tr -d ' ')" = 1
verifier cmp -s "$ENV_FICHIER" "$TMP/env-avant"
verifier test ! -e "$VERROU_DIR"

# 6. Sans argument : usage, rien n'est touché.
repartir
if bash "$RACINE/restore.sh" > /dev/null 2> "$TMP/sortie-usage"; then
    echo "ECHEC : restore.sh sans argument a réussi" >&2
    echecs=$((echecs + 1))
fi
verifier grep -q "Usage: restore.sh" "$TMP/sortie-usage"
verifier test ! -e "$TMP/entree/systemctl.txt"

if [ "$echecs" -ne 0 ]; then
    echo "$echecs contrôle(s) en échec" >&2
    exit 1
fi
echo "ok : restauration conforme (JAR exploitation-controle réel)"
