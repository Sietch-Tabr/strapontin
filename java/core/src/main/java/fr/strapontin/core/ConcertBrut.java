/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Concert brut tel que renvoyé par une source (TM, Eventim), avant dédoublonnage/résolution du
 * référentiel. Artiste, date, lieu et source sont obligatoires ; se construit par {@link
 * #nouveau()}.
 *
 * @param artiste nom de l'artiste
 * @param artisteSourceId identifiant préfixé de l'artiste chez la source, peut être null
 * @param date date ISO ({@code yyyy-MM-dd})
 * @param heure heure ({@code HH:mm}), peut être null
 * @param lieu lieu publié
 * @param url fiche de l'événement, peut être null
 * @param source source ({@code ticketmaster}, {@code eventim})
 * @param classification classification normalisée, peut être null
 * @param genre genre, peut être null
 * @param vente statut de vente, {@link InfosVente#INCONNUE} à défaut
 * @since 2.0.0
 * @author Kwaite
 */
public record ConcertBrut(
    String artiste,
    String artisteSourceId,
    String date,
    String heure,
    LieuBrut lieu,
    String url,
    String source,
    String classification,
    String genre,
    InfosVente vente) {

  /** Valide la présence des champs obligatoires. */
  public ConcertBrut {
    Objects.requireNonNull(artiste, "artiste");
    Objects.requireNonNull(date, "date");
    Objects.requireNonNull(lieu, "lieu");
    Objects.requireNonNull(source, "source");
    vente = vente != null ? vente : InfosVente.INCONNUE;
  }

  /**
   * @return un constructeur de concert, champs nommés
   */
  public static Constructeur nouveau() {
    return new Constructeur();
  }

  /**
   * @return un constructeur initialisé avec les champs de ce concert
   */
  public Constructeur copier() {
    return new Constructeur()
        .artiste(artiste)
        .artisteSourceId(artisteSourceId)
        .date(date)
        .heure(heure)
        .salle(lieu.salle())
        .ville(lieu.ville())
        .dept(lieu.dept())
        .salleSourceId(lieu.salleSourceId())
        .position(lieu.position())
        .url(url)
        .source(source)
        .classification(classification)
        .genre(genre)
        .vente(vente);
  }

  /**
   * @return nom de la salle
   */
  public String salle() {
    return lieu.salle();
  }

  /**
   * @return ville, peut être null
   */
  public String ville() {
    return lieu.ville();
  }

  /**
   * @return département, peut être null
   */
  public String dept() {
    return lieu.dept();
  }

  /**
   * @return identifiant préfixé du lieu chez la source, peut être null
   */
  public String salleSourceId() {
    return lieu.salleSourceId();
  }

  /**
   * @return position publiée du lieu
   */
  public PositionLieu position() {
    return lieu.position();
  }

  /**
   * @return statut de vente, peut être null
   */
  public String statut() {
    return vente.statut();
  }

  /**
   * @return début de prévente, peut être null
   */
  public String presaleDebut() {
    return vente.presaleDebut();
  }

  /**
   * @return nom de la prévente, peut être null
   */
  public String presaleNom() {
    return vente.presaleNom();
  }

  /** Construction d'un {@link ConcertBrut} par champs nommés. */
  public static final class Constructeur {

    private String artiste;
    private String artisteSourceId;
    private String date;
    private String heure;
    private String salle;
    private String ville;
    private String dept;
    private String salleSourceId;
    private PositionLieu position;
    private String url;
    private String source;
    private String classification;
    private String genre;
    private String statut;
    private String presaleDebut;
    private String presaleNom;

    private Constructeur() {}

    /**
     * @param valeur nom de l'artiste
     * @return ce constructeur
     */
    public Constructeur artiste(final String valeur) {
      this.artiste = valeur;
      return this;
    }

    /**
     * @param valeur identifiant préfixé de l'artiste chez la source
     * @return ce constructeur
     */
    public Constructeur artisteSourceId(final String valeur) {
      this.artisteSourceId = valeur;
      return this;
    }

    /**
     * @param valeur date ISO
     * @return ce constructeur
     */
    public Constructeur date(final String valeur) {
      this.date = valeur;
      return this;
    }

    /**
     * @param valeur heure {@code HH:mm}
     * @return ce constructeur
     */
    public Constructeur heure(final String valeur) {
      this.heure = valeur;
      return this;
    }

    /**
     * @param valeur nom de la salle
     * @return ce constructeur
     */
    public Constructeur salle(final String valeur) {
      this.salle = valeur;
      return this;
    }

    /**
     * @param valeur ville
     * @return ce constructeur
     */
    public Constructeur ville(final String valeur) {
      this.ville = valeur;
      return this;
    }

    /**
     * @param valeur département
     * @return ce constructeur
     */
    public Constructeur dept(final String valeur) {
      this.dept = valeur;
      return this;
    }

    /**
     * @param valeur identifiant préfixé du lieu chez la source
     * @return ce constructeur
     */
    public Constructeur salleSourceId(final String valeur) {
      this.salleSourceId = valeur;
      return this;
    }

    /**
     * @param valeur position publiée du lieu
     * @return ce constructeur
     */
    public Constructeur position(final PositionLieu valeur) {
      this.position = valeur;
      return this;
    }

    /**
     * @param valeur fiche de l'événement
     * @return ce constructeur
     */
    public Constructeur url(final String valeur) {
      this.url = valeur;
      return this;
    }

    /**
     * @param valeur source
     * @return ce constructeur
     */
    public Constructeur source(final String valeur) {
      this.source = valeur;
      return this;
    }

    /**
     * @param valeur classification
     * @return ce constructeur
     */
    public Constructeur classification(final String valeur) {
      this.classification = valeur;
      return this;
    }

    /**
     * @param valeur genre
     * @return ce constructeur
     */
    public Constructeur genre(final String valeur) {
      this.genre = valeur;
      return this;
    }

    /**
     * @param valeur statut de vente
     * @return ce constructeur
     */
    public Constructeur statut(final String valeur) {
      this.statut = valeur;
      return this;
    }

    /**
     * @param valeur début de prévente
     * @return ce constructeur
     */
    public Constructeur presaleDebut(final String valeur) {
      this.presaleDebut = valeur;
      return this;
    }

    /**
     * @param valeur nom de la prévente
     * @return ce constructeur
     */
    public Constructeur presaleNom(final String valeur) {
      this.presaleNom = valeur;
      return this;
    }

    /**
     * @param valeur statut de vente complet
     * @return ce constructeur
     */
    public Constructeur vente(final InfosVente valeur) {
      this.statut = valeur.statut();
      this.presaleDebut = valeur.presaleDebut();
      this.presaleNom = valeur.presaleNom();
      return this;
    }

    /**
     * @return le concert
     * @throws NullPointerException si artiste, date, salle ou source manque
     */
    public ConcertBrut construire() {
      return new ConcertBrut(
          artiste,
          artisteSourceId,
          date,
          heure,
          new LieuBrut(salle, ville, dept, salleSourceId, position),
          url,
          source,
          classification,
          genre,
          new InfosVente(statut, presaleDebut, presaleNom));
    }
  }
}
