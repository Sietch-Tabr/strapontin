/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Objects;

/**
 * Concert brut renvoyé par une source, avant dédoublonnage et résolution.
 * Artiste, date, lieu et source obligatoires ; construit par {@link #nouveau()}.
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
    Classement classement,
    InfosVente vente) {

  /** Valide la présence des champs obligatoires. */
  public ConcertBrut {
    Objects.requireNonNull(artiste, "artiste");
    Objects.requireNonNull(date, "date");
    Objects.requireNonNull(lieu, "lieu");
    Objects.requireNonNull(source, "source");
    vente = vente != null ? vente : InfosVente.INCONNUE;
    classement = classement != null ? classement : Classement.INCONNU;
  }

  /**
   * @return classification (segment), peut être null
   */
  public String classification() {
    return classement.classification();
  }

  /**
   * @return genre, peut être null
   */
  public String genre() {
    return classement.genre();
  }

  /**
   * @return sous-genre, peut être null
   */
  public String sousGenre() {
    return classement.sousGenre();
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
        .classement(classement)
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

    private String nomArtiste;
    private String idArtisteSource;
    private String dateConcert;
    private String heureConcert;
    private String nomSalle;
    private String villeLieu;
    private String deptLieu;
    private String idLieuSource;
    private PositionLieu positionLieu;
    private String urlFiche;
    private String nomSource;
    private Classement classementConcert = Classement.INCONNU;
    private InfosVente infosVente = InfosVente.INCONNUE;

    private Constructeur() {}

    /**
     * @param valeur nom de l'artiste
     * @return ce constructeur
     */
    public Constructeur artiste(final String valeur) {
      this.nomArtiste = valeur;
      return this;
    }

    /**
     * @param valeur identifiant préfixé de l'artiste chez la source
     * @return ce constructeur
     */
    public Constructeur artisteSourceId(final String valeur) {
      this.idArtisteSource = valeur;
      return this;
    }

    /**
     * @param valeur date ISO
     * @return ce constructeur
     */
    public Constructeur date(final String valeur) {
      this.dateConcert = valeur;
      return this;
    }

    /**
     * @param valeur heure {@code HH:mm}
     * @return ce constructeur
     */
    public Constructeur heure(final String valeur) {
      this.heureConcert = valeur;
      return this;
    }

    /**
     * @param valeur nom de la salle
     * @return ce constructeur
     */
    public Constructeur salle(final String valeur) {
      this.nomSalle = valeur;
      return this;
    }

    /**
     * @param valeur ville
     * @return ce constructeur
     */
    public Constructeur ville(final String valeur) {
      this.villeLieu = valeur;
      return this;
    }

    /**
     * @param valeur département
     * @return ce constructeur
     */
    public Constructeur dept(final String valeur) {
      this.deptLieu = valeur;
      return this;
    }

    /**
     * @param valeur identifiant préfixé du lieu chez la source
     * @return ce constructeur
     */
    public Constructeur salleSourceId(final String valeur) {
      this.idLieuSource = valeur;
      return this;
    }

    /**
     * @param valeur position publiée du lieu
     * @return ce constructeur
     */
    public Constructeur position(final PositionLieu valeur) {
      this.positionLieu = valeur;
      return this;
    }

    /**
     * @param valeur fiche de l'événement
     * @return ce constructeur
     */
    public Constructeur url(final String valeur) {
      this.urlFiche = valeur;
      return this;
    }

    /**
     * @param valeur source
     * @return ce constructeur
     */
    public Constructeur source(final String valeur) {
      this.nomSource = valeur;
      return this;
    }

    /**
     * @param valeur classification
     * @return ce constructeur
     */
    public Constructeur classification(final String valeur) {
      this.classementConcert =
          new Classement(valeur, classementConcert.genre(), classementConcert.sousGenre());
      return this;
    }

    /**
     * @param valeur genre
     * @return ce constructeur
     */
    public Constructeur genre(final String valeur) {
      this.classementConcert =
          new Classement(classementConcert.classification(), valeur, classementConcert.sousGenre());
      return this;
    }

    /**
     * @param valeur sous-genre
     * @return ce constructeur
     */
    public Constructeur sousGenre(final String valeur) {
      this.classementConcert =
          new Classement(classementConcert.classification(), classementConcert.genre(), valeur);
      return this;
    }

    /**
     * @param valeur classement complet
     * @return ce constructeur
     */
    public Constructeur classement(final Classement valeur) {
      this.classementConcert = valeur != null ? valeur : Classement.INCONNU;
      return this;
    }

    /**
     * @param valeur statut de vente
     * @return ce constructeur
     */
    public Constructeur statut(final String valeur) {
      this.infosVente = new InfosVente(valeur, infosVente.presaleDebut(), infosVente.presaleNom());
      return this;
    }

    /**
     * @param valeur début de prévente
     * @return ce constructeur
     */
    public Constructeur presaleDebut(final String valeur) {
      this.infosVente = new InfosVente(infosVente.statut(), valeur, infosVente.presaleNom());
      return this;
    }

    /**
     * @param valeur nom de la prévente
     * @return ce constructeur
     */
    public Constructeur presaleNom(final String valeur) {
      this.infosVente = new InfosVente(infosVente.statut(), infosVente.presaleDebut(), valeur);
      return this;
    }

    /**
     * @param valeur statut de vente complet
     * @return ce constructeur
     */
    public Constructeur vente(final InfosVente valeur) {
      this.infosVente = valeur;
      return this;
    }

    /**
     * @return le concert
     * @throws NullPointerException si artiste, date, salle ou source manque
     */
    public ConcertBrut construire() {
      return new ConcertBrut(
          nomArtiste,
          idArtisteSource,
          dateConcert,
          heureConcert,
          new LieuBrut(nomSalle, villeLieu, deptLieu, idLieuSource, positionLieu),
          urlFiche,
          nomSource,
          classementConcert,
          infosVente);
    }
  }
}
