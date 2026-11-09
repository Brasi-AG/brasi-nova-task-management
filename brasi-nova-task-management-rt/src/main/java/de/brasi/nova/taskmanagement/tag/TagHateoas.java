package de.brasi.nova.taskmanagement.tag;

/**
 * HATEOAS-Konstanten (Rels, Targets, Titles, Specs) fuer die {@code TaskTag}-Entitaet (PLAN #141
 * Slice 2).
 *
 * <p>Handgepflegtes Gegenstueck zum scaffold-generierten {@code MenuHateoas} der application und zum
 * {@code TaskHateoas} der Task-Lane (Slice 1). Die Konstanten binden die deklarativen
 * Affordance-Regeln ({@code @LinkRule}/{@code @CollectionLinkRule}/{@code @HateoasLinkTarget} am
 * {@code TagController}) an stabile Namen, damit Regel, Ziel und Bedingung nicht via loser
 * String-Literale auseinanderlaufen koennen.
 *
 * <p>Rel-Matrix (SPEC #141): {@code create} (COLLECTION), {@code update}/{@code deactivate}/{@code
 * delete} (ITEM). KEIN {@code self}-Item-Link (es gibt kein {@code GET /tags/{id}}). Alle Rels sind
 * TAG_ADMIN-gegatet; {@code deactivate} zusaetzlich nur bei {@code modelState=ACTIVE}.
 */
public final class TagHateoas {

  private TagHateoas() {}

  /** {@code rel}-Werte der Affordance-Links. */
  public static final class Rels {
    private Rels() {}

    /** Collection-Rel: einen neuen Tag anlegen (POST /tags). */
    public static final String CREATE = "create";

    /** Item-Rel: einen Tag aktualisieren (PUT /tags/{id}). */
    public static final String UPDATE = "update";

    /** Item-Rel: einen aktiven Tag deaktivieren/retiren (POST /tags/{id}/deactivate). */
    public static final String DEACTIVATE = "deactivate";

    /** Item-Rel: einen Tag loeschen (DELETE /tags/{id}). */
    public static final String DELETE = "delete";
  }

  /**
   * {@code linkTo}-Zielnamen der Controller-Handler ({@code @HateoasLinkTarget}). Stabile, fachlich
   * praefixierte Schluessel, die der KSF-{@code LinkRuleScanner} auf die Handler-Methode aufloest.
   */
  public static final class Targets {
    private Targets() {}

    /** POST {@code /api/v1/tags} (Tag anlegen). */
    public static final String CREATE = "tag.create";

    /** PUT {@code /api/v1/tags/{id}} (Tag aktualisieren). */
    public static final String UPDATE = "tag.update";

    /** POST {@code /api/v1/tags/{id}/deactivate} (Tag deaktivieren). */
    public static final String DEACTIVATE = "tag.deactivate";

    /** DELETE {@code /api/v1/tags/{id}} (Tag loeschen). */
    public static final String DELETE = "tag.delete";
  }

  /** {@code title}-Texte der Affordance-Links (deutsch, FE-Anzeige). */
  public static final class Titles {
    private Titles() {}

    /** Titel des {@code create}-Links. */
    public static final String CREATE = "Tag anlegen";

    /** Titel des {@code update}-Links. */
    public static final String UPDATE = "Tag bearbeiten";

    /** Titel des {@code deactivate}-Links. */
    public static final String DEACTIVATE = "Tag deaktivieren";

    /** Titel des {@code delete}-Links. */
    public static final String DELETE = "Tag loeschen";
  }

  /**
   * Namen der {@code LinkSpecification}-Beans ({@code conditions}). MUESSEN exakt den {@code
   * getName()}-Rueckgaben der Specs in {@code TagLinkSpecifications} entsprechen.
   */
  public static final class Specs {
    private Specs() {}

    /** Bedingung: der Anfrager traegt die exakte Authority {@code task-management:TAG_ADMIN}. */
    public static final String TAG_ADMIN = "tag.isTagAdmin";

    /** Bedingung: der Tag ist aktiv ({@code modelState=ACTIVE}). */
    public static final String IS_ACTIVE = "tag.isActive";
  }
}
