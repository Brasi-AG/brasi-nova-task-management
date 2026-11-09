package de.brasi.nova.taskmanagement.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import io.keel.framework.api.hateoas.specification.LinkSpecification;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.security.PermissionEvaluator;
import io.keel.framework.api.service.model.BaseModel;
import io.keel.framework.api.service.model.ModelState;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.hateoas.RepresentationModel;

/**
 * Unit-Tests der beiden {@link LinkSpecification}-Implementierungen fuer die Tag-Affordance (PLAN
 * #141 Slice 2). Reine Logik, kein Spring-Context: beweist, dass die {@code getName()}-Namen exakt
 * den {@link TagHateoas.Specs}-Konstanten entsprechen (damit {@code @LinkRule.conditions} aufloest)
 * und dass {@code isSatisfiedBy} fail-safe ist (Cast-Guard, Exception -&gt; false).
 */
@ExtendWith(MockitoExtension.class)
class TagLinkSpecificationsTest {

  @Mock private PermissionEvaluator evaluator;

  // ---- TagAdminSpec ---------------------------------------------------------------------------

  @Test
  @DisplayName("TagAdminSpec.getName entspricht exakt TagHateoas.Specs.TAG_ADMIN")
  void tag_admin_spec_name_matcht_konstante() {
    final LinkSpecification spec = new TagLinkSpecifications.TagAdminSpec();
    assertThat(spec.getName()).isEqualTo(TagHateoas.Specs.TAG_ADMIN);
  }

  @Test
  @DisplayName("TagAdminSpec: hasExactAuthority(TAG_ADMIN)=true -> satisfied")
  void tag_admin_spec_mit_authority_true() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TAG_ADMIN)).thenReturn(true);
    final LinkSpecification spec = new TagLinkSpecifications.TagAdminSpec();
    assertThat(spec.isSatisfiedBy(tagModel(ModelState.ACTIVE), Optional.empty(), evaluator))
        .isTrue();
  }

  @Test
  @DisplayName("TagAdminSpec: hasExactAuthority(TAG_ADMIN)=false -> nicht satisfied")
  void tag_admin_spec_ohne_authority_false() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TAG_ADMIN)).thenReturn(false);
    final LinkSpecification spec = new TagLinkSpecifications.TagAdminSpec();
    assertThat(spec.isSatisfiedBy(tagModel(ModelState.ACTIVE), Optional.empty(), evaluator))
        .isFalse();
  }

  @Test
  @DisplayName("TagAdminSpec: Evaluator wirft RuntimeException -> fail-safe false")
  void tag_admin_spec_exception_fail_safe() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TAG_ADMIN))
        .thenThrow(new RuntimeException("boom"));
    final LinkSpecification spec = new TagLinkSpecifications.TagAdminSpec();
    assertThat(spec.isSatisfiedBy(tagModel(ModelState.ACTIVE), Optional.empty(), evaluator))
        .isFalse();
  }

  // ---- TagActiveStateSpec ---------------------------------------------------------------------

  @Test
  @DisplayName("TagActiveStateSpec.getName entspricht exakt TagHateoas.Specs.IS_ACTIVE")
  void tag_active_spec_name_matcht_konstante() {
    final LinkSpecification spec = new TagLinkSpecifications.TagActiveStateSpec();
    assertThat(spec.getName()).isEqualTo(TagHateoas.Specs.IS_ACTIVE);
  }

  @Test
  @DisplayName("TagActiveStateSpec: modelState=ACTIVE -> satisfied")
  void tag_active_spec_active_true() {
    final LinkSpecification spec = new TagLinkSpecifications.TagActiveStateSpec();
    assertThat(spec.isSatisfiedBy(tagModel(ModelState.ACTIVE), Optional.empty(), evaluator))
        .isTrue();
  }

  @Test
  @DisplayName("TagActiveStateSpec: modelState=DEACTIVE -> nicht satisfied")
  void tag_active_spec_deactive_false() {
    final LinkSpecification spec = new TagLinkSpecifications.TagActiveStateSpec();
    assertThat(spec.isSatisfiedBy(tagModel(ModelState.DEACTIVE), Optional.empty(), evaluator))
        .isFalse();
  }

  @Test
  @DisplayName("TagActiveStateSpec: modelState=null -> nicht satisfied")
  void tag_active_spec_null_state_false() {
    final LinkSpecification spec = new TagLinkSpecifications.TagActiveStateSpec();
    assertThat(spec.isSatisfiedBy(tagModel(null), Optional.empty(), evaluator)).isFalse();
  }

  @Test
  @DisplayName("TagActiveStateSpec: dto ist KEIN BaseModel (Cast-Guard) -> nicht satisfied")
  void tag_active_spec_cast_guard_false() {
    final LinkSpecification spec = new TagLinkSpecifications.TagActiveStateSpec();
    final RepresentationModel<?> notABaseModel = new NonBaseRepresentationModel();
    assertThat(spec.isSatisfiedBy(notABaseModel, Optional.empty(), evaluator)).isFalse();
  }

  // ---- Hilfen ---------------------------------------------------------------------------------

  private static TagModel tagModel(final ModelState state) {
    final TagModel model = new TagModel(UUID.randomUUID(), "Dringend", "DANGER", "#F87171");
    model.setModelState(state);
    return model;
  }

  /** Minimale RepresentationModel-Subklasse, die KEIN {@link BaseModel} ist (Cast-Guard-Beweis). */
  private static final class NonBaseRepresentationModel
      extends RepresentationModel<NonBaseRepresentationModel> {}

  /** Nur zur Signatur-Dokumentation: die Spec-Entity-Optional-Signatur nutzt {@link NovaEntity}. */
  @SuppressWarnings("unused")
  private static Optional<NovaEntity<?>> emptyEntity() {
    return Optional.empty();
  }
}
