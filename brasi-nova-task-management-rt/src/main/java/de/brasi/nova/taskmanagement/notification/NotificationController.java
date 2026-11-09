package de.brasi.nova.taskmanagement.notification;

import de.brasi.nova.taskmanagement.entity.TaskNotification;
import de.brasi.nova.taskmanagement.notification.model.NotificationModel;
import de.brasi.nova.taskmanagement.notification.model.NotificationReadInput;
import de.brasi.nova.taskmanagement.paging.CursorCollectionModel;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.hateoas.annotation.CollectionLinkRule;
import io.keel.framework.api.hateoas.annotation.EnableNovaHateoas;
import io.keel.framework.api.hateoas.annotation.HateoasLinkTarget;
import io.keel.framework.api.hateoas.annotation.LinkParam;
import io.keel.framework.api.hateoas.annotation.LinkRule;
import io.keel.framework.api.hateoas.annotation.LinkRules;
import io.keel.framework.api.security.permission.RequiresPermission;
import io.keel.framework.api.security.user.UserAccessor;
import io.keel.framework.api.openapi.annotation.ApiFindCollectionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handgeschriebener Facade-Controller fuer Benachrichtigungen (arch-v2 §7).
 *
 * <p>Jeder Handler traegt eine {@link RequiresPermission}-Annotation (Pflicht gemaess arch-v2 §8).
 * Actor-UUID kommt ausschliesslich aus dem Security-Context ({@link UserAccessor}),
 * NIEMALS aus Client-Eingabedaten.
 *
 * <p>Route-Reihenfolge: {@code /read-all} wird VOR {@code /{id}/read} gemappt, damit
 * Spring den konkreten Pfad vor dem variablen matcht und {@code id=read-all} nicht faengt.
 *
 * <p>PLAN #141 Slice 5 (Teil A): dieser Controller ist Regel-Owner der Affordance-Links fuer
 * {@link TaskNotification} ({@code @EnableNovaHateoas}). Die {@code @LinkRules} deklarieren
 * permission-gegatete HATEOAS-{@code _links}: Item-Rel {@code mark-read} am
 * {@code _embedded}-Item, Collection-Rel {@code mark-all-read} am Collection-Level. Beide
 * Rels sind NOTIFICATION_WRITE-gegatet ({@link NotificationHateoas.Specs#NOTIFICATION_WRITE}).
 * Die {@code _links} sind rein ADDITIV (FE-Anzeige) und veraendern die bestehende Enforcement
 * NICHT - beide Mutations-Handler tragen unveraendert ihr {@code @RequiresPermission(NOTIFICATION_WRITE)}.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@EnableNovaHateoas(entityClass = TaskNotification.class)
@LinkRules(
    value = {
      @LinkRule(
          rel = NotificationHateoas.Rels.MARK_READ,
          linkTo = NotificationHateoas.Targets.MARK_READ,
          title = NotificationHateoas.Titles.MARK_READ,
          conditions = {NotificationHateoas.Specs.NOTIFICATION_WRITE},
          params = {@LinkParam(name = "id", source = "#dto.id")})
    },
    collectionRules = {
      @CollectionLinkRule(
          rel = NotificationHateoas.Rels.MARK_ALL_READ,
          linkTo = NotificationHateoas.Targets.MARK_ALL_READ,
          title = NotificationHateoas.Titles.MARK_ALL_READ,
          conditions = {NotificationHateoas.Specs.NOTIFICATION_WRITE})
    })
public class NotificationController {

  private final NotificationQueryService queryService;
  private final UserAccessor userAccessor;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public NotificationController(
      final NotificationQueryService queryService,
      final UserAccessor userAccessor) {
    this.queryService = queryService;
    this.userAccessor = userAccessor;
  }

  /**
   * Liefert eine HAL-Collection von Benachrichtigungen fuer den angemeldeten Benutzer.
   *
   * <p>Slice 4: Return-Typ ist {@link CursorCollectionModel} (HAL-Collection mit top-level
   * Cursor-Feldern). Item- und Collection-rels (mark-read, mark-all-read) werden permission-gegatet
   * ueber die Notification-Link-Regeln ergaenzt.
   *
   * @param filter   "all" | "unread" (default: all)
   * @param cursor   opaker Cursor fuer Keyset-Paginierung (null = erste Seite)
   * @param limit    Seitengroesse (default 50, geclampt auf [1, 100])
   * @return 200 OK mit {@link CursorCollectionModel} von {@link NotificationModel}
   */
  @Operation(
      summary = "Benachrichtigungen auflisten",
      description = "Liefert eine Keyset-paginierte HAL-Collection der eigenen Benachrichtigungen."
          + " filter=all (Standard) liefert alle, filter=unread nur ungelesene.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "HAL-Collection mit Benachrichtigungen."),
      @ApiResponse(responseCode = "400", description = "Ungueltiger filter-Wert oder cursor."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende NOTIFICATION_READ-Berechtigung.")
  })
  @GetMapping
  @RequiresPermission(TaskManagementPermissions.NOTIFICATION_READ)
  @ApiFindCollectionResponse(NotificationModel.class)
  public ResponseEntity<CursorCollectionModel<NotificationModel>> listNotifications(
      @Parameter(description = "Filter: 'all' (Standard) oder 'unread' fuer nur ungelesene.")
      @RequestParam(required = false) final String filter,
      @Parameter(description = "Opaker Keyset-Cursor fuer die naechste Seite (leer = erste Seite).")
      @RequestParam(required = false) final String cursor,
      @Parameter(description = "Seitengroesse, geclampt auf [1, 100]. Default: 50.")
      @RequestParam(required = false, defaultValue = "50") final int limit) {

    final CurrentActor actor = CurrentActor.require(userAccessor);
    return ResponseEntity.ok(
        queryService.listForCollection(
            actor.user().id(),
            actor.tenant().id(),
            filter,
            cursor,
            limit));
  }

  /**
   * Markiert alle eigenen ungelesenen Benachrichtigungen als gelesen.
   *
   * <p>Bewusst EINE separate Mapping-Methode (kein Wildcard), damit Spring {@code /read-all}
   * nicht als {@code /{id}=read-all} interpretiert.
   *
   * @return 204 No Content
   */
  @Operation(
      summary = "Alle Benachrichtigungen als gelesen markieren",
      description = "Markiert alle eigenen ungelesenen Benachrichtigungen als gelesen (idempotent).")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Alle Benachrichtigungen erfolgreich als gelesen markiert."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende NOTIFICATION_WRITE-Berechtigung.")
  })
  @PutMapping("/read-all")
  @HateoasLinkTarget(NotificationHateoas.Targets.MARK_ALL_READ)
  @RequiresPermission(TaskManagementPermissions.NOTIFICATION_WRITE)
  public ResponseEntity<Void> markAllRead() {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    queryService.markAllRead(actor.user().id(), actor.tenant().id());
    return ResponseEntity.noContent().build();
  }

  /**
   * Setzt den Lese-Zustand einer einzelnen Benachrichtigung (gelesen/ungelesen).
   *
   * <p>Optionaler Body {@link NotificationReadInput} {@code { value }}: {@code true} = gelesen,
   * {@code false} = ungelesen. Fehlt der Body ODER {@code value}, gilt {@code true} (gelesen) -
   * backward-compatible zum frueheren No-Body-Verhalten. Idempotent.
   *
   * @param id    externe UUID der Benachrichtigung
   * @param input optionaler Lese-Zielzustand (fehlend/null -> gelesen)
   * @return 204 No Content
   */
  @Operation(
      summary = "Lese-Zustand einer Benachrichtigung setzen",
      description = "Setzt eine einzelne Benachrichtigung auf gelesen (value=true, Default) oder"
          + " ungelesen (value=false). Der Body ist OPTIONAL; fehlt er oder value, gilt gelesen"
          + " (backward-compatible). Idempotent. Liefert 404 wenn die Benachrichtigung nicht gefunden"
          + " wird oder einem anderen Benutzer/Mandanten gehoert (neutral maskiert).")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Lese-Zustand erfolgreich gesetzt."),
      @ApiResponse(responseCode = "400", description = "Syntaktisch ODER typmaessig ungueltiger JSON-Body (ein fehlendes value ist KEIN 400)."),
      @ApiResponse(responseCode = "401", description = "Kein oder ungueltiges JWT."),
      @ApiResponse(responseCode = "403", description = "Fehlende NOTIFICATION_WRITE-Berechtigung."),
      @ApiResponse(responseCode = "404", description = "Benachrichtigung nicht gefunden (neutral maskiert).")
  })
  @PutMapping("/{id}/read")
  @HateoasLinkTarget(NotificationHateoas.Targets.MARK_READ)
  @RequiresPermission(TaskManagementPermissions.NOTIFICATION_WRITE)
  public ResponseEntity<Void> markRead(
      @Parameter(description = "Externe UUID der Benachrichtigung.", required = true)
      @PathVariable final UUID id,
      @RequestBody(required = false) final NotificationReadInput input) {
    final CurrentActor actor = CurrentActor.require(userAccessor);
    // Backward-compat (D2): kein Body / value null -> gelesen (true).
    final boolean read = input == null || input.value() == null || input.value();
    queryService.setReadState(id, actor.user().id(), actor.tenant().id(), read);
    return ResponseEntity.noContent().build();
  }
}
