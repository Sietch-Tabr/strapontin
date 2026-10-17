-- Couverture des départements : nombre de concerts à venir relevé chaque nuit, depuis quand un
-- département n'en a plus aucun, et si l'admin a reconnu cette absence (couverture connue). Une
-- absence reconnue n'est plus signalée jusqu'à ce que le département retrouve des concerts.
CREATE TABLE couverture_departements (
    dept                 text         PRIMARY KEY,
    nb_concerts          integer      NOT NULL,
    sans_concert_depuis  date,
    couverture_connue_le timestamptz,
    mis_a_jour_le        timestamptz  NOT NULL DEFAULT now()
);
