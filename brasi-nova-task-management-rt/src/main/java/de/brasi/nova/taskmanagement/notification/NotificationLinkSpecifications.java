package de.brasi.nova.taskmanagement.notification;

import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.hateoas.specification.LinkSpecification;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.security.PermissionEvaluator;
import java.util.Optional;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.stereotype.Component;

/**
 * {@link LinkSpecification}-Beans fuer die Notification-Affordance (PLAN #141 Slice 5, Teil A).
 * Analog zum Tag-Muster: statische Inner-{@code @Component}-Klassen, fail-safe (Exception ->
 * false), {@code getName()} liefert stabile Namen, die exakt den {@link
 * NotificationHateoas.Specs}-Konstanten entsprechen.
 *
 * <p>Die {@code _links} sind rein ADDITIV (FE-Anzeige); die Enforcement bleibt
 * {@code @RequiresPermission(NOTIFICATION_WRITE)} an jedem Mutations-Handler des {@code
 * NotificationController}.
 */
public final class NotificationLinkSpecifications {

  private NotificationLinkSpecifications() {}

  /**
   * Bedingung {@link NotificationHateoas.Specs#NOTIFICATION_WRITE}: der aktuell authentifizierte
   * Anfrager traegt die exakte Authority {@code task-management:NOTIFICATION_WRITE}. Spiegelt die
   * Handler-Enforcement; ohne NOTIFICATION_WRITE erscheinen die write-gegateten Rels
   * (mark-read/mark-all-read) nicht.
   */
  @Component
  public static final class NotificationWriteSpec implements LinkSpecification {

    @Override
    public String getName() {
      return NotificationHateoas.Specs.NOTIFICATION_WRITE;
    }

    @Override
    public boolean isSatisfiedBy(
        final RepresentationModel<?> dto,
        final Optional<NovaEntity<?>> entityOpt,
        final PermissionEvaluator evaluator) {
      try {
        return evaluator.hasExactAuthority(TaskManagementPermissions.NOTIFICATION_WRITE);
      } catch (final RuntimeException e) {
        // Fail-safe: bei einem Evaluator-Fehler keinen Affordance-Link emittieren.
        return false;
      }
    }
  }
}
