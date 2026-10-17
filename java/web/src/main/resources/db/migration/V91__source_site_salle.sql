-- Concerts lus sur le site d'une salle qui vend hors Ticketmaster et Eventim. Une seule valeur
-- pour tous ces sites : le site se reconnaît à l'identifiant de salle (`site_salle:triton`...),
-- et la vue `concerts` les classe après les deux billetteries (priorité 4).
ALTER TYPE source_concert ADD VALUE IF NOT EXISTS 'site_salle';
