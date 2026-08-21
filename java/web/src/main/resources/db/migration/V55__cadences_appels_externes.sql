-- V55__cadences_appels_externes.sql
--
-- Le cadenceur en mémoire de TicketmasterSource (module sources) n'espace que les appels d'un
-- seul processus. Or Ticketmaster est aussi appelé directement par ce module web (recherche
-- interactive de salle à l'ajout, test de clé API admin), un processus indépendant du worker : les
-- deux ne peuvent pas se coordonner via un verrou en mémoire. Cette table sert de cadenceur
-- partagé — une ligne par service externe cadencé, réservation atomique par
-- UPDATE ... RETURNING prochain_appel_autorise.
CREATE TABLE cadences_appels_externes (
    cle                     text PRIMARY KEY,
    prochain_appel_autorise timestamptz NOT NULL
);

INSERT INTO cadences_appels_externes (cle, prochain_appel_autorise)
VALUES ('ticketmaster', now());
