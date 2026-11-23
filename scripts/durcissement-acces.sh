#!/bin/bash
set -euo pipefail

# durcissement-acces.sh — accès réseau du LXC : sshd (clé seulement), fail2ban sur sshd et pare-feu
# nftables en refus par défaut. Une seule définition (les fonctions *_attendu), appliquée puis
# contrôlée sur les valeurs réellement en vigueur.
# Usage (root, sur le LXC) :
#   durcissement-acces.sh appliquer   écrit les fichiers, vérifie avant de charger, applique
#   durcissement-acces.sh verifier    code 1 si une valeur en vigueur diffère de l'attendu
#   durcissement-acces.sh tester      valide les fichiers sans rien charger (sshd -t, nft -c)
#
# Avant de l'appliquer à distance, garder une session ouverte : une règle fautive coupe l'accès
# SSH, que seul « pct exec » depuis Proxmox rétablit.

. "$(dirname "$0")/chemins.sh"

SSHD_CONF="${SSHD_CONF:-/etc/ssh/sshd_config.d/strapontin.conf}"
FAIL2BAN_CONF="${FAIL2BAN_CONF:-/etc/fail2ban/jail.d/strapontin.local}"
NFT_CONF="${NFT_CONF:-/etc/nftables.conf}"
TABLE_NFT="strapontin_filtre"

profil() {
    lire_env SPRING_PROFILES_ACTIVE
}

# Réseaux privés du site : postes de gestion (VLAN 1), VLAN des LXC (20) et suivants.
RESEAU_PRIVE="192.168.0.0/16"
# Proxy inverse (Nginx Proxy Manager), seul client légitime du port applicatif en production.
PROXY_INVERSE="192.168.20.98"

sshd_attendu() {
    cat <<'CONF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
X11Forwarding no
MaxAuthTries 3
LoginGraceTime 30
CONF
}

fail2ban_attendu() {
    cat <<CONF
[DEFAULT]
backend = systemd
banaction = nftables
ignoreip = 127.0.0.1/8 ::1 $RESEAU_PRIVE

[sshd]
enabled = true
maxretry = 3
findtime = 10m
bantime = 1h
CONF
}

# Refus par défaut ; services connus ouverts au réseau privé, le port applicatif au seul proxy en
# production. Les ports 8081 (API interne du worker) et 5432 ne sont jamais ouverts.
nft_attendu() {
    local ouverture_web="ip saddr $RESEAU_PRIVE"
    [ "$(profil)" = "prod" ] && ouverture_web="ip saddr $PROXY_INVERSE"
    cat <<CONF
#!/usr/sbin/nft -f
# Géré par durcissement-acces.sh : ne pas modifier à la main.
table inet $TABLE_NFT
delete table inet $TABLE_NFT
table inet $TABLE_NFT {
    chain entree {
        type filter hook input priority 0; policy drop;
        ct state established,related accept
        ct state invalid drop
        iifname "lo" accept
        ip protocol icmp accept
        ip6 nexthdr ipv6-icmp accept
        tcp dport 22 ip saddr $RESEAU_PRIVE accept
        tcp dport 8080 $ouverture_web accept
        tcp dport { 9090, 9091 } ip saddr $RESEAU_PRIVE accept
    }
}
CONF
}

ecrire_si_different() {
    local cible="$1" temporaire
    temporaire="$(mktemp "$cible.XXXXXX")"
    cat > "$temporaire"
    chmod 0644 "$temporaire"
    if [ -f "$cible" ] && cmp -s "$temporaire" "$cible"; then
        rm -f "$temporaire"
        return 1
    fi
    mv "$temporaire" "$cible"
}

tester() {
    local temporaire
    temporaire="$(mktemp)"
    nft_attendu > "$temporaire"
    nft -c -f "$temporaire"
    rm -f "$temporaire"
    sshd -t
}

appliquer() {
    export DEBIAN_FRONTEND=noninteractive
    apt-get install -y -qq fail2ban nftables > /dev/null
    mkdir -p "$(dirname "$SSHD_CONF")" "$(dirname "$FAIL2BAN_CONF")"
    ecrire_si_different "$SSHD_CONF" < <(sshd_attendu) && echo "[acces] $SSHD_CONF écrit" || true
    ecrire_si_different "$FAIL2BAN_CONF" < <(fail2ban_attendu) && echo "[acces] $FAIL2BAN_CONF écrit" || true
    local temporaire
    temporaire="$(mktemp)"
    nft_attendu > "$temporaire"
    # Rien n'est chargé si un fichier est invalide.
    nft -c -f "$temporaire"
    sshd -t
    if [ ! -f "$NFT_CONF" ] || ! cmp -s "$temporaire" "$NFT_CONF"; then
        cp "$temporaire" "$NFT_CONF"
        echo "[acces] $NFT_CONF écrit"
    fi
    rm -f "$temporaire"
    systemctl enable --now nftables
    nft -f "$NFT_CONF"
    systemctl reload ssh
    systemctl enable fail2ban
    systemctl restart fail2ban
    verifier
}

# Une directive sshd en vigueur (sshd -T, clés en minuscules). sshd affiche « without-password »
# pour « prohibit-password », son alias historique.
valeur_sshd() {
    local sortie
    sortie="$(sshd -T)"
    awk -v cle="$(echo "$1" | tr 'A-Z' 'a-z')" '$1 == cle { print ($2 == "without-password" ? "prohibit-password" : $2); exit }' <<< "$sortie"
}

verifier() {
    local ecarts=0 cle valeur attendu
    while read -r cle valeur; do
        attendu="$(echo "$valeur" | tr 'A-Z' 'a-z')"
        if [ "$(valeur_sshd "$cle")" = "$attendu" ]; then
            echo "[OK]    sshd $cle $valeur"
        else
            echo "[ECART] sshd $cle : attendu $valeur, en vigueur $(valeur_sshd "$cle")"
            ecarts=$((ecarts + 1))
        fi
    done < <(sshd_attendu)
    if fail2ban-client status sshd > /dev/null 2>&1; then
        echo "[OK]    fail2ban sshd actif"
    else
        echo "[ECART] fail2ban : la prison sshd n'est pas active"
        ecarts=$((ecarts + 1))
    fi
    # Sortie capturée : « grep -q » ferme le tube et ferait échouer nft sous pipefail.
    chaine="$(nft list chain inet "$TABLE_NFT" entree 2> /dev/null || true)"
    if grep -q 'policy drop' <<< "$chaine"; then
        echo "[OK]    nftables entree en refus par défaut"
    else
        echo "[ECART] nftables : la chaîne $TABLE_NFT/entree n'est pas en refus par défaut"
        ecarts=$((ecarts + 1))
    fi
    if [ "$ecarts" -ne 0 ]; then
        echo "$ecarts écart(s) sur l'accès réseau du LXC" >&2
        return 1
    fi
}

case "${1:-}" in
    appliquer) appliquer ;;
    verifier) verifier ;;
    tester) tester ;;
    *) echo "Usage : $0 appliquer|verifier|tester" >&2; exit 1 ;;
esac
