#!/bin/bash
# verrou.sh — verrou d'exploitation partagé entre les scripts et les modules Java (classe Verrou
# de la bibliothèque exploitation-commun) : deux opérations simultanées sur un LXC (mise à jour,
# restauration, rotation des secrets) s'excluent. À sourcer après chemins.sh.
#
# Protocole commun aux deux langages : le verrou est le répertoire VERROU_DIR, créé de façon
# atomique (mkdir). Il contient un fichier « proprietaire » de trois lignes : PID, heure de
# démarrage du processus (champ 22 de /proc/<pid>/stat, « - » hors Linux) et nom de l'opération.
# Un verrou dont le propriétaire a disparu est périmé : il est écarté par un renommage atomique,
# qu'un seul concurrent remporte, puis recréé. Un flock ne convient pas : le verrou fcntl de Java
# et flock(1) sont indépendants sous Linux.
#
#   prendre_verrou <opération>   refuse aussitôt (code 1) si un propriétaire vivant tient le verrou
#   liberer_verrou               libère le verrou, seulement s'il est à ce processus
#
# VERROU_HERITE=1 : le processus appelant (le module de mise à jour) tient déjà le verrou ; on
# vérifie qu'il existe et que son propriétaire est vivant, sans le reprendre ni le libérer.

DELAI_SANS_PROPRIETAIRE_MINUTES=5

# Heure de démarrage d'un processus en ticks, « - » si illisible (autre système que Linux).
ticks_processus() {
    local statistiques
    statistiques=$(cat "/proc/$1/stat" 2>/dev/null) || { echo "-"; return 0; }
    # Le nom de commande peut contenir espaces et parenthèses : on repart de la dernière.
    statistiques="${statistiques##*) }"
    set -- $statistiques
    echo "${20:--}"
}

# Code 0 si le propriétaire décrit dans le répertoire ($1) est vivant.
proprietaire_vivant() {
    local pid ticks
    { read -r pid && read -r ticks; } < "$1/proprietaire" 2>/dev/null || return 1
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
    [ "$ticks" = "-" ] && return 0
    [ "$(ticks_processus "$pid")" = "$ticks" ] || [ "$(ticks_processus "$pid")" = "-" ]
}

decrire_proprietaire() {
    local pid operation
    { read -r pid; read -r _; read -r operation; } < "$VERROU_DIR/proprietaire" 2>/dev/null || true
    echo "${operation:-inconnue} (processus ${pid:-?})"
}

ecrire_proprietaire() {
    printf '%s\n%s\n%s\n' "$$" "$(ticks_processus "$$")" "$1" > "$VERROU_DIR/proprietaire.tmp"
    mv "$VERROU_DIR/proprietaire.tmp" "$VERROU_DIR/proprietaire"
}

prendre_verrou() {
    local operation="$1" essai
    if [ "${VERROU_HERITE:-}" = "1" ]; then
        proprietaire_vivant "$VERROU_DIR" && return 0
        echo "ERREUR : verrou hérité absent ou périmé ($VERROU_DIR)" >&2
        return 1
    fi
    for essai in 1 2 3; do
        if mkdir "$VERROU_DIR" 2>/dev/null; then
            ecrire_proprietaire "$operation"
            VERROU_PRIS=1
            return 0
        fi
        if [ -f "$VERROU_DIR/proprietaire" ]; then
            if proprietaire_vivant "$VERROU_DIR"; then
                echo "ERREUR : opération en cours : $(decrire_proprietaire)" >&2
                return 1
            fi
        elif [ -n "$(find "$VERROU_DIR" -maxdepth 0 -mmin "-$DELAI_SANS_PROPRIETAIRE_MINUTES" 2>/dev/null)" ]; then
            echo "ERREUR : verrou en cours de création ($VERROU_DIR)" >&2
            return 1
        fi
        # Verrou périmé : un seul concurrent remporte le renommage, les autres réessaient.
        if mv "$VERROU_DIR" "$VERROU_DIR.perime.$$.$essai" 2>/dev/null; then
            rm -rf "$VERROU_DIR.perime.$$.$essai"
            echo "Verrou périmé écarté ($VERROU_DIR)" >&2
        fi
    done
    echo "ERREUR : verrou disputé ($VERROU_DIR)" >&2
    return 1
}

liberer_verrou() {
    [ "${VERROU_PRIS:-}" = "1" ] || return 0
    local pid
    { read -r pid; } < "$VERROU_DIR/proprietaire" 2>/dev/null || true
    [ "$pid" = "$$" ] && rm -rf "$VERROU_DIR"
    VERROU_PRIS=0
    return 0
}
