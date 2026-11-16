#!/usr/bin/env bash
# tester-sauvegardes.sh — contrôle, sans PostgreSQL ni systemd, que backup.sh, backup-local.sh et
# restore.sh écrivent et relisent une sauvegarde comme attendu : un dump compressé écrit une seule
# fois, jamais de fichier intermédiaire ni de journaux, le .env à côté, la restauration d'une
# sauvegarde .sql.gz comme d'une ancienne archive .tar.gz. Les commandes système sont remplacées
# par des substituts placés devant le PATH.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
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
for faux in mountpoint systemctl chown; do printf '#!/usr/bin/env bash\nexit 0\n' > "$FAUX/$faux"; done
chmod +x "$FAUX"/*
export PATH="$FAUX:$PATH" TMP_ENTREE="$TMP/entree"

export ENV_FICHIER="$TMP/strapontin.env"
printf 'SPRING_DATASOURCE_USERNAME=strapontin\nSPRING_DATASOURCE_PASSWORD=secret\nSPRING_PROFILES_ACTIVE=prod\n' > "$ENV_FICHIER"
echecs=0
verifier() { if ! "$@"; then echo "ECHEC : $*" >&2; echecs=$((echecs + 1)); fi; }
tous_les_fichiers() { find "$1" -type f | sort; }

# 1. backup.sh : un .sql.gz et un .env, rien d'autre (ni /tmp intermédiaire, ni journaux).
SAUVEGARDES_NAS="$TMP/nas" bash "$RACINE/backup.sh" > /dev/null
DUMP="$(ls "$TMP"/nas/strapontin-prod_*.sql.gz)"
verifier test "$(tous_les_fichiers "$TMP/nas" | wc -l | tr -d ' ')" = 2
verifier gzip -t "$DUMP"
verifier grep -q "CREATE TABLE t" <(gzip -dc "$DUMP")
verifier test -f "${DUMP%.sql.gz}.env"
verifier test "$(stat -c %a "${DUMP%.sql.gz}.env" 2>/dev/null || stat -f %Lp "${DUMP%.sql.gz}.env")" = 600
verifier test ! -e /tmp/dump.sql

# 2. backup-local.sh : même contenu, sans appel réseau réel.
SAUVEGARDES_LOCALES="$TMP/local" URL_STATUT="http://127.0.0.1:9/inexistant" \
    bash "$RACINE/backup-local.sh" > /dev/null
verifier test "$(tous_les_fichiers "$TMP/local" | wc -l | tr -d ' ')" = 2
verifier ls "$TMP"/local/strapontin-prod_*.sql.gz >/dev/null
verifier test ! -e /tmp/dump_local.sql

# 3. restore.sh sur la sauvegarde .sql.gz : le dump est rejoué, le .env est restauré.
rm -f "$TMP/entree/psql.sql"
bash "$RACINE/restore.sh" "$DUMP" > /dev/null
verifier grep -q "CREATE TABLE t" "$TMP/entree/psql.sql"
verifier grep -q "SPRING_DATASOURCE_PASSWORD=secret" "$ENV_FICHIER"

# 4. restore.sh sur une ancienne archive .tar.gz (dump + .env + journaux) : toujours restaurable.
printf -- '-- ancien dump\nCREATE TABLE ancien (a int);\n' > "$TMP/dump.sql"
cp "$ENV_FICHIER" "$TMP/.env"
tar -czf "$TMP/strapontin_ancien.tar.gz" -C "$TMP" dump.sql .env
rm -f "$TMP/entree/psql.sql"
bash "$RACINE/restore.sh" "$TMP/strapontin_ancien.tar.gz" > /dev/null
verifier grep -q "CREATE TABLE ancien" "$TMP/entree/psql.sql"

# 5. Un dump compressé corrompu est refusé avant de toucher à la base.
printf 'pas du gzip' > "$TMP/strapontin_casse.sql.gz"
if bash "$RACINE/restore.sh" "$TMP/strapontin_casse.sql.gz" > /dev/null 2>&1; then
    echo "ECHEC : un dump corrompu a été accepté" >&2
    echecs=$((echecs + 1))
fi

# 6. Purge : une sauvegarde de plus de 30 jours est supprimée pour cet environnement et pour les
# anciennes sans préfixe, jamais pour l'autre environnement, quel que soit son âge.
touch -t 202001010000 "$TMP/nas/strapontin-prod_20200101_000000.sql.gz" \
    "$TMP/nas/strapontin_20200101_000000.sql.gz" "$TMP/nas/strapontin-dev_20200101_000000.sql.gz"
SAUVEGARDES_NAS="$TMP/nas" bash "$RACINE/backup.sh" > /dev/null
verifier test ! -e "$TMP/nas/strapontin-prod_20200101_000000.sql.gz"
verifier test ! -e "$TMP/nas/strapontin_20200101_000000.sql.gz"
verifier test -e "$TMP/nas/strapontin-dev_20200101_000000.sql.gz"

# 7. Un environnement inconnu arrête la sauvegarde avant d'écrire quoi que ce soit.
printf 'SPRING_PROFILES_ACTIVE=autre\n' > "$TMP/inconnu.env"
if ENV_FICHIER="$TMP/inconnu.env" SAUVEGARDES_NAS="$TMP/nas2" bash "$RACINE/backup.sh" > /dev/null 2>&1; then
    echo "ECHEC : une sauvegarde d'environnement inconnu a été acceptée" >&2
    echecs=$((echecs + 1))
fi

if [ "$echecs" -ne 0 ]; then
    echo "$echecs contrôle(s) en échec" >&2
    exit 1
fi
echo "ok : sauvegardes et restauration conformes"
