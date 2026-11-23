#!/bin/bash
# tester-systeme.sh — contrôle, sans LXC ni PostgreSQL, les scripts qui posent et vérifient les
# réglages PostgreSQL, l'accès réseau et le retard des mises à jour. Des substituts de psql, sshd,
# nft, fail2ban-client, apt-get et curl devant le PATH rejouent les cas d'écart.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAUX="$TMP/faux"
mkdir -p "$FAUX" "$TMP/conf.d"
export PATH="$FAUX:$PATH"
export ENV_FICHIER="$TMP/strapontin.env"
printf 'SPRING_PROFILES_ACTIVE=dev\nINTEGRITY_INTERNAL_SECRET=secret-de-test\n' > "$ENV_FICHIER"

echecs=0
verifier() { if ! "$@"; then echo "ECHEC : $*" >&2; echecs=$((echecs + 1)); fi; }
refuser() { if "$@" > /dev/null 2>&1; then echo "ECHEC (réussi à tort) : $*" >&2; echecs=$((echecs + 1)); fi; }

# --- reglages-postgresql.sh verifier -----------------------------------------------------------
cat > "$FAUX/psql" <<'FAUX_PSQL'
#!/bin/bash
sql="$*"
case "$sql" in
    *pg_settings*) echo "${FAUX_SOURCE}|${FAUX_PENDING}" ;;
    *pg_extension*) echo "${FAUX_EXTENSION}" ;;
    *shared_preload_libraries*) echo "pg_stat_statements" ;;
esac
FAUX_PSQL
chmod +x "$FAUX/psql"
reglages() {
    PSQL_ADMIN="$FAUX/psql" PG_VERSION=19 PG_CONF_D="$TMP/conf.d" bash "$RACINE/reglages-postgresql.sh" verifier
}
CIBLE="$TMP/conf.d/strapontin.conf"
export FAUX_SOURCE="$CIBLE" FAUX_PENDING=f FAUX_EXTENSION=1
verifier reglages
FAUX_SOURCE="/etc/postgresql/19/main/postgresql.conf" refuser reglages
FAUX_PENDING=t refuser reglages
FAUX_EXTENSION=0 refuser reglages
verifier bash -c "grep -q \"shared_preload_libraries = 'pg_stat_statements'\" '$RACINE/reglages-postgresql.sh'"
# Aucun paramètre de requête n'est journalisé.
verifier bash -c "grep -q '^log_parameter_max_length = 0' '$RACINE/reglages-postgresql.sh'"

# La version du cluster se lit dans pg_lsclusters (colonnes : version, cluster, port, statut).
cat > "$FAUX/pg_lsclusters" <<'FAUX_LS'
#!/bin/bash
echo "18  main    5433 down   postgres /var/lib/postgresql/18/main /var/log/postgresql/postgresql-18-main.log"
echo "19  main    5432 online postgres /var/lib/postgresql/19/main /var/log/postgresql/postgresql-19-main.log"
FAUX_LS
chmod +x "$FAUX/pg_lsclusters"
version_lue() {
    env -u PG_VERSION -u PG_CONF_D PSQL_ADMIN="$FAUX/psql" \
        FAUX_SOURCE=/etc/postgresql/19/main/conf.d/strapontin.conf \
        bash "$RACINE/reglages-postgresql.sh" verifier
}
verifier version_lue

