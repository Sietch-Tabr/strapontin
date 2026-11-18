#!/bin/bash
# tester-controle.sh — contrôle, sans LXC ni JVM, que les scripts de contrôle ne sont que des
# enveloppes du JAR exploitation-controle : commande et arguments transmis, code de sortie du JAR
# rendu tel quel, refus clair quand le JAR n'est pas livré. Le JAR est remplacé par un faux « java »
# qui journalise ses arguments.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export APP_JARS="$TMP/application" JOURNAL="$TMP/journal"
mkdir -p "$APP_JARS"
cat > "$TMP/java" <<'FIN'
#!/usr/bin/env bash
echo "$*" >> "$JOURNAL"
exit "${CODE_JAVA:-0}"
FIN
chmod +x "$TMP/java"
export JAVA="$TMP/java"

echecs=0
verifier() { if ! "$@"; then echo "ECHEC : $*" >&2; echecs=$((echecs + 1)); fi; }
lancer() { bash "$RACINE/$1" "${@:2}" > "$TMP/sortie" 2>&1 && CODE=0 || CODE=$?; }

# 1. JAR absent : refus clair, rien n'est lancé.
lancer aide-check.sh
verifier test "$CODE" -ne 0
verifier grep -q "exploitation-controle.jar absent" "$TMP/sortie"
verifier test ! -e "$JOURNAL"
# Pas de repli : une sauvegarde sans JAR échoue franchement plutôt que de ne rien dire.
lancer backup.sh
verifier test "$CODE" -ne 0
verifier grep -q "exploitation-controle.jar absent" "$TMP/sortie"
lancer backup-local.sh
verifier test "$CODE" -ne 0
lancer rotation-secrets.sh --tous
verifier test "$CODE" -ne 0
lancer restore.sh /x.sql.gz
verifier test "$CODE" -ne 0
verifier grep -q "exploitation-controle.jar absent" "$TMP/sortie"
verifier grep -q "exploitation-controle.jar absent" "$TMP/sortie"
verifier test ! -e "$JOURNAL"

# 2. JAR livré : commande et arguments transmis au JAR, dans l'ordre.
: > "$APP_JARS/exploitation-controle.jar"
lancer aide-check.sh
verifier test "$CODE" = 0
verifier grep -qx -- "-Xmx128m -jar $APP_JARS/exploitation-controle.jar aide-check" "$JOURNAL"
lancer declencher-job.sh purger:concerts
verifier grep -qx -- "-Xmx128m -jar $APP_JARS/exploitation-controle.jar declencher-job purger:concerts" "$JOURNAL"
lancer declencher-job.sh --simulation-salles
verifier grep -qx -- "-Xmx128m -jar $APP_JARS/exploitation-controle.jar declencher-job --simulation-salles" "$JOURNAL"
lancer backup.sh
verifier test "$CODE" = 0
verifier grep -qx -- "-Xmx128m -jar $APP_JARS/exploitation-controle.jar backup" "$JOURNAL"
lancer backup-local.sh
verifier grep -qx -- "-Xmx128m -jar $APP_JARS/exploitation-controle.jar backup-local" "$JOURNAL"
lancer restore.sh /sauvegardes/strapontin-prod_20261118_000000.sql.gz
verifier test "$CODE" = 0
verifier grep -qx -- "-Xmx128m -jar $APP_JARS/exploitation-controle.jar restore /sauvegardes/strapontin-prod_20261118_000000.sql.gz" "$JOURNAL"
lancer rotation-secrets.sh --db --backup
verifier grep -qx -- "-Xmx128m -jar $APP_JARS/exploitation-controle.jar rotation-secrets --db --backup" "$JOURNAL"

# 3. Le code de sortie du JAR est celui de l'enveloppe.
CODE_JAVA=1 lancer declencher-job.sh purger:concerts
verifier test "$CODE" = 1

if [ "$echecs" -ne 0 ]; then
    echo "$echecs contrôle(s) en échec" >&2
    exit 1
fi
echo "contrôle : enveloppes des scripts (commande et arguments transmis, code de sortie rendu, JAR absent refusé)"
