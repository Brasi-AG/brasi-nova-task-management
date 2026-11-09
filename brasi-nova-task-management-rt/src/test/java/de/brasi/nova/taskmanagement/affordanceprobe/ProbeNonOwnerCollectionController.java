package de.brasi.nova.taskmanagement.affordanceprobe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.keel.framework.api.openapi.annotation.ApiFindResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPIKE-WEGWERF-ARTEFAKT (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>NICHT-Owner-Controller (KEIN {@code @EnableNovaHateoas}). Serviert eine {@code
 * CollectionModel<ProbeModel>}, deren Items jeweils {@code setEntity(ProbeEntity)} tragen
 * (entityClass=ProbeEntity). Fuer P2 wird beobachtet, ob
 *
 * <ul>
 *   <li>(a) die Collection-Items die Item-Rels ({@code self}/{@code edit}) tragen, die am
 *       Owner-Controller via Item-entityClass deklariert sind;
 *   <li>(b) die Collection selbst die {@code create}-Collection-Rel traegt, obwohl sie von einem
 *       NICHT-Owner-Controller serviert wird;
 *   <li>(c) wie sich eine LEERE Collection verhaelt.
 * </ul>
 */
@RestController
@ConditionalOnProperty(name = "task-management.test.affordance-probe.enabled", havingValue = "true")
@RequestMapping("/probe/nonowner")
public class ProbeNonOwnerCollectionController {

  /** Nicht-leere Collection: zwei Items, jeweils mit gesetzter ProbeEntity. */
  @GetMapping("/list")
  @ApiFindResponse(ProbeModel.class)
  public ResponseEntity<CollectionModel<ProbeModel>> list() {
    final ProbeModel a = new ProbeModel(UUID.randomUUID(), "item-a");
    a.setEntity(new ProbeEntity(1L, a.getId()));
    final ProbeModel b = new ProbeModel(UUID.randomUUID(), "item-b");
    b.setEntity(new ProbeEntity(2L, b.getId()));
    return ResponseEntity.ok(CollectionModel.of(List.of(a, b)));
  }

  /** Leere Collection: Nachweis des Verhaltens fuer P2c. */
  @GetMapping("/empty")
  @ApiFindResponse(ProbeModel.class)
  public ResponseEntity<CollectionModel<ProbeModel>> empty() {
    return ResponseEntity.ok(CollectionModel.of(List.of()));
  }

  /** P5 (MVC-HAL-Wire): PagedModel ueber den echten HTTP-/HAL-Konverter. */
  @GetMapping("/paged")
  @ApiFindResponse(ProbeModel.class)
  public ResponseEntity<PagedModel<ProbeModel>> paged() {
    final ProbeModel a = new ProbeModel(UUID.randomUUID(), "paged-a");
    final ProbeModel b = new ProbeModel(UUID.randomUUID(), "paged-b");
    return ResponseEntity.ok(
        PagedModel.of(List.of(a, b), new PagedModel.PageMetadata(2, 0, 2, 1)));
  }

  /** P5 (MVC-HAL-Wire): Keyset-Collection ueber den echten HTTP-/HAL-Konverter. */
  @GetMapping("/keyset")
  @ApiFindResponse(ProbeModel.class)
  public ResponseEntity<KeysetCollectionModel> keyset() {
    final ProbeModel a = new ProbeModel(UUID.randomUUID(), "keyset-a");
    final ProbeModel b = new ProbeModel(UUID.randomUUID(), "keyset-b");
    return ResponseEntity.ok(
        new KeysetCollectionModel(
            List.of(a, b), "cursor-abc", true, Instant.parse("2026-11-05T09:30:00Z")));
  }
}
