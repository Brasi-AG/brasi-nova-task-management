package de.brasi.nova.taskmanagement.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.hateoas.specification.LinkSpecification;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.security.PermissionEvaluator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.hateoas.RepresentationModel;

/**
 * Unit-Tests der {@link LinkSpecification}-Implementierung fuer die Notification-Affordance
 * (PLAN #141 Slice 5, Teil A). Reine Logik, kein Spring-Context: beweist, dass der
 * {@code getName()}-Name exakt der {@link NotificationHateoas.Specs}-Konstante entspricht (damit
 * {@code @LinkRule.conditions} aufloest) und dass {@code isSatisfiedBy} fail-safe ist
 * (Exception -> false).
 */
@ExtendWith(MockitoExtension.class)
class NotificationLinkSpecificationsTest {

  @Mock private PermissionEvaluator evaluator;

  // ---- NotificationWriteSpec ------------------------------------------------------------------

  @Test
  @DisplayName("NotificationWriteSpec.getName entspricht exakt NotificationHateoas.Specs.NOTIFICATION_WRITE")
  void notification_write_spec_name_matcht_konstante() {
    final LinkSpecification spec = new NotificationLinkSpecifications.NotificationWriteSpec();
    assertThat(spec.getName()).isEqualTo(NotificationHateoas.Specs.NOTIFICATION_WRITE);
  }

  @Test
  @DisplayName("NotificationWriteSpec: hasExactAuthority(NOTIFICATION_WRITE)=true -> satisfied")
  void notification_write_spec_mit_authority_true() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.NOTIFICATION_WRITE)).thenReturn(true);
    final LinkSpecification spec = new NotificationLinkSpecifications.NotificationWriteSpec();
    assertThat(spec.isSatisfiedBy(minimalModel(), Optional.empty(), evaluator)).isTrue();
  }

  @Test
  @DisplayName("NotificationWriteSpec: hasExactAuthority(NOTIFICATION_WRITE)=false -> nicht satisfied")
  void notification_write_spec_ohne_authority_false() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.NOTIFICATION_WRITE)).thenReturn(false);
    final LinkSpecification spec = new NotificationLinkSpecifications.NotificationWriteSpec();
    assertThat(spec.isSatisfiedBy(minimalModel(), Optional.empty(), evaluator)).isFalse();
  }

  @Test
  @DisplayName("NotificationWriteSpec: Evaluator wirft RuntimeException -> fail-safe false")
  void notification_write_spec_exception_fail_safe() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.NOTIFICATION_WRITE))
        .thenThrow(new RuntimeException("boom"));
    final LinkSpecification spec = new NotificationLinkSpecifications.NotificationWriteSpec();
    assertThat(spec.isSatisfiedBy(minimalModel(), Optional.empty(), evaluator)).isFalse();
  }

  // ---- Hilfen ---------------------------------------------------------------------------------

  private static RepresentationModel<?> minimalModel() {
    return new MinimalRepresentationModel();
  }

  /** Minimale RepresentationModel-Subklasse fuer die Signatur (NotificationWriteSpec prueft kein Cast). */
  private static final class MinimalRepresentationModel
      extends RepresentationModel<MinimalRepresentationModel> {}

  /** Nur zur Signatur-Dokumentation. */
  @SuppressWarnings("unused")
  private static Optional<NovaEntity<?>> emptyEntity() {
    return Optional.empty();
  }
}
