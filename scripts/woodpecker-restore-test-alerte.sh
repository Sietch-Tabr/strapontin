#!/bin/bash
set -euo pipefail

# woodpecker-restore-test-alerte.sh — Alerte email si le test de restauration
# hebdomadaire (restore-test) échoue.
# Secrets requis : resend_api_key, alerte_expediteur, alerte_destinataire —
# aucune adresse email codée en dur (dépôt public)

DE="${ALERTE_EXPEDITEUR}"
A="${ALERTE_DESTINATAIRE}"
SUJET="Strapontin — échec du test de restauration hebdomadaire"
CORPS="Le test de restauration automatique (pipeline #${CI_PIPELINE_NUMBER:-?}) a échoué.

Dépôt : ${CI_REPO:-Kwaite/strapontin}
Détails : ${CI_PIPELINE_URL:-non disponible}

Vérifier les logs du step restore-test avant de faire confiance au prochain backup."

CORPS_JSON=$(jq -n --arg de "$DE" --arg a "$A" --arg sujet "$SUJET" --arg corps "$CORPS" \
    '{from: $de, to: [$a], subject: $sujet, text: $corps}')

curl -sf --max-time 20 -X POST "https://api.resend.com/emails" \
    -H "Authorization: Bearer ${RESEND_API_KEY}" \
    -H "Content-Type: application/json" \
    -d "$CORPS_JSON"
