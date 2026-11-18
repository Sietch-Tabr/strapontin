#!/usr/bin/env bash
# tester-sauvegardes.sh — contrôle, sans PostgreSQL ni systemd, que restore.sh relit une sauvegarde
# comme attendu : la restauration d'une sauvegarde .sql.gz comme d'une ancienne archive .tar.gz, le
# refus d'un dump corrompu, la libération du verrou. L'écriture des sauvegardes (backup.sh,
# backup-local.sh) est contrôlée par SauvegardeTest (module exploitation-controle). Les commandes
# système sont remplacées par des substituts placés devant le PATH.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
export VERROU_DIR="$TMP/verrou"
trap 'rm -rf "$TMP"' EXIT
FAUX="$TMP/faux"
mkdir -p "$FAUX" "$TMP/nas" "$TMP/entree"

# su : "pg_dump strapontin" produit un dump, "psql strapontin" enregistre ce qu'il reçoit.
cat > "$FAUX/su" <<'FIN'
#!/usr/bin/env bash
commande="${*: -1}"
case "$commande" in
    pg_dump*) printf -- '-- dump de test\nCREATE TABLE t (a int);\n' ;;
    "psql strapontin") cat > "$TMP_ENTREE/psql.sql" ;;
    *) exit 0 ;;
esac
FIN
for faux in systemctl chown; do printf '#!/usr/bin/env bash\nexit 0\n' > "$FAUX/$faux"; done
chmod +x "$FAUX"/*
export PATH="$FAUX:$PATH" TMP_ENTREE="$TMP/entree"

export ENV_FICHIER="$TMP/strapontin.env"
printf 'SPRING_DATASOURCE_USERNAME=strapontin\nSPRING_DATASOURCE_PASSWORD=secret\nSPRING_PROFILES_ACTIVE=prod\n' > "$ENV_FICHIER"
echecs=0
verifier() { if ! "$@"; then echo "ECHEC : $*" >&2; echecs=$((echecs + 1)); fi; }
tous_les_fichiers() { find "$1" -type f | sort; }

# 1. Une sauvegarde telle qu'écrite par backup.sh : un dump compressé et le .env à côté.
DUMP="$TMP/nas/strapontin-prod_20260101_000000.sql.gz"
printf -- '-- dump de test\nCREATE TABLE t (a int);\n' | gzip -c > "$DUMP"
cp "$ENV_FICHIER" "${DUMP%.sql.gz}.env"

# 2. restore.sh sur la sauvegarde .sql.gz : le dump est rejoué, le .env est restauré.
rm -f "$TMP/entree/psql.sql"
bash "$RACINE/restore.sh" "$DUMP" > /dev/null
verifier grep -q "CREATE TABLE t" "$TMP/entree/psql.sql"
verifier grep -q "SPRING_DATASOURCE_PASSWORD=secret" "$ENV_FICHIER"
# Le verrou d'exploitation est libéré à la fin de la restauration (le piège : un autre trap EXIT).
verifier test ! -e "$VERROU_DIR"

# 3. restore.sh sur une ancienne archive .tar.gz (dump + .env + journaux) : toujours restaurable.
printf -- '-- ancien dump\nCREATE TABLE ancien (a int);\n' > "$TMP/dump.sql"
cp "$ENV_FICHIER" "$TMP/.env"
tar -czf "$TMP/strapontin_ancien.tar.gz" -C "$TMP" dump.sql .env
rm -f "$TMP/entree/psql.sql"
bash "$RACINE/restore.sh" "$TMP/strapontin_ancien.tar.gz" > /dev/null
verifier grep -q "CREATE TABLE ancien" "$TMP/entree/psql.sql"
verifier test ! -e "$VERROU_DIR"

# 4. Un dump compressé corrompu est refusé avant de toucher à la base.
printf 'pas du gzip' > "$TMP/strapontin_casse.sql.gz"
if bash "$RACINE/restore.sh" "$TMP/strapontin_casse.sql.gz" > /dev/null 2>&1; then
    echo "ECHEC : un dump corrompu a été accepté" >&2
    echecs=$((echecs + 1))
fi
verifier test ! -e "$VERROU_DIR"

if [ "$echecs" -ne 0 ]; then
    echo "$echecs contrôle(s) en échec" >&2
    exit 1
fi
echo "ok : restauration conforme"
