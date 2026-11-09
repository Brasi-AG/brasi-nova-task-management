package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import de.brasi.nova.taskmanagement.task.model.TaskDetailModel;
import io.keel.framework.api.hateoas.specification.LinkSpecification;
import io.keel.framework.api.security.PermissionEvaluator;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.AuthUser;
import io.keel.framework.api.security.user.UserAccessor;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Unit-Tests der vier {@link LinkSpecification}-Implementierungen fuer die Task-Affordance (PLAN
 * #141 Slice 3). Reine Logik, kein Spring-Context: beweist pos/neg/fail-safe-Verhalten sowie dass
 * {@code getName()} exakt den {@link TaskHateoas.Specs}-Konstanten entspricht.
 */
@ExtendWith(MockitoExtension.class)
class TaskLinkSpecificationsTest {

  @Mock private PermissionEvaluator evaluator;
  @Mock private TaskMembershipGuard membershipGuard;
  @Mock private UserAccessor userAccessor;
  @Mock private TenantInfo tenantInfo;
  @Mock private AuthUser authUser;

  private static final UUID ACTOR_REF = UUID.randomUUID();
  private static final long TASK_TENANT_ID = 510L;
  private static final long TASK_ID = 42L;

  private Task taskEntity;
  private TaskDetailModel taskDetailModel;

  @BeforeEach
  void setUp() {
    taskEntity = new Task();
    taskEntity.setId(TASK_ID);
    taskEntity.setTenantId(TASK_TENANT_ID);
    taskDetailModel = new TaskDetailModel();
    // Sicherheitshalber Kontext bereinigen (falls ein vorheriger Test ihn nicht aufraeumte).
    TenantContextHolder.clear();
  }

  @AfterEach
  void tearDown() {
    // ThreadLocal-Kontext nach jedem Test bereinigen, damit keine Tests sich gegenseitig
    // beeinflussen.
    TenantContextHolder.clear();
    RequestContextHolder.resetRequestAttributes();
  }

  // ---- TaskWriteAuthoritySpec -----------------------------------------------------------------

  @Test
  @DisplayName(
      "TaskWriteAuthoritySpec.getName entspricht exakt TaskHateoas.Specs.HAS_WRITE_AUTHORITY")
  void write_authority_spec_name_matcht_konstante() {
    final LinkSpecification spec = new TaskLinkSpecifications.TaskWriteAuthoritySpec();
    assertThat(spec.getName()).isEqualTo(TaskHateoas.Specs.HAS_WRITE_AUTHORITY);
  }

  @Test
  @DisplayName("TaskWriteAuthoritySpec: TASK_WRITE vorhanden -> satisfied")
  void write_authority_spec_mit_authority_true() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE)).thenReturn(true);
    final LinkSpecification spec = new TaskLinkSpecifications.TaskWriteAuthoritySpec();
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isTrue();
  }

  @Test
  @DisplayName("TaskWriteAuthoritySpec: TASK_WRITE fehlt -> nicht satisfied")
  void write_authority_spec_ohne_authority_false() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE)).thenReturn(false);
    final LinkSpecification spec = new TaskLinkSpecifications.TaskWriteAuthoritySpec();
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isFalse();
  }

  @Test
  @DisplayName("TaskWriteAuthoritySpec: Evaluator wirft RuntimeException -> fail-safe false")
  void write_authority_spec_exception_fail_safe() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE))
        .thenThrow(new RuntimeException("boom"));
    final LinkSpecification spec = new TaskLinkSpecifications.TaskWriteAuthoritySpec();
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isFalse();
  }

  // ---- TaskCreateAuthoritySpec ----------------------------------------------------------------

  @Test
  @DisplayName(
      "TaskCreateAuthoritySpec.getName entspricht exakt TaskHateoas.Specs.HAS_CREATE_AUTHORITY")
  void create_authority_spec_name_matcht_konstante() {
    final LinkSpecification spec = new TaskLinkSpecifications.TaskCreateAuthoritySpec();
    assertThat(spec.getName()).isEqualTo(TaskHateoas.Specs.HAS_CREATE_AUTHORITY);
  }

  @Test
  @DisplayName("TaskCreateAuthoritySpec: TASK_CREATE vorhanden -> satisfied")
  void create_authority_spec_mit_authority_true() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_CREATE)).thenReturn(true);
    final LinkSpecification spec = new TaskLinkSpecifications.TaskCreateAuthoritySpec();
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.empty(), evaluator)).isTrue();
  }

  @Test
  @DisplayName("TaskCreateAuthoritySpec: TASK_CREATE fehlt -> nicht satisfied")
  void create_authority_spec_ohne_authority_false() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_CREATE)).thenReturn(false);
    final LinkSpecification spec = new TaskLinkSpecifications.TaskCreateAuthoritySpec();
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.empty(), evaluator)).isFalse();
  }

  @Test
  @DisplayName("TaskCreateAuthoritySpec: Exception -> fail-safe false")
  void create_authority_spec_exception_fail_safe() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_CREATE))
        .thenThrow(new RuntimeException("boom"));
    final LinkSpecification spec = new TaskLinkSpecifications.TaskCreateAuthoritySpec();
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.empty(), evaluator)).isFalse();
  }

  // ---- TaskWriteMembershipSpec ----------------------------------------------------------------

  @Test
  @DisplayName("TaskWriteMembershipSpec.getName entspricht exakt TaskHateoas.Specs.IS_WRITE_MEMBER")
  void membership_spec_name_matcht_konstante() {
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    assertThat(spec.getName()).isEqualTo(TaskHateoas.Specs.IS_WRITE_MEMBER);
  }

  @Test
  @DisplayName("TaskWriteMembershipSpec: WRITE-Member -> satisfied")
  void membership_spec_write_member_true() {
    stubActor(ACTOR_REF, TASK_TENANT_ID);
    when(membershipGuard.isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID)).thenReturn(true);
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isTrue();
  }

  @Test
  @DisplayName("TaskWriteMembershipSpec: kein WRITE-Member (z.B. Ex-Member) -> nicht satisfied")
  void membership_spec_ex_member_false() {
    stubActor(ACTOR_REF, TASK_TENANT_ID);
    when(membershipGuard.isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID)).thenReturn(false);
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isFalse();
  }

  @Test
  @DisplayName("TaskWriteMembershipSpec: entityOpt leer (Cast-Guard) -> nicht satisfied")
  void membership_spec_leeres_entity_opt_false() {
    // userAccessor wird hier nicht gecallt, da der Cast-Guard zuerst fehlschlaegt.
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.empty(), evaluator)).isFalse();
  }

  @Test
  @DisplayName("TaskWriteMembershipSpec: entity ist kein Task (Cast-Guard) -> nicht satisfied")
  void membership_spec_falscher_entity_typ_false() {
    // TaskTag ist ebenfalls eine NovaEntity, aber kein Task -> Cast-Guard greift.
    final de.brasi.nova.taskmanagement.entity.TaskTag notATask =
        new de.brasi.nova.taskmanagement.entity.TaskTag();
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(notATask), evaluator)).isFalse();
  }

  @Test
  @DisplayName("TaskWriteMembershipSpec: Guard wirft RuntimeException -> fail-safe false")
  void membership_spec_guard_exception_fail_safe() {
    stubActor(ACTOR_REF, TASK_TENANT_ID);
    when(membershipGuard.isWriteMember(anyLong(), any(UUID.class), anyLong()))
        .thenThrow(new RuntimeException("DB down"));
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isFalse();
  }

  @Test
  @DisplayName(
      "TaskWriteMembershipSpec: kein Tenant-Context (BusinessRuleException) -> fail-safe false")
  void membership_spec_fehlender_tenant_context_fail_safe() {
    // TenantContextHolder ist leer (setUp bereinigt) -> CurrentActor.require() wirft
    // BusinessRuleException (RuntimeException) -> fail-safe catch -> false.
    // userAccessor wird hier nicht gecallt (Tenant-Fehler kommt zuerst).
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isFalse();
  }

  // ---- TaskWriteAuthorityAndMembershipSpec ----------------------------------------------------

  @Test
  @DisplayName(
      "TaskWriteAuthorityAndMembershipSpec.getName entspricht exakt Specs.HAS_WRITE_AUTHORITY_AND_IS_MEMBER")
  void authority_and_membership_spec_name_matcht_konstante() {
    final TaskLinkSpecifications.TaskWriteAuthoritySpec authoritySpec =
        new TaskLinkSpecifications.TaskWriteAuthoritySpec();
    final TaskLinkSpecifications.TaskWriteMembershipSpec membershipSpecBean =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteAuthorityAndMembershipSpec(
            authoritySpec, membershipSpecBean);
    assertThat(spec.getName()).isEqualTo(TaskHateoas.Specs.HAS_WRITE_AUTHORITY_AND_IS_MEMBER);
  }

  @Test
  @DisplayName("TaskWriteAuthorityAndMembershipSpec: TASK_WRITE + WRITE-Member -> satisfied")
  void authority_and_membership_spec_beide_true() {
    stubActor(ACTOR_REF, TASK_TENANT_ID);
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE)).thenReturn(true);
    when(membershipGuard.isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID)).thenReturn(true);
    assertThat(
            buildAuthorityAndMembershipSpec()
                .isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator))
        .isTrue();
  }

  @Test
  @DisplayName(
      "TaskWriteAuthorityAndMembershipSpec: TASK_WRITE vorhanden, aber KEIN Member -> nicht satisfied")
  void authority_and_membership_spec_authority_ohne_membership_false() {
    stubActor(ACTOR_REF, TASK_TENANT_ID);
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE)).thenReturn(true);
    when(membershipGuard.isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID)).thenReturn(false);
    assertThat(
            buildAuthorityAndMembershipSpec()
                .isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator))
        .isFalse();
  }

  @Test
  @DisplayName(
      "TaskWriteAuthorityAndMembershipSpec: Member, aber kein TASK_WRITE -> nicht satisfied")
  void authority_and_membership_spec_membership_ohne_authority_false() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE)).thenReturn(false);
    // membershipGuard wird wegen Short-Circuit (authority=false) nicht gecallt.
    assertThat(
            buildAuthorityAndMembershipSpec()
                .isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator))
        .isFalse();
  }

  @Test
  @DisplayName("TaskWriteAuthorityAndMembershipSpec: entityOpt leer -> nicht satisfied")
  void authority_and_membership_spec_leeres_entity_false() {
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE)).thenReturn(true);
    // Membership-Spec gibt false (Cast-Guard auf leeres entityOpt).
    assertThat(
            buildAuthorityAndMembershipSpec()
                .isSatisfiedBy(taskDetailModel, Optional.empty(), evaluator))
        .isFalse();
  }

  // ---- Request-scoped Memoization (N+1-Fix) ----------------------------------------------------

  private static final UUID TENANT_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

  private void bindRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private void bindContextTenant(final long id) {
    TenantContextHolder.setTenant(new TenantInfo(TENANT_UUID, id));
  }

  private void stubActorOnly(final UUID actorRef) {
    when(userAccessor.requireCurrentUser()).thenReturn(authUser);
    when(authUser.id()).thenReturn(actorRef);
  }

  private Task task(final long id, final long tenant) {
    final Task t = new Task();
    t.setId(id);
    t.setTenantId(tenant);
    return t;
  }

  @Test
  @DisplayName(
      "Memoization: 8 Rule-Auswertungen (6x Membership + 2x AND) je Task -> Guard genau 1x")
  void memoization_acht_auswertungen_ein_guard_call_je_task() {
    bindRequest();
    bindContextTenant(TASK_TENANT_ID);
    stubActorOnly(ACTOR_REF);
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE)).thenReturn(true);
    when(membershipGuard.isWriteMember(anyLong(), any(UUID.class), anyLong())).thenReturn(true);
    final TaskLinkSpecifications.TaskWriteMembershipSpec membership =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    final TaskLinkSpecifications.TaskWriteAuthorityAndMembershipSpec both =
        new TaskLinkSpecifications.TaskWriteAuthorityAndMembershipSpec(
            new TaskLinkSpecifications.TaskWriteAuthoritySpec(), membership);

    for (int i = 0; i < 6; i++) {
      assertThat(membership.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator))
          .isTrue();
    }
    for (int i = 0; i < 2; i++) {
      assertThat(both.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isTrue();
    }
    verify(membershipGuard, times(1)).isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID);

    // zweiter Task im selben Request -> genau ein weiterer Guard-Call
    final Task second = task(43L, TASK_TENANT_ID);
    membership.isSatisfiedBy(taskDetailModel, Optional.of(second), evaluator);
    membership.isSatisfiedBy(taskDetailModel, Optional.of(second), evaluator);
    verify(membershipGuard, times(1)).isWriteMember(43L, ACTOR_REF, TASK_TENANT_ID);
  }

  @Test
  @DisplayName("Memoization: ohne RequestAttributes -> jeder Aufruf delegiert, kein Cache")
  void memoization_ohne_request_attributes_delegiert_jedes_mal() {
    bindContextTenant(TASK_TENANT_ID);
    stubActorOnly(ACTOR_REF);
    when(membershipGuard.isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID)).thenReturn(true);
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);

    for (int i = 0; i < 5; i++) {
      assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isTrue();
    }
    verify(membershipGuard, times(5)).isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID);
  }

  @Test
  @DisplayName(
      "Memoization: Schluessel-Isolation (Actor, Kontext-Tenant, Task-Tenant) ohne Cross-Hit")
  void memoization_schluessel_isolation() {
    bindRequest();
    final UUID otherActor = UUID.randomUUID();
    when(userAccessor.requireCurrentUser()).thenReturn(authUser);
    when(authUser.id()).thenReturn(ACTOR_REF, otherActor, ACTOR_REF, ACTOR_REF);
    when(membershipGuard.isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID)).thenReturn(true);
    when(membershipGuard.isWriteMember(TASK_ID, otherActor, TASK_TENANT_ID)).thenReturn(false);
    when(membershipGuard.isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID + 7)).thenReturn(false);
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);

    bindContextTenant(TASK_TENANT_ID);
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isTrue();
    // anderer Actor -> eigener Guard-Call, gecachtes true darf nicht greifen
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isFalse();

    // anderer Kontext-Tenant, gleicher Actor/Task -> eigener Call (Guard liefert hier false)
    bindContextTenant(TASK_TENANT_ID + 1);
    spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator);

    // anderer Task-Tenant -> eigener Call
    bindContextTenant(TASK_TENANT_ID);
    final Task foreign = task(TASK_ID, TASK_TENANT_ID + 7);
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(foreign), evaluator)).isFalse();

    verify(membershipGuard, times(1)).isWriteMember(TASK_ID, otherActor, TASK_TENANT_ID);
    verify(membershipGuard, times(1)).isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID + 7);
    // ACTOR_REF/TASK_TENANT_ID: 1x unter Kontext-Tenant 510, 1x unter Kontext-Tenant 511
    verify(membershipGuard, times(2)).isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID);
  }

  @Test
  @DisplayName(
      "Memoization: Guard-Exception -> false, NICHT gecacht; Folgeaufruf liefert echtes Ergebnis")
  void memoization_exception_wird_nicht_gecacht() {
    bindRequest();
    bindContextTenant(TASK_TENANT_ID);
    stubActorOnly(ACTOR_REF);
    when(membershipGuard.isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID))
        .thenThrow(new RuntimeException("DB down"))
        .thenReturn(true);
    final LinkSpecification spec =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);

    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isFalse();
    assertThat(spec.isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator)).isTrue();
    verify(membershipGuard, times(2)).isWriteMember(TASK_ID, ACTOR_REF, TASK_TENANT_ID);
  }

  @Test
  @DisplayName("Memoization: AND-Spec ohne Authority ruft den Guard nie")
  void memoization_and_spec_ohne_authority_kein_guard_call() {
    bindRequest();
    when(evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE)).thenReturn(false);
    assertThat(
            buildAuthorityAndMembershipSpec()
                .isSatisfiedBy(taskDetailModel, Optional.of(taskEntity), evaluator))
        .isFalse();
    verify(membershipGuard, never()).isWriteMember(anyLong(), any(UUID.class), anyLong());
  }

  // ---- Hilfen ---------------------------------------------------------------------------------

  /**
   * Bereitet den Security-Kontext fuer pos/neg-Membership-Tests vor. UserAccessor gibt den Actor
   * zurueck; TenantContextHolder wird real belegt (ThreadLocal), da CurrentActor.require() den
   * Tenant aus dem statischen Holder liest. Teardown in {@link #tearDown()} (via clear()).
   *
   * <p>Hinweis: tenantInfo.id() wird NICHT gestubbt, da die Spec task.getTenantId() als tenant-
   * Argument an isWriteMember uebergibt (nicht actor.tenant().id()).
   */
  private void stubActor(final UUID actorRef, final long tenantId) {
    when(userAccessor.requireCurrentUser()).thenReturn(authUser);
    when(authUser.id()).thenReturn(actorRef);
    // TenantInfo ins ThreadLocal-Context setzen, damit CurrentActor.require() nicht wirft.
    // tenantId wird hier als Kontext gesetzt; der Guard-Mock entscheidet ueber die tatsaechliche
    // Membership-Pruefung (task.getTenantId() wird an isWriteMember uebergeben).
    TenantContextHolder.setTenant(tenantInfo);
  }

  private TaskLinkSpecifications.TaskWriteAuthorityAndMembershipSpec
      buildAuthorityAndMembershipSpec() {
    final TaskLinkSpecifications.TaskWriteAuthoritySpec authoritySpec =
        new TaskLinkSpecifications.TaskWriteAuthoritySpec();
    final TaskLinkSpecifications.TaskWriteMembershipSpec membershipSpecBean =
        new TaskLinkSpecifications.TaskWriteMembershipSpec(membershipGuard, userAccessor);
    return new TaskLinkSpecifications.TaskWriteAuthorityAndMembershipSpec(
        authoritySpec, membershipSpecBean);
  }
}
