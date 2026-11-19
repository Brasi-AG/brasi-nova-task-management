package de.brasi.nova.taskmanagement.attachment;

import static de.brasi.nova.taskmanagement.entity.enums.AttachmentTariff.ENTERPRISE;
import static de.brasi.nova.taskmanagement.entity.enums.AttachmentTariff.EXPERT;
import static de.brasi.nova.taskmanagement.entity.enums.AttachmentTariff.STANDARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskAttachmentLimit;
import de.brasi.nova.taskmanagement.entity.enums.AttachmentTariff;
import de.brasi.nova.taskmanagement.repository.TaskAttachmentLimitQueryRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.util.unit.DataSize;

/**
 * Unit-Tests fuer {@link AttachmentLimitPolicy} und {@link AttachmentFileLimit} (Intake #152, Task
 * A2; gemockt, kein Spring-Kontext).
 *
 * <p>Vertrag: Reihenfolge Config-Liste vor Tabelle vor Default STANDARD; Tabellenwert wird in die
 * Tarifspanne geklemmt, ohne Wert gilt die Obergrenze der Spanne; danach deckelt die harte
 * Obergrenze jeden Tarif. Die Properties entsprechen den Vorgaben aus {@code
 * application.properties}, die ENTERPRISE-Liste enthaelt den Mandanten 700.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttachmentLimitPolicyTest {

  private static final long MIB = 1_048_576L;
  private static final long ENTERPRISE_TENANT = 700L;
  private static final long TENANT = 560L;

  @Mock private TaskAttachmentLimitQueryRepository repo;

  private AttachmentLimitPolicy policy;

  @BeforeEach
  void setUp() {
    policy = policyWith(DataSize.ofMegabytes(15));
  }

  @Test
  void i152_config_listed_tenant_is_enterprise_max_even_if_table_says_standard() {
    when(repo.findActiveForTenant()).thenReturn(List.of(limit(STANDARD, null)));

    assertThat(policy.limitFor(ENTERPRISE_TENANT))
        .isEqualTo(new AttachmentFileLimit(ENTERPRISE, 15_728_640L));
  }

  @Test
  void i152_table_expert_without_max_uses_upper_bound() {
    when(repo.findActiveForTenant()).thenReturn(List.of(limit(EXPERT, null)));

    assertThat(policy.limitFor(TENANT)).isEqualTo(new AttachmentFileLimit(EXPERT, 10_485_760L));
  }

  @Test
  void i152_table_max_is_clamped_into_tariff_span() {
    assertThat(limitWith(EXPERT, 20 * MIB)).isEqualTo(10_485_760L);
    assertThat(limitWith(EXPERT, 1 * MIB)).isEqualTo(5_242_880L);
    assertThat(limitWith(ENTERPRISE, 12 * MIB)).isEqualTo(12_582_912L);
    assertThat(limitWith(ENTERPRISE, 9 * MIB)).isEqualTo(10_485_760L);
    assertThat(limitWith(STANDARD, 8 * MIB)).isEqualTo(5_242_880L);
  }

  @Test
  void i152_no_row_defaults_to_standard_5mb() {
    when(repo.findActiveForTenant()).thenReturn(List.of());

    assertThat(policy.limitFor(TENANT)).isEqualTo(new AttachmentFileLimit(STANDARD, 5_242_880L));
  }

  @Test
  void i152_hard_max_caps_every_tariff() {
    // enterpriseMax 20 MB, hardMax 15 MB
    when(repo.findActiveForTenant()).thenReturn(List.of());

    assertThat(policyWith(DataSize.ofMegabytes(20)).limitFor(ENTERPRISE_TENANT).maxFileBytes())
        .isEqualTo(15_728_640L);
  }

  @Test
  void i152_display_size() {
    assertThat(new AttachmentFileLimit(STANDARD, 5_242_880L).displaySize()).isEqualTo("5 MB");
    assertThat(new AttachmentFileLimit(ENTERPRISE, 12_582_912L + 524_288L).displaySize())
        .isEqualTo("12,5 MB");
  }

  /** Grenze fuer einen Mandanten ausserhalb der Config-Liste mit genau einer Tabellenzeile. */
  private long limitWith(final AttachmentTariff tariff, final long maxFileBytes) {
    when(repo.findActiveForTenant()).thenReturn(List.of(limit(tariff, maxFileBytes)));
    final AttachmentFileLimit result = policy.limitFor(TENANT);
    assertThat(result.tariff()).isEqualTo(tariff);
    return result.maxFileBytes();
  }

  private AttachmentLimitPolicy policyWith(final DataSize enterpriseMax) {
    return new AttachmentLimitPolicy(properties(enterpriseMax), repo);
  }

  /** Properties mit den Vorgaben aus application.properties; ENTERPRISE-Liste = [700]. */
  private static AttachmentProperties properties(final DataSize enterpriseMax) {
    return new AttachmentProperties(
        new AttachmentProperties.Storage(null),
        List.of(ENTERPRISE_TENANT),
        20,
        new AttachmentProperties.Limits(
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(10),
            enterpriseMax,
            DataSize.ofMegabytes(15)),
        new AttachmentProperties.Thumbnail(600, 0.85f, 50_000_000L),
        new AttachmentProperties.Cache(Duration.ofDays(1), Duration.ofDays(10)));
  }

  private static TaskAttachmentLimit limit(final AttachmentTariff tariff, final Long maxFileBytes) {
    final TaskAttachmentLimit limit = new TaskAttachmentLimit();
    limit.setTariff(tariff);
    limit.setMaxFileBytes(maxFileBytes);
    return limit;
  }
}
