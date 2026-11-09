package de.brasi.nova.taskmanagement.task;

/**
 * HATEOAS-Konstanten (Rels, Targets, Titles, Specs) fuer die {@code Task}-Entitaet (PLAN #141).
 *
 * <p>Handgepflegtes Gegenstueck zum scaffold-generierten {@code MenuHateoas} der application. Die
 * Konstanten binden die deklarativen Affordance-Regeln ({@code @LinkRule}/{@code @HateoasLinkTarget}
 * am {@code TaskFacadeController} und den Sibling-Controllern) an stabile Namen, damit Regel, Ziel
 * und Bedingung nicht via loser String-Literale auseinanderlaufen koennen.
 *
 * <p>Slice 1 fuehrt {@code self} (GET-Detail) ein; Slice 3 ergaenzt alle permission-/membership-
 * gegateten Schreib-Rels sowie die Collection-Rels (create/create-from-source).
 */
public final class TaskHateoas {

  private TaskHateoas() {}

  /** {@code rel}-Werte der Affordance-Links. */
  public static final class Rels {
    private Rels() {}

    /** Selbst-Referenz auf das Aufgaben-Detail. */
    public static final String SELF = "self";

    /** Item-Rel: Aufgabe patchen (PATCH /tasks/{id}). */
    public static final String UPDATE = "update";

    /** Item-Rel: Kommentare lesen (GET /tasks/{id}/comments). */
    public static final String COMMENTS = "comments";

    /** Item-Rel: Kommentar erstellen (POST /tasks/{id}/comments). */
    public static final String ADD_COMMENT = "add-comment";

    /** Item-Rel: Assignees aktualisieren (PUT /tasks/{id}/assignees). */
    public static final String ASSIGNEES = "assignees";

    /** Item-Rel: Teilnehmer hinzufuegen (POST /tasks/{id}/participants). */
    public static final String PARTICIPANTS = "participants";

    /** Item-Rel: Aufgabe verlassen (POST /tasks/{id}/exit). */
    public static final String EXIT = "exit";

    /** Item-Rel: Pin-Marker setzen (PUT /tasks/{id}/pin). */
    public static final String PIN = "pin";

    /** Item-Rel: Flag-Marker setzen (PUT /tasks/{id}/flag). */
    public static final String FLAG = "flag";

    /** Item-Rel: Mute-Marker setzen (PUT /tasks/{id}/mute). */
    public static final String MUTE = "mute";

    /** Collection-Rel: Aufgabe anlegen (POST /tasks). */
    public static final String CREATE = "create";

    /** Collection-Rel: Aufgabe aus Quellkontext anlegen (POST /tasks/from-source). */
    public static final String CREATE_FROM_SOURCE = "create-from-source";
  }

  /**
   * {@code linkTo}-Zielnamen der Controller-Handler ({@code @HateoasLinkTarget}). Stabile, fachlich
   * praefixierte Schluessel, die der KSF-{@code LinkRuleScanner} auf die Handler-Methode aufloest.
   */
  public static final class Targets {
    private Targets() {}

    /** GET {@code /api/v1/tasks/{id}} (Aufgaben-Detail). */
    public static final String GET_DETAIL = "task.getDetail";

    /** PATCH {@code /api/v1/tasks/{id}} (Aufgabe patchen). */
    public static final String PATCH = "task.patch";

    /** POST {@code /api/v1/tasks} (Aufgabe anlegen). */
    public static final String CREATE = "task.create";

    /** POST {@code /api/v1/tasks/from-source} (Aufgabe aus Quellkontext anlegen). */
    public static final String CREATE_FROM_SOURCE = "task.createFromSource";

    /** GET {@code /api/v1/tasks/{id}/comments} (Kommentare lesen). */
    public static final String GET_COMMENTS = "comment.getComments";

    /** POST {@code /api/v1/tasks/{id}/comments} (Kommentar erstellen). */
    public static final String CREATE_COMMENT = "comment.createComment";

    /** PUT {@code /api/v1/tasks/{id}/assignees} (Assignees aktualisieren). */
    public static final String UPDATE_ASSIGNEES = "assignee.updateAssignees";

    /** POST {@code /api/v1/tasks/{id}/participants} (Teilnehmer hinzufuegen). */
    public static final String ADD_PARTICIPANTS = "assignee.addParticipants";

    /** POST {@code /api/v1/tasks/{id}/exit} (Aufgabe verlassen). */
    public static final String EXIT = "assignee.exit";

    /** PUT {@code /api/v1/tasks/{id}/pin} (Pin-Marker setzen). */
    public static final String PIN = "userState.pin";

    /** PUT {@code /api/v1/tasks/{id}/flag} (Flag-Marker setzen). */
    public static final String FLAG = "userState.flag";

    /** PUT {@code /api/v1/tasks/{id}/mute} (Mute-Marker setzen). */
    public static final String MUTE = "userState.mute";
  }

  /** {@code title}-Texte der Affordance-Links (deutsch, FE-Anzeige). */
  public static final class Titles {
    private Titles() {}

    /** Titel des {@code self}-Links. */
    public static final String SELF = "Aufgaben-Detail anzeigen";

    /** Titel des {@code update}-Links. */
    public static final String UPDATE = "Aufgabe bearbeiten";

    /** Titel des {@code comments}-Links. */
    public static final String COMMENTS = "Kommentare lesen";

    /** Titel des {@code add-comment}-Links. */
    public static final String ADD_COMMENT = "Kommentar erstellen";

    /** Titel des {@code assignees}-Links. */
    public static final String ASSIGNEES = "Assignees verwalten";

    /** Titel des {@code participants}-Links. */
    public static final String PARTICIPANTS = "Teilnehmer hinzufuegen";

    /** Titel des {@code exit}-Links. */
    public static final String EXIT = "Aufgabe verlassen";

    /** Titel des {@code pin}-Links. */
    public static final String PIN = "Pin-Marker setzen";

    /** Titel des {@code flag}-Links. */
    public static final String FLAG = "Flag-Marker setzen";

    /** Titel des {@code mute}-Links. */
    public static final String MUTE = "Mute-Marker setzen";

    /** Titel des {@code create}-Links. */
    public static final String CREATE = "Aufgabe anlegen";

    /** Titel des {@code create-from-source}-Links. */
    public static final String CREATE_FROM_SOURCE = "Aufgabe aus Quellkontext anlegen";
  }

  /**
   * Namen der {@code LinkSpecification}-Beans ({@code conditions}). MUESSEN exakt den {@code
   * getName()}-Rueckgaben der Specs in {@code TaskLinkSpecifications} entsprechen.
   */
  public static final class Specs {
    private Specs() {}

    /**
     * Bedingung: der Akteur ist WRITE-Member der Aufgabe (aktive, nicht ausgetretene PERSON-Zeile).
     */
    public static final String IS_WRITE_MEMBER = "task.isWriteMember";

    /**
     * Bedingung: der Anfrager traegt die exakte Authority {@code task-management:TASK_WRITE}.
     */
    public static final String HAS_WRITE_AUTHORITY = "task.hasWriteAuthority";

    /**
     * Bedingung: der Anfrager traegt die exakte Authority {@code task-management:TASK_CREATE}.
     */
    public static final String HAS_CREATE_AUTHORITY = "task.hasCreateAuthority";

    /**
     * Bedingung: TASK_WRITE-Authority UND WRITE-Member (Konjunktion; fuer assignees/participants).
     */
    public static final String HAS_WRITE_AUTHORITY_AND_IS_MEMBER = "task.hasWriteAuthorityAndMember";
  }
}
