package de.brasi.nova.taskmanagement.affordanceprobe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.keel.framework.api.hateoas.annotation.CollectionLinkRule;
import io.keel.framework.api.hateoas.annotation.EnableNovaHateoas;
import io.keel.framework.api.hateoas.annotation.HateoasLinkTarget;
import io.keel.framework.api.hateoas.annotation.LinkParam;
import io.keel.framework.api.hateoas.annotation.LinkRule;
import io.keel.framework.api.hateoas.annotation.LinkRules;
import io.keel.framework.api.openapi.annotation.ApiFindResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.hateoas.CollectionModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>PLAIN {@code @RestController} (KEIN {@code AbstractNovaHateoasController}), der ueber {@code
 * @EnableNovaHateoas(entityClass=ProbeEntity.class)} als Regel-Owner fuer {@link ProbeEntity}
 * registriert wird (Option-1-Mechanik). Deklariert:
 *
 * <ul>
 *   <li>Item-Regel {@code self} -> GET {@code /probe/owner/{id}} (Nachweis P1).
 *   <li>Item-Regel {@code edit} -> GET {@code /probe/owner/{id}/edit} (Nachweis P2a: Item-Rel ueber
 *       Item-entityClass).
 *   <li>Collection-Regel {@code create} (Nachweis P2b: Collection-Rel, serviert durch
 *       Nicht-Owner-Controller).
 * </ul>
 *
 * <p>Die Link-Erzeugung laeuft ueber den globalen {@code NovaFindResponseAdvice}
 * (ResponseBodyAdvice), getriggert durch {@code @ApiFindResponse} am Handler. Es wird NIE manuell
 * ein Link gesetzt.
 */
@RestController
@ConditionalOnProperty(name = "task-management.test.affordance-probe.enabled", havingValue = "true")
@RequestMapping("/probe/owner")
@EnableNovaHateoas(entityClass = ProbeEntity.class)
@LinkRules(
    value = {
      @LinkRule(
          rel = "self",
          linkTo = "probe_owner_get_by_id",
          params = {@LinkParam(name = "id", source = "#dto.id")}),
      @LinkRule(
          rel = "edit",
          linkTo = "probe_owner_edit",
          params = {@LinkParam(name = "id", source = "#dto.id")})
    },
    collectionRules = {
      @CollectionLinkRule(rel = "create", linkTo = "probe_owner_create")
    })
public class ProbeOwnerController {

  /** Self-Ziel (GET single): liefert ein {@link ProbeModel} mit gesetzter Entity. */
  @GetMapping("/{id}")
  @HateoasLinkTarget("probe_owner_get_by_id")
  @ApiFindResponse(ProbeModel.class)
  public ResponseEntity<ProbeModel> getById(@PathVariable("id") final UUID id) {
    final ProbeModel model = new ProbeModel(id, "owner-single");
    model.setEntity(new ProbeEntity(42L, id));
    return ResponseEntity.ok(model);
  }

  /** Edit-Ziel: nur als Link-Ziel relevant (wird im Test nicht direkt aufgerufen). */
  @GetMapping("/{id}/edit")
  @HateoasLinkTarget("probe_owner_edit")
  @ApiFindResponse(ProbeModel.class)
  public ResponseEntity<ProbeModel> edit(@PathVariable("id") final UUID id) {
    final ProbeModel model = new ProbeModel(id, "owner-edit");
    model.setEntity(new ProbeEntity(42L, id));
    return ResponseEntity.ok(model);
  }

  /** Create-Ziel (Collection-Rel-Ziel): hier nur als Link-Ziel relevant. */
  @GetMapping("/create-target")
  @HateoasLinkTarget("probe_owner_create")
  @ApiFindResponse(ProbeModel.class)
  public ResponseEntity<CollectionModel<ProbeModel>> createTarget() {
    return ResponseEntity.ok(CollectionModel.of(List.of()));
  }
}
