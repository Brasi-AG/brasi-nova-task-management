package de.brasi.nova.taskmanagement.affordanceprobe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.keel.framework.api.hateoas.annotation.EnableNovaHateoas;
import io.keel.framework.api.hateoas.annotation.HateoasLinkTarget;
import io.keel.framework.api.openapi.annotation.ApiFindResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPIKE-WEGWERF-ARTEFAKTE (PLAN #141 Slice 0). KEIN Produktionscode.
 *
 * <p>Zwei P3-Snapshot-Controller, die dieselbe {@link ProbeInfoModel}-Struktur (mit
 * verschachtelten {@link ProbeNestedInfoModel}) servieren:
 *
 * <ul>
 *   <li>{@link WithoutOwner}: PLAIN {@code @RestController} OHNE {@code @EnableNovaHateoas}.
 *   <li>{@link WithOwner}: {@code @RestController} MIT {@code @EnableNovaHateoas} (eigener
 *       Regel-Owner via {@link ProbeSnapshotEntity}).
 * </ul>
 *
 * <p>Nachweisziel: ob der verschachtelte {@code self}-Link (aus {@code @SelfLinkTarget} am nested
 * InfoModel) nur erscheint, wenn der servierende Handler unter einem Regel-Owner liegt
 * (BLOCKER-1-Pruefung).
 */
public final class ProbeSnapshotControllers {

  private ProbeSnapshotControllers() {}

  private static ProbeInfoModel sampleSnapshot() {
    return new ProbeInfoModel(
        UUID.randomUUID(),
        List.of(
            new ProbeNestedInfoModel(UUID.randomUUID(), "nested-1"),
            new ProbeNestedInfoModel(UUID.randomUUID(), "nested-2")));
  }

  /** P3a: Snapshot-Handler OHNE {@code @EnableNovaHateoas}. */
  @RestController
  @ConditionalOnProperty(name = "task-management.test.affordance-probe.enabled", havingValue = "true")
  @RequestMapping("/probe/snapshot-noowner")
  public static class WithoutOwner {

    @GetMapping("/view")
    @ApiFindResponse(ProbeInfoModel.class)
    public ResponseEntity<ProbeInfoModel> view() {
      return ResponseEntity.ok(sampleSnapshot());
    }
  }

  /** P3b: Snapshot-Handler MIT {@code @EnableNovaHateoas} (eigener Regel-Owner). */
  @RestController
  @ConditionalOnProperty(name = "task-management.test.affordance-probe.enabled", havingValue = "true")
  @RequestMapping("/probe/snapshot-owner")
  @EnableNovaHateoas(entityClass = ProbeSnapshotEntity.class)
  public static class WithOwner {

    @GetMapping("/view")
    @HateoasLinkTarget("probe_snapshot_view")
    @ApiFindResponse(ProbeInfoModel.class)
    public ResponseEntity<ProbeInfoModel> view() {
      return ResponseEntity.ok(sampleSnapshot());
    }
  }
}
