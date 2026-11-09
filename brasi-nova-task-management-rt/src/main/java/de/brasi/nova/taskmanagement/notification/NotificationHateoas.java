package de.brasi.nova.taskmanagement.notification;

/**
 * HATEOAS-Konstanten (Rels, Targets, Titles, Specs) fuer die {@code TaskNotification}-Entitaet
 * (PLAN #141 Slice 5, Teil A).
 *
 * <p>Handgepflegtes Gegenstueck zu {@code TagHateoas} und {@code TaskHateoas}. Die Konstanten
 * binden die deklarativen Affordance-Regeln ({@code @LinkRule}/{@code @CollectionLinkRule}/
 * {@code @HateoasLinkTarget} am {@code NotificationController}) an stabile Namen, damit Regel,
 * Ziel und Bedingung nicht via loser String-Literale auseinanderlaufen koennen.
 *
 * <p>Rel-Matrix (SPEC #141 Slice 5): Item-Rel {@code mark-read} (ITEM), Collection-Rel
 * {@code mark-all-read} (COLLECTION). Beide NOTIFICATION_WRITE-gegatet.
 */
public final class NotificationHateoas {

  private NotificationHateoas() {}

  /** {@code rel}-Werte der Affordance-Links. */
  public static final class Rels {
    private Rels() {}

    /** Item-Rel: eine einzelne Benachrichtigung als gelesen markieren (PUT /{id}/read). */
    public static final String MARK_READ = "mark-read";

    /** Collection-Rel: alle eigenen ungelesenen Benachrichtigungen als gelesen markieren (PUT /read-all). */
    public static final String MARK_ALL_READ = "mark-all-read";
  }

  /**
   * {@code linkTo}-Zielnamen der Controller-Handler ({@code @HateoasLinkTarget}). Stabile, fachlich
   * praefixierte Schluessel, die der KSF-{@code LinkRuleScanner} auf die Handler-Methode aufloest.
   */
  public static final class Targets {
    private Targets() {}

    /** PUT {@code /api/v1/notifications/{id}/read} (Einzelne Benachrichtigung als gelesen markieren). */
    public static final String MARK_READ = "notification.markRead";

    /** PUT {@code /api/v1/notifications/read-all} (Alle Benachrichtigungen als gelesen markieren). */
    public static final String MARK_ALL_READ = "notification.markAllRead";
  }

  /** {@code title}-Texte der Affordance-Links (deutsch, FE-Anzeige). */
  public static final class Titles {
    private Titles() {}

    /** Titel des {@code mark-read}-Links. */
    public static final String MARK_READ = "Als gelesen markieren";

    /** Titel des {@code mark-all-read}-Links. */
    public static final String MARK_ALL_READ = "Alle als gelesen markieren";
  }

  /**
   * Namen der {@code LinkSpecification}-Beans ({@code conditions}). MUESSEN exakt den {@code
   * getName()}-Rueckgaben der Specs in {@code NotificationLinkSpecifications} entsprechen.
   */
  public static final class Specs {
    private Specs() {}

    /** Bedingung: der Anfrager traegt die exakte Authority {@code task-management:NOTIFICATION_WRITE}. */
    public static final String NOTIFICATION_WRITE = "notification.hasWriteAuthority";
  }
}