# --- durcissement-acces.sh verifier ------------------------------------------------------------
cat > "$FAUX/sshd" <<'FAUX_SSHD'
#!/bin/bash
cat "$FAUX_SSHD_SORTIE"
FAUX_SSHD
cat > "$FAUX/fail2ban-client" <<'FAUX_F2B'
#!/bin/bash
[ "${FAUX_F2B:-actif}" = "actif" ]
FAUX_F2B
cat > "$FAUX/nft" <<'FAUX_NFT'
#!/bin/bash
echo "type filter hook input priority filter; policy ${FAUX_NFT_POLITIQUE:-drop};"
FAUX_NFT
chmod +x "$FAUX/sshd" "$FAUX/fail2ban-client" "$FAUX/nft"
BON="$TMP/sshd-bon.txt"
printf 'passwordauthentication no\nkbdinteractiveauthentication no\npermitrootlogin prohibit-password\nx11forwarding no\nmaxauthtries 3\nlogingracetime 30\n' > "$BON"
MAUVAIS="$TMP/sshd-mauvais.txt"
sed 's/^passwordauthentication no/passwordauthentication yes/' "$BON" > "$MAUVAIS"
acces() { SSHD_CONF="$TMP/sshd.conf" bash "$RACINE/durcissement-acces.sh" verifier; }
export FAUX_SSHD_SORTIE="$BON"
verifier acces
FAUX_SSHD_SORTIE="$MAUVAIS" refuser acces
FAUX_F2B=inactif refuser acces
FAUX_NFT_POLITIQUE=accept refuser acces

# --- controle-systeme.sh -----------------------------------------------------------------------
LIVRAISON="$TMP/exploitation"
mkdir -p "$LIVRAISON"
cp "$RACINE/chemins.sh" "$RACINE/controle-systeme.sh" "$LIVRAISON/"
printf '#!/bin/bash\nexit 0\n' > "$LIVRAISON/reglages-postgresql.sh"
printf '#!/bin/bash\nexit 0\n' > "$LIVRAISON/durcissement-acces.sh"
cat > "$FAUX/apt-get" <<'FAUX_APT'
#!/bin/bash
i=0
while [ "$i" -lt "${FAUX_PAQUETS:-0}" ]; do i=$((i + 1)); echo "Inst paquet$i [1] (2 Debian)"; done
FAUX_APT
cat > "$FAUX/curl" <<'FAUX_CURL'
#!/bin/bash
echo "$*" >> "$FAUX_APPELS"
FAUX_CURL
chmod +x "$FAUX/apt-get" "$FAUX/curl"
export FAUX_APPELS="$TMP/appels.txt"
export ETAT_RETARD="$TMP/retard"
controle() { bash "$LIVRAISON/controle-systeme.sh"; }

: > "$FAUX_APPELS"; rm -f "$ETAT_RETARD"
FAUX_PAQUETS=0 verifier controle
verifier test ! -s "$FAUX_APPELS"
# Des paquets en attente depuis peu ne sont pas un retard.
FAUX_PAQUETS=3 verifier controle
verifier test ! -s "$FAUX_APPELS"
# En attente depuis plus de quatorze jours : écart signalé à l'application.
echo "$(( $(date +%s) - 20 * 86400 ))" > "$ETAT_RETARD"
FAUX_PAQUETS=3 verifier controle
verifier grep -q 'statut=ecart_systeme' "$FAUX_APPELS"
verifier grep -q 'Authorization: Bearer secret-de-test' "$FAUX_APPELS"
# Plus rien en attente : le compteur d'ancienneté est remis à zéro.
FAUX_PAQUETS=0 verifier controle
verifier test ! -f "$ETAT_RETARD"
# Un contrôle en écart est signalé avec ses lignes.
printf '#!/bin/bash\necho "[ECART] jit : valeur ailleurs"\nexit 1\n' > "$LIVRAISON/reglages-postgresql.sh"
: > "$FAUX_APPELS"
verifier controle
verifier grep -q 'statut=ecart_systeme' "$FAUX_APPELS"
verifier grep -q 'jit' "$FAUX_APPELS"

# --- Syntaxe de tous les scripts livrés --------------------------------------------------------
for script in reglages-postgresql.sh durcissement-acces.sh controle-systeme.sh migrer-postgresql.sh \
    planifier-mise-a-jour-systeme.sh provision.sh update.sh; do
    verifier bash -n "$RACINE/$script"
done
# La migration refuse sans snapshot et sans versions cohérentes.
refuser bash "$RACINE/migrer-postgresql.sh" migrer 18 19
refuser bash "$RACINE/migrer-postgresql.sh" verifier 19 18

if [ "$echecs" -ne 0 ]; then
    echo "$echecs contrôle(s) en échec" >&2
    exit 1
fi
echo "système : réglages PostgreSQL, accès réseau, retard des mises à jour"
