-- Date et statut HTTP du dernier envoi push refusé : un appareil qui ne reçoit rien est visible
-- de l'utilisateur dès le premier échec, sans attendre la révocation automatique (cinq échecs).
ALTER TABLE push_abonnements
    ADD COLUMN dernier_echec_le     timestamptz,
    ADD COLUMN dernier_echec_statut integer;
