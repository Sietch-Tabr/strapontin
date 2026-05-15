package fr.strapontin.core;

public record ConcertBrut(
    String artiste,
    String date,
    String heure,
    String salle,
    String ville,
    String dept,
    Double prixMin,
    Double prixMax,
    String url,
    String source,
    String classification,
    String genre,
    String statut
) {}
