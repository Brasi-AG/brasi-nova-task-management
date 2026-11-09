package de.brasi.nova.taskmanagement.tag;

import de.brasi.nova.taskmanagement.security.TaskManagementPermissions;
import io.keel.framework.api.hateoas.specification.LinkSpecification;
import io.keel.framework.api.model.NovaEntity;
import io.keel.framework.api.security.PermissionEvaluator;
import io.keel.framework.api.service.model.BaseModel;
import io.keel.framework.api.service.model.ModelState;
import java.util.Optional;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.stereotype.Component;

/**
 * {@link LinkSpecification}-Beans fuer die Tag-Affordance (PLAN #141 Slice 2). Analog zum
 * application-Muster: statische Inner-{@code @Component}-Klassen je Bedingung, fail-safe
 * (Cast-Guard + Exception -&gt; false), {@code getName()} liefert einen stabilen Namen, der exakt
 * den {@link TagHateoas.Specs}-Konstanten entspricht (Referenz in {@code @LinkRule.conditions}).
 *
 * <p>Die {@code _links} sind rein ADDITIV (FE-Anzeige); die eigentliche Enforcement bleibt {@code
 * @RequiresPermission(TAG_ADMIN)} an jedem Mutations-Handler des {@code TagController}.
 */
public final class TagLinkSpecifications {

  private TagLinkSpecifications() {}

  /**
   * Bedingung {@link TagHateoas.Specs#TAG_ADMIN}: der aktuell authentifizierte Anfrager traegt die
   * exakte Authority {@code task-management:TAG_ADMIN}. Spiegelt die Handler-Enforcement; ohne
   * TAG_ADMIN erscheinen die admin-gegateten Rels (create/update/deactivate/delete) nicht.
   */
  @Component
  public static final class TagAdminSpec implements LinkSpecification {

    @Override
    public String getName() {
      return TagHateoas.Specs.TAG_ADMIN;
    }

    @Override
    public boolean isSatisfiedBy(
        final RepresentationModel<?> dto,
        final Optional<NovaEntity<?>> entityOpt,
        final PermissionEvaluator evaluator) {
      try {
        return evaluator.hasExactAuthority(TaskManagementPermissions.TAG_ADMIN);
      } catch (final RuntimeException e) {
        // Fail-safe: bei einem Evaluator-Fehler keinen Affordance-Link emittieren.
        return false;
      }
    }
  }

  /**
   * Bedingung {@link TagHateoas.Specs#IS_ACTIVE}: der Tag ist aktiv ({@code modelState=ACTIVE}). Fuer
   * {@code deactivate} zusaetzlich zu {@link TagHateoas.Specs#TAG_ADMIN} gefordert, damit ein bereits
   * deaktivierter Tag keinen (fachlich sinnlosen) deactivate-Link traegt.
   */
  @Component
  public static final class TagActiveStateSpec implements LinkSpecification {

    @Override
    public String getName() {
      return TagHateoas.Specs.IS_ACTIVE;
    }

    @Override
    public boolean isSatisfiedBy(
        final RepresentationModel<?> dto,
        final Optional<NovaEntity<?>> entityOpt,
        final PermissionEvaluator evaluator) {
      if (!(dto instanceof BaseModel<?> baseModel)) {
        return false;
      }
      try {
        return ModelState.ACTIVE.equals(baseModel.getModelState());
      } catch (final RuntimeException e) {
        return false;
      }
    }
  }
}
