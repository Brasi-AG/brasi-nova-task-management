package de.brasi.nova.taskmanagement.task;

import de.brasi.nova.taskmanagement.entity.Task;
import de.brasi.nova.taskmanagement.security.CurrentActor;
import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import de.brasi.nova.taskmanagement.security.TaskMembershipGuard;
import io.keel.framework.api.hateoas.specification.LinkSpecification;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.security.PermissionEvaluator;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.security.user.UserAccessor;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * {@link LinkSpecification}-Beans fuer die Task-Affordance (PLAN #141 Slice 3). Analog zum
 * TagLinkSpecifications-Muster (Slice 2): statische Inner-{@code @Component}-Klassen je Bedingung,
 * fail-safe (Cast-Guard + Exception -&gt; false), {@code getName()} liefert stabile Namen, die
 * exakt den {@link TaskHateoas.Specs}-Konstanten entsprechen.
 *
 * <p>Die {@code _links} sind rein ADDITIV (FE-Anzeige); die Enforcement bleibt UNVERAENDERT an den
 * Handler-Methoden ({@code requireWriteMember} im Service / {@code @RequiresPermission} am
 * Controller).
 *
 * <p><b>Sicherheitskern:</b> alle {@code isSatisfiedBy}-Implementierungen sind fail-safe ({@code
 * try/catch(RuntimeException) -> false}): ein fehlender Security-Context, ein fehlendes entityOpt
 * oder ein Guard-/Evaluator-Fehler emittiert KEINEN Link (fail-closed). Kein Fail-open.
 */
public final class TaskLinkSpecifications {

  private TaskLinkSpecifications() {}

  /**
   * Bedingung {@link TaskHateoas.Specs#IS_WRITE_MEMBER}: der aktuell authentifizierte Akteur ist
   * aktives WRITE-Member der Aufgabe. Spiegelt EXAKT die Guard-Pruefung - genutzt wird die
   * non-throwing Variante {@code TaskMembershipGuard.isWriteMember(taskId, actorRef,
   * task.getTenantId())} (identische Pruefung {@code matchesContextTenant && existsWriteMember} wie
   * das werfende {@code requireWriteMember}), wie es die Enforcement-Pfade {@code
   * TaskCommandService.patch}, {@code MarkerService.applyMarker}, {@code
   * CommentService.createComment} und {@code AssigneeService.exit} enforcen.
   *
   * <p>Die Membership-Pruefung liest Actor und Tenant-Kontext aus dem Security-Context ({@link
   * UserAccessor}/{@link CurrentActor}), NIEMALS aus dem DTO oder externen Parametern. Als
   * Task-Entity-ID und Tenant-ID werden die Werte aus dem {@code entityOpt} genutzt ({@link
   * Task#getId()} und {@link Task#getTenantId()}), die vom {@code TaskDetailService} via {@code
   * setEntity} in den BaseModel-Kontext gestellt wurden (Slice 1).
   *
   * <p><b>Request-scoped Memoization (N+1-Fix):</b> KSF wertet die Spec je Listen-Item und je
   * {@code @LinkRule} aus (6 {@code IS_WRITE_MEMBER}-Rels plus 2 {@code
   * HAS_WRITE_AUTHORITY_AND_IS_MEMBER}, die nach der Authority-Pruefung an diese Spec delegieren) -
   * ohne Cache also 6-8 identische DB-Abfragen je Item. Das Ergebnis wird deshalb je HTTP-Request
   * im Request-Attribut {@value #RESULT_CACHE_ATTRIBUTE} (Scope REQUEST) gehalten.
   *
   * <ul>
   *   <li><b>Schluessel:</b> (taskId, actorRef, taskTenant, Kontext-Tenant aus dem {@code
   *       TenantContextHolder}) - genau die Eingaben, von denen {@code isWriteMember} abhaengt; ein
   *       gecachtes Ergebnis kann nie fuer einen anderen Akteur, eine andere Aufgabe oder einen
   *       anderen Mandanten-Kontext greifen.
   *   <li><b>Stale-Freiheit:</b> die Enrichment-Pipeline laeuft pro Request genau einmal NACH dem
   *       Handler; Mutations-Handler liefern {@code Void} (keine Links). Innerhalb des Requests
   *       aendert sich die Membership zwischen zwei Spec-Auswertungen nicht.
   *   <li><b>Kein Request-Kontext</b> ({@code RequestContextHolder.getRequestAttributes() == null},
   *       z. B. Async/Tests): kein Cache, jeder Aufruf delegiert direkt an den Guard. Bewusst KEIN
   *       {@code @RequestScope}-Proxy: eine {@code ScopeNotActiveException} wuerde vom
   *       fail-safe-catch still zu {@code false} und liesse Links verschwinden.
   *   <li><b>Fehlerfall:</b> eine Exception ergibt fail-safe {@code false}; dieses Ergebnis wird
   *       NICHT gecacht - nur echte Guard-Ergebnisse. Ein transienter Fehler maskiert so nicht den
   *       Rest des Requests.
   *   <li><b>Nur hier:</b> der Cache lebt ausschliesslich in dieser Spec, NIEMALS im {@link
   *       TaskMembershipGuard}; die Enforcement-Pfade ({@code requireWriteMember}, {@code
   *       requireReadMember}, {@code allMatch(isWriteMember)} im AssigneeService u. a.) bleiben
   *       ungecacht.
   * </ul>
   *
   * <p>Lokales Muster; Kandidat fuer einen KSF-weiten Spec-Result-Cache (eigene KSF-Lane).
   */
  @Component
  public static final class TaskWriteMembershipSpec implements LinkSpecification {

    /** Name des Request-Attributs (Scope REQUEST), unter dem die Ergebnis-Map je Request liegt. */
    static final String RESULT_CACHE_ATTRIBUTE =
        TaskWriteMembershipSpec.class.getName() + ".WRITE_MEMBER_RESULTS";

    /** Cache-Schluessel: alle Eingaben, von denen das Guard-Ergebnis abhaengt. */
    private record CacheKey(Long taskId, UUID actorRef, long taskTenant, Long contextTenant) {}

    private final TaskMembershipGuard membershipGuard;
    private final UserAccessor userAccessor;

    /** Konstruktor-Injektion. */
    public TaskWriteMembershipSpec(
        final TaskMembershipGuard membershipGuard, final UserAccessor userAccessor) {
      this.membershipGuard = membershipGuard;
      this.userAccessor = userAccessor;
    }

    @Override
    public String getName() {
      return TaskHateoas.Specs.IS_WRITE_MEMBER;
    }

    @Override
    public boolean isSatisfiedBy(
        final RepresentationModel<?> dto,
        final Optional<NovaEntity<?>> entityOpt,
        final PermissionEvaluator evaluator) {
      try {
        // Cast-Guard: entity muss Task-Instanz sein (gesetzt via setEntity in TaskDetailService).
        if (!(entityOpt.orElse(null) instanceof Task task)) {
          return false;
        }
        final Long taskId = task.getId();
        final long taskTenant = task.getTenantId();
        // Actor aus dem Security-Context (nie aus dem DTO).
        final CurrentActor actor = CurrentActor.require(userAccessor);
        final UUID actorRef = actor.user().id();
        // isWriteMember: fail-safe boolean, wirft NICHT (matchesContextTenant + existsWriteMember).
        return isWriteMemberMemoized(taskId, actorRef, taskTenant);
      } catch (final RuntimeException e) {
        // Fail-safe: fehlender Security-Context, fehlende Entity-ID, Guard-Fehler -> kein Link.
        return false;
      }
    }

    /**
     * Delegiert an den Guard und haelt das Ergebnis je Request. Ohne Request-Kontext wird direkt
     * delegiert. Wirft der Guard, propagiert die Exception (nichts wird gecacht).
     */
    private boolean isWriteMemberMemoized(
        final Long taskId, final UUID actorRef, final long taskTenant) {
      final RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
      if (attributes == null) {
        return membershipGuard.isWriteMember(taskId, actorRef, taskTenant);
      }
      @SuppressWarnings("unchecked")
      Map<CacheKey, Boolean> results =
          (Map<CacheKey, Boolean>)
              attributes.getAttribute(RESULT_CACHE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
      if (results == null) {
        results = new ConcurrentHashMap<>();
        attributes.setAttribute(RESULT_CACHE_ATTRIBUTE, results, RequestAttributes.SCOPE_REQUEST);
      }
      final Long contextTenant = TenantContextHolder.getTenant().map(TenantInfo::id).orElse(null);
      final CacheKey key = new CacheKey(taskId, actorRef, taskTenant, contextTenant);
      final Boolean cached = results.get(key);
      if (cached != null) {
        return cached;
      }
      final boolean result = membershipGuard.isWriteMember(taskId, actorRef, taskTenant);
      results.put(key, result);
      return result;
    }
  }

  /**
   * Bedingung {@link TaskHateoas.Specs#HAS_WRITE_AUTHORITY}: der Anfrager traegt die exakte
   * Authority {@code task-management:TASK_WRITE}. Spiegelt das
   * {@code @RequiresPermission(TASK_WRITE)} an {@code TaskFacadeController.create}, {@code
   * AssigneeController.updateAssignees} und {@code AssigneeController.addParticipants}.
   */
  @Component
  public static final class TaskWriteAuthoritySpec implements LinkSpecification {

    @Override
    public String getName() {
      return TaskHateoas.Specs.HAS_WRITE_AUTHORITY;
    }

    @Override
    public boolean isSatisfiedBy(
        final RepresentationModel<?> dto,
        final Optional<NovaEntity<?>> entityOpt,
        final PermissionEvaluator evaluator) {
      try {
        return evaluator.hasExactAuthority(TaskManagementPermissions.TASK_WRITE);
      } catch (final RuntimeException e) {
        return false;
      }
    }
  }

  /**
   * Bedingung {@link TaskHateoas.Specs#HAS_CREATE_AUTHORITY}: der Anfrager traegt die exakte
   * Authority {@code task-management:TASK_CREATE}. Spiegelt das
   * {@code @RequiresPermission(TASK_CREATE)} an {@code TaskFacadeController.createFromSource}.
   */
  @Component
  public static final class TaskCreateAuthoritySpec implements LinkSpecification {

    @Override
    public String getName() {
      return TaskHateoas.Specs.HAS_CREATE_AUTHORITY;
    }

    @Override
    public boolean isSatisfiedBy(
        final RepresentationModel<?> dto,
        final Optional<NovaEntity<?>> entityOpt,
        final PermissionEvaluator evaluator) {
      try {
        return evaluator.hasExactAuthority(TaskManagementPermissions.TASK_CREATE);
      } catch (final RuntimeException e) {
        return false;
      }
    }
  }

  /**
   * Bedingung {@link TaskHateoas.Specs#HAS_WRITE_AUTHORITY_AND_IS_MEMBER}: TASK_WRITE-Authority UND
   * WRITE-Member (Konjunktion). Spiegelt die kombinierte Enforcement an {@code
   * AssigneeController.updateAssignees} und {@code AssigneeController.addParticipants}: beide
   * tragen {@code @RequiresPermission(TASK_WRITE)} UND der {@link AssigneeService} ruft intern
   * {@code requireWriteMember} auf.
   *
   * <p>Implementiert durch Delegation an {@link TaskWriteAuthoritySpec} und {@link
   * TaskWriteMembershipSpec} (injiziert), damit die Fail-safe-Logik nicht dupliziert wird.
   */
  @Component
  public static final class TaskWriteAuthorityAndMembershipSpec implements LinkSpecification {

    private final TaskWriteAuthoritySpec authoritySpec;
    private final TaskWriteMembershipSpec membershipSpec;

    /** Konstruktor-Injektion der Teilbedingungen. */
    public TaskWriteAuthorityAndMembershipSpec(
        final TaskWriteAuthoritySpec authoritySpec, final TaskWriteMembershipSpec membershipSpec) {
      this.authoritySpec = authoritySpec;
      this.membershipSpec = membershipSpec;
    }

    @Override
    public String getName() {
      return TaskHateoas.Specs.HAS_WRITE_AUTHORITY_AND_IS_MEMBER;
    }

    @Override
    public boolean isSatisfiedBy(
        final RepresentationModel<?> dto,
        final Optional<NovaEntity<?>> entityOpt,
        final PermissionEvaluator evaluator) {
      // Beide Teil-Specs sind je fail-safe; die Konjunktion ist es damit auch.
      return authoritySpec.isSatisfiedBy(dto, entityOpt, evaluator)
          && membershipSpec.isSatisfiedBy(dto, entityOpt, evaluator);
    }
  }
}
