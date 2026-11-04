package de.brasi.nova.taskmanagement.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TagSeverityColors;
import de.brasi.nova.taskmanagement.repository.TaskTagAssignmentQueryRepository;
import de.brasi.nova.taskmanagement.repository.TaskTagRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.tag.model.TagCreateInput;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import de.brasi.nova.taskmanagement.tag.model.TagUpdateInput;
import io.keel.framework.api.exception.BusinessRuleException;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.model.EntityState;
import io.keel.framework.api.service.model.PatchValue;
import io.keel.framework.api.validation.EntityValidationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit-Tests fuer {@link TagCatalogService} (gemockt, kein DB-Zugriff).
 *
 * <p>TDD-Rationale: beweist die Kern-Invarianten:
 *
 * <ol>
 *   <li>create: name validiert (blank -&gt; 400); severityCode und color optional; color
 *       normalisiert/validiert; canonical-Check; Farb-Ableitung aus severity; neutraler Tag erlaubt
 *   <li>update: name/severity/color patchbar; present+null color = Farbe+severity leeren; nicht
 *       gefunden -&gt; 404
 *   <li>list/toModel: severityId -&gt; severityCode per Resolver (null-safe), color durchgereicht
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TagCatalogServiceTest {

  private static final long SEVERITY_ID_PRIMARY = 601L;
  private static final String SEVERITY_CODE_PRIMARY = "PRIMARY";
  private static final long SEVERITY_ID_DANGER = 604L;
  private static final String SEVERITY_CODE_DANGER = "DANGER";

  @Mock private TaskTagRepository tagRepository;
  @Mock private TaskTagAssignmentQueryRepository tagAssignmentQueryRepository;
  @Mock private DictionaryCodeResolver resolver;

  private TagCatalogService service;

  @BeforeEach
  void setUp() {
    service = new TagCatalogService(tagRepository, tagAssignmentQueryRepository, resolver);

    // Mindest-Stubs fuer den Resolver
    when(resolver.resolveTagSeverity(SEVERITY_CODE_PRIMARY)).thenReturn(SEVERITY_ID_PRIMARY);
    when(resolver.resolveTagSeverity(SEVERITY_CODE_DANGER)).thenReturn(SEVERITY_ID_DANGER);
    when(resolver.resolveTagSeverityCode(SEVERITY_ID_PRIMARY)).thenReturn(SEVERITY_CODE_PRIMARY);
    when(resolver.resolveTagSeverityCode(SEVERITY_ID_DANGER)).thenReturn(SEVERITY_CODE_DANGER);
  }

  // ---- create ---------------------------------------------------------------------------------

  @Test
  @DisplayName("create_name_und_severity_korrekt: Tag anlegen mit gueltigen Werten -> UUID zurueck")
  void create_name_und_severity_korrekt() {
    // Arrange: save gibt Tag mit UUID zurueck
    final UUID tagUuid = UUID.randomUUID();
    when(tagRepository.save(any(TaskTag.class)))
        .thenAnswer(
            inv -> {
              final TaskTag t = inv.getArgument(0);
              t.setUuid(tagUuid);
              t.setId(1000001000L);
              return t;
            });

    // Act
    final UUID result = service.create(new TagCreateInput("Bug", SEVERITY_CODE_PRIMARY, null));

    // Assert: UUID zurueckgegeben
    assertThat(result).isEqualTo(tagUuid);

    // gespeicherter Tag: TENANT_ID=0, ENTRY_TYPE=SYSTEM_PUBLIC, BEZEICHNUNG=Bug
    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    final TaskTag saved = captor.getValue();
    assertThat(saved.getTenantId()).as("TENANT_ID muss 0 sein (globaler Katalog)").isEqualTo(0L);
    assertThat(saved.getDictionaryEntryType())
        .as("ENTRY_TYPE muss SYSTEM_PUBLIC sein")
        .isEqualTo("SYSTEM_PUBLIC");
    assertThat(saved.getEntryValue()).as("BEZEICHNUNG muss 'Bug' sein").isEqualTo("Bug");
    assertThat(saved.getSeverityId())
        .as("SEVERITY_ID muss aufgeloest sein")
        .isEqualTo(SEVERITY_ID_PRIMARY);
    // color ohne expliziten Wert -> aus severity abgeleitet (PRIMARY-Hex)
    assertThat(saved.getColor())
        .as("COLOR aus severity abgeleitet")
        .isEqualTo(TagSeverityColors.PRIMARY);
  }

  @Test
  @DisplayName("create_blank_name_liefert_400: EntityValidationException bei leerem Namen")
  void create_blank_name_liefert_400() {
    assertThatThrownBy(() -> service.create(new TagCreateInput("  ", SEVERITY_CODE_PRIMARY, null)))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("create_null_name_liefert_400: EntityValidationException bei null-Namen")
  void create_null_name_liefert_400() {
    assertThatThrownBy(() -> service.create(new TagCreateInput(null, SEVERITY_CODE_PRIMARY, null)))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName(
      "create_name_ueber_200_liefert_400: Name > 200 Zeichen (nvarchar(200)-Spalte) -> 400 statt DB-500")
  void create_name_ueber_200_liefert_400() {
    assertThatThrownBy(
            () -> service.create(new TagCreateInput("x".repeat(201), SEVERITY_CODE_PRIMARY, null)))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("update_name_ueber_200_liefert_400: Name-Patch > 200 Zeichen -> 400 (keine DB-500)")
  void update_name_ueber_200_liefert_400() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001099L);
    existing.setEntryValue("OldName");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.update(
                    tagUuid,
                    new TagUpdateInput(
                        PatchValue.of("x".repeat(201)), PatchValue.absent(), PatchValue.absent())))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName(
      "create_unbekannter_severityCode_liefert_400: EntityValidationException bei unbekanntem Code")
  void create_unbekannter_severity_code_liefert_400() {
    // "GIBTS_NICHT" ist nicht kanonisch -> 400 greift bereits beim canonical-Check,
    // bevor der Resolver aufgerufen wird. (Kein Resolver-Stub noetig.)
    assertThatThrownBy(() -> service.create(new TagCreateInput("TestTag", "GIBTS_NICHT", null)))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("GIBTS_NICHT");
  }

  @Test
  @DisplayName("create_neutral_name_only: name only -> COLOR null, SEVERITY_ID null (AC2b)")
  void create_neutral_name_only_liefert_null_color_und_severity() {
    final UUID tagUuid = UUID.randomUUID();
    when(tagRepository.save(any(TaskTag.class)))
        .thenAnswer(
            inv -> {
              final TaskTag t = inv.getArgument(0);
              t.setUuid(tagUuid);
              t.setId(1000001005L);
              return t;
            });

    final UUID result = service.create(new TagCreateInput("Neutral", null, null));

    assertThat(result).isEqualTo(tagUuid);
    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    final TaskTag saved = captor.getValue();
    assertThat(saved.getColor()).as("neutraler Tag: COLOR null").isNull();
    assertThat(saved.getSeverityId()).as("neutraler Tag: SEVERITY_ID null").isNull();
  }

  @Test
  @DisplayName(
      "create_color_only: name + color ohne severity -> uppercase-Hex, severity null (AC1)")
  void create_color_only_normalisiert_und_severity_null() {
    final UUID tagUuid = UUID.randomUUID();
    when(tagRepository.save(any(TaskTag.class)))
        .thenAnswer(
            inv -> {
              final TaskTag t = inv.getArgument(0);
              t.setUuid(tagUuid);
              t.setId(1000001006L);
              return t;
            });

    service.create(new TagCreateInput("Farbe", null, "#f87171"));

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    final TaskTag saved = captor.getValue();
    assertThat(saved.getColor()).as("COLOR normalisiert uppercase").isEqualTo("#F87171");
    assertThat(saved.getSeverityId()).as("ohne severity -> null").isNull();
  }

  @Test
  @DisplayName(
      "create_severity_derive: name + severityCode DANGER ohne color -> COLOR aus severity (AC2)")
  void create_severity_derive_color() {
    final UUID tagUuid = UUID.randomUUID();
    when(tagRepository.save(any(TaskTag.class)))
        .thenAnswer(
            inv -> {
              final TaskTag t = inv.getArgument(0);
              t.setUuid(tagUuid);
              t.setId(1000001007L);
              return t;
            });

    service.create(new TagCreateInput("Fehler", SEVERITY_CODE_DANGER, null));

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    final TaskTag saved = captor.getValue();
    assertThat(saved.getColor())
        .as("COLOR aus DANGER abgeleitet")
        .isEqualTo(TagSeverityColors.DANGER);
    assertThat(saved.getColor()).isEqualTo("#F87171");
    assertThat(saved.getSeverityId()).isEqualTo(SEVERITY_ID_DANGER);
  }

  @Test
  @DisplayName(
      "create_hex_und_severity_gleichzeitig: expliziter Hex gewinnt vor Ableitung; severityId gesetzt (Fallmatrix Zeile 1)")
  void create_hex_und_severity_gleichzeitig_hex_gewinnt() {
    final UUID tagUuid = UUID.randomUUID();
    when(tagRepository.save(any(TaskTag.class)))
        .thenAnswer(
            inv -> {
              final TaskTag t = inv.getArgument(0);
              t.setUuid(tagUuid);
              t.setId(1000001009L);
              return t;
            });

    // color (abweichend vom DANGER-Hex #F87171) UND severityCode DANGER gleichzeitig gesetzt
    service.create(new TagCreateInput("Beides", SEVERITY_CODE_DANGER, "#123456"));

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    final TaskTag saved = captor.getValue();
    assertThat(saved.getColor()).as("expliziter Hex gewinnt vor Ableitung").isEqualTo("#123456");
    assertThat(saved.getSeverityId())
        .as("severityId trotzdem gesetzt")
        .isEqualTo(SEVERITY_ID_DANGER);
  }

  @Test
  @DisplayName("create_ungueltiger_hex_liefert_400: 'red', '#12', '#GGGGGG' -> 400 (AC7)")
  void create_ungueltiger_hex_liefert_400() {
    assertThatThrownBy(() -> service.create(new TagCreateInput("T1", null, "red")))
        .isInstanceOf(EntityValidationException.class);
    assertThatThrownBy(() -> service.create(new TagCreateInput("T2", null, "#12")))
        .isInstanceOf(EntityValidationException.class);
    assertThatThrownBy(() -> service.create(new TagCreateInput("T3", null, "#GGGGGG")))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName(
      "create_hex_rgb_kurzform: '#abc' (#RGB) wird akzeptiert und uppercase normalisiert (AC7)")
  void create_hex_rgb_kurzform_normalisiert() {
    final UUID tagUuid = UUID.randomUUID();
    when(tagRepository.save(any(TaskTag.class)))
        .thenAnswer(
            inv -> {
              final TaskTag t = inv.getArgument(0);
              t.setUuid(tagUuid);
              t.setId(1000001008L);
              return t;
            });

    service.create(new TagCreateInput("Kurz", null, "#abc"));

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getColor()).isEqualTo("#ABC");
  }

  @Test
  @DisplayName(
      "create_non_canonical_severityCode_liefert_400: 'EXTRA' nicht in BY_CODE -> 400 (ohne Resolver-Stub)")
  void create_non_canonical_severity_code_liefert_400() {
    assertThatThrownBy(() -> service.create(new TagCreateInput("TestTag", "EXTRA", null)))
        .isInstanceOf(EntityValidationException.class);
    // canonical-Check greift VOR dem Resolver -> Resolver nicht aufgerufen
    verify(resolver, never()).resolveTagSeverity("EXTRA");
  }

  @Test
  @DisplayName(
      "update_present_null_severityCode_liefert_400: EntityValidationException bei PatchValue present+null (F-P2a)")
  void update_present_null_severity_code_liefert_400() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001099L);
    existing.setEntryValue("SomeName");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));

    // PatchValue.ofNull() = present(null) -> muss 400 sein, nicht NPE (500)
    assertThatThrownBy(
            () ->
                service.update(
                    tagUuid,
                    new TagUpdateInput(
                        PatchValue.absent(), PatchValue.ofNull(), PatchValue.absent())))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- update ---------------------------------------------------------------------------------

  @Test
  @DisplayName("update_name_und_severity_patchen: Tag-Name und Severity werden aktualisiert")
  void update_name_und_severity_patchen() {
    // Arrange: existierender Tag (hat bereits eine Farbe -> wird nicht ueberschrieben)
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001001L);
    existing.setEntryValue("OldName");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    existing.setColor("#111111");
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    // Act
    service.update(
        tagUuid,
        new TagUpdateInput(
            PatchValue.of("NewName"), PatchValue.of(SEVERITY_CODE_DANGER), PatchValue.absent()));

    // Assert: Name + Severity aktualisiert; vorhandene Farbe bleibt
    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getEntryValue()).isEqualTo("NewName");
    assertThat(captor.getValue().getSeverityId()).isEqualTo(SEVERITY_ID_DANGER);
    assertThat(captor.getValue().getColor())
        .as("vorhandene Farbe bleibt bei severity-Set")
        .isEqualTo("#111111");
  }

  @Test
  @DisplayName("update_nur_name_patchen: nur Name aendern, severity + color unberuehrt")
  void update_nur_name_patchen() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001002L);
    existing.setEntryValue("OldName");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    existing.setColor("#ABCDEF");
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.update(
        tagUuid,
        new TagUpdateInput(PatchValue.of("NeuName"), PatchValue.absent(), PatchValue.absent()));

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getEntryValue()).isEqualTo("NeuName");
    assertThat(captor.getValue().getSeverityId()).isEqualTo(SEVERITY_ID_PRIMARY); // unveraendert
    assertThat(captor.getValue().getColor())
        .as("reiner Namens-Patch laesst color unangetastet")
        .isEqualTo("#ABCDEF");
  }

  @Test
  @DisplayName("update_nicht_gefunden_liefert_404: EntityNotFoundException bei unbekannter UUID")
  void update_nicht_gefunden_liefert_404() {
    when(tagRepository.findByUuid(any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.update(
                    UUID.randomUUID(),
                    new TagUpdateInput(
                        PatchValue.of("Test"), PatchValue.absent(), PatchValue.absent())))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  @DisplayName(
      "update_blank_name_liefert_400: EntityValidationException bei leerem Namen im Update")
  void update_blank_name_liefert_400() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001003L);
    existing.setEntryValue("OldName");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.update(
                    tagUuid,
                    new TagUpdateInput(
                        PatchValue.of("  "), PatchValue.absent(), PatchValue.absent())))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName(
      "update_color_present_null_leert_color_und_severity: present+null color -> COLOR null UND SEVERITY_ID null (AC5-i)")
  void update_color_present_null_leert_color_und_severity() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001020L);
    existing.setEntryValue("MitFarbe");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    existing.setColor("#60A5FA");
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.update(
        tagUuid, new TagUpdateInput(PatchValue.absent(), PatchValue.absent(), PatchValue.ofNull()));

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getColor()).as("color geleert").isNull();
    assertThat(captor.getValue().getSeverityId()).as("severity mitgeleert").isNull();
  }

  @Test
  @DisplayName(
      "update_severity_set_bei_null_color_leitet_color_ab: severity present bei aktuell null color -> color abgeleitet (AC5-iii)")
  void update_severity_set_bei_null_color_leitet_color_ab() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001021L);
    existing.setEntryValue("OhneFarbe");
    existing.setSeverityId(null);
    existing.setColor(null);
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.update(
        tagUuid,
        new TagUpdateInput(
            PatchValue.absent(), PatchValue.of(SEVERITY_CODE_DANGER), PatchValue.absent()));

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getSeverityId()).isEqualTo(SEVERITY_ID_DANGER);
    assertThat(captor.getValue().getColor())
        .as("color aus neuer severity abgeleitet")
        .isEqualTo(TagSeverityColors.DANGER);
  }

  @Test
  @DisplayName(
      "update_color_null_und_severity_set_konflikt_400: color present+null + severity present+Wert -> 400 (AC5-iv)")
  void update_color_null_und_severity_set_konflikt_400() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001022L);
    existing.setEntryValue("Konflikt");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    existing.setColor("#60A5FA");
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));

    // zusaetzlicher gueltiger Namens-Patch: beweist validate-vor-mutate (wird NICHT angewandt)
    assertThatThrownBy(
            () ->
                service.update(
                    tagUuid,
                    new TagUpdateInput(
                        PatchValue.of("NeuerName"),
                        PatchValue.of(SEVERITY_CODE_DANGER),
                        PatchValue.ofNull())))
        .isInstanceOf(EntityValidationException.class);
    // validate-vor-mutate: kein save UND die geladene Entity bleibt in ALLEN Feldern unmutiert
    verify(tagRepository, never()).save(any(TaskTag.class));
    assertThat(existing.getEntryValue())
        .as("Name trotz gueltigem Patch unveraendert")
        .isEqualTo("Konflikt");
    assertThat(existing.getColor()).as("color unveraendert").isEqualTo("#60A5FA");
    assertThat(existing.getSeverityId()).as("severity unveraendert").isEqualTo(SEVERITY_ID_PRIMARY);
  }

  @Test
  @DisplayName(
      "update_namenspatch_heilt_vorbestehende_invariantenverletzung_NICHT: color bleibt null (Interpretation A / AC5)")
  void update_namenspatch_laesst_vorbestehende_null_color_unangetastet() {
    // Vorbestehende Alt-Writer-Anomalie (color null + severity gesetzt). Nach SPEC unerreichbar
    // nach
    // dem Phase-1-V8-Deploy (atomarer Backfill + P2-Preflight + Single-Instance); eine etwaige
    // Anomalie
    // ist Sache der owner-gated D1-Detektion vor Phase 3, NICHT eines Heals auf beliebigem Update.
    // AC5: reiner Namens-Patch laesst color unveraendert.
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001026L);
    existing.setEntryValue("AltWriter");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    existing.setColor(null);
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.update(
        tagUuid,
        new TagUpdateInput(PatchValue.of("NeuName"), PatchValue.absent(), PatchValue.absent()));

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getEntryValue()).isEqualTo("NeuName");
    assertThat(captor.getValue().getColor())
        .as("Namens-Patch heilt vorbestehende Anomalie NICHT (AC5, Interpretation A)")
        .isNull();
    assertThat(captor.getValue().getSeverityId()).isEqualTo(SEVERITY_ID_PRIMARY);
    // keine Ableitung beim reinen Namens-Patch -> kein Reverse-Resolver-Call
    verify(resolver, never()).resolveTagSeverityCode(any());
  }

  @Test
  @DisplayName(
      "update_freier_hex_dann_namenspatch_erhaelt_hex: Hex set, dann separater Namens-Patch -> Hex bleibt (AC5-v)")
  void update_freier_hex_dann_namenspatch_erhaelt_hex() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001023L);
    existing.setEntryValue("Name1");
    existing.setSeverityId(null);
    existing.setColor(null);
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    // 1) freier Hex setzen
    service.update(
        tagUuid,
        new TagUpdateInput(PatchValue.absent(), PatchValue.absent(), PatchValue.of("#123456")));
    assertThat(existing.getColor()).isEqualTo("#123456");

    // 2) nur Name patchen -> Hex bleibt, keine Re-Ableitung
    service.update(
        tagUuid,
        new TagUpdateInput(PatchValue.of("Name2"), PatchValue.absent(), PatchValue.absent()));

    assertThat(existing.getEntryValue()).isEqualTo("Name2");
    assertThat(existing.getColor())
        .as("freier Hex erhalten, keine Re-Ableitung")
        .isEqualTo("#123456");
    assertThat(existing.getSeverityId()).isNull();
  }

  @Test
  @DisplayName("update_ungueltiger_hex_liefert_400: present + ungueltiger Hex -> 400")
  void update_ungueltiger_hex_liefert_400() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001024L);
    existing.setEntryValue("X");
    existing.setSeverityId(null);
    existing.setColor(null);
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.update(
                    tagUuid,
                    new TagUpdateInput(
                        PatchValue.absent(), PatchValue.absent(), PatchValue.of("nope"))))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("update_non_canonical_severityCode_liefert_400: 'EXTRA' -> 400 (ohne Resolver-Stub)")
  void update_non_canonical_severity_code_liefert_400() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001025L);
    existing.setEntryValue("X");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    existing.setColor("#60A5FA");
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.update(
                    tagUuid,
                    new TagUpdateInput(
                        PatchValue.absent(), PatchValue.of("EXTRA"), PatchValue.absent())))
        .isInstanceOf(EntityValidationException.class);
    verify(resolver, never()).resolveTagSeverity("EXTRA");
  }

  // ---- list / toModel -------------------------------------------------------------------------

  @Test
  @DisplayName("list_mapping_severity_id_zu_code: severityId wird per Resolver auf CODE aufgeloest")
  void list_mapping_severity_id_zu_code() {
    // Arrange: zwei Tags mit unterschiedlichen Severities + Farben
    final UUID tagUuid1 = UUID.randomUUID();
    final UUID tagUuid2 = UUID.randomUUID();

    final TaskTag tag1 = new TaskTag();
    tag1.setUuid(tagUuid1);
    tag1.setId(1000001010L);
    tag1.setEntryValue("Fehler");
    tag1.setSeverityId(SEVERITY_ID_DANGER);
    tag1.setColor("#F87171");
    tag1.setTenantId(0L);

    final TaskTag tag2 = new TaskTag();
    tag2.setUuid(tagUuid2);
    tag2.setId(1000001011L);
    tag2.setEntryValue("Hauptfunktion");
    tag2.setSeverityId(SEVERITY_ID_PRIMARY);
    tag2.setColor("#60A5FA");
    tag2.setTenantId(0L);

    when(tagRepository.findAllValidAndActive()).thenReturn(List.of(tag1, tag2));

    // Act
    final List<TagModel> models = service.list();

    // Assert: Mapping korrekt (inkl. color)
    assertThat(models).hasSize(2);
    assertThat(models.get(0).id()).isEqualTo(tagUuid1);
    assertThat(models.get(0).name()).isEqualTo("Fehler");
    assertThat(models.get(0).severityCode()).isEqualTo(SEVERITY_CODE_DANGER);
    assertThat(models.get(0).color()).isEqualTo("#F87171");
    assertThat(models.get(1).id()).isEqualTo(tagUuid2);
    assertThat(models.get(1).name()).isEqualTo("Hauptfunktion");
    assertThat(models.get(1).severityCode()).isEqualTo(SEVERITY_CODE_PRIMARY);
    assertThat(models.get(1).color()).isEqualTo("#60A5FA");
  }

  @Test
  @DisplayName(
      "toModel_null_severity_safe: severityId null -> severityCode null, color durchgereicht, kein Resolver-Call")
  void toModel_null_severity_safe() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag tag = new TaskTag();
    tag.setUuid(tagUuid);
    tag.setId(1000001030L);
    tag.setEntryValue("NurFarbe");
    tag.setSeverityId(null);
    tag.setColor("#ABCDEF");
    tag.setTenantId(0L);

    when(tagRepository.findAllValidAndActive()).thenReturn(List.of(tag));

    final List<TagModel> models = service.list();

    assertThat(models).hasSize(1);
    assertThat(models.get(0).severityCode()).as("null severity -> null code").isNull();
    assertThat(models.get(0).color()).as("color durchgereicht").isEqualTo("#ABCDEF");
    // kein Reverse-Resolver-Call bei null severityId
    verify(resolver, never()).resolveTagSeverityCode(any());
  }

  // ---- Intake #138 Slice 138-2: deactivate (retire) -------------------------------------------

  @Test
  @DisplayName(
      "deactivate_aktiver_tag_setzt_entity_state_deactive:"
          + " ACTIVE -> DEACTIVE via save; Zuordnungen unangetastet (Intake #138)")
  void deactivate_aktiver_tag_setzt_entity_state_deactive() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000002001L);
    existing.setEntryValue("ZumRetiren");
    existing.setTenantId(0L);
    existing.setEntityState(EntityState.ACTIVE);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.deactivate(tagUuid);

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getEntityState())
        .as("Retire setzt ENTITY_STATUS=DEACTIVE")
        .isEqualTo(EntityState.DEACTIVE);
    // Retire fasst keine Assignments an.
    verify(tagAssignmentQueryRepository, never()).deactivateAllByIds(any());
  }

  @Test
  @DisplayName(
      "deactivate_aktiver_tag_ausserhalb_gueltigkeit_nicht_404:"
          + " status-agnostischer Read (findByUuid) + ACTIVE-Check -> ein ACTIVE-Tag ausserhalb"
          + " seines Gueltigkeitsfensters wird deaktiviert, NICHT 404 (findActiveByUuid ungenutzt)")
  void deactivate_aktiver_tag_ausserhalb_gueltigkeit_nicht_404() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000002002L);
    existing.setEntryValue("AktivAberAbgelaufen");
    existing.setTenantId(0L);
    existing.setEntityState(EntityState.ACTIVE);
    // findByUuid liefert den ACTIVE-Tag (status-/gueltigkeitsagnostisch); der Service darf
    // findActiveByUuid (Gueltigkeitsfilter) NICHT nutzen - per verify(never()) unten belegt.
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.deactivate(tagUuid);

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getEntityState()).isEqualTo(EntityState.DEACTIVE);
    verify(tagRepository, never()).findActiveByUuid(any());
  }

  @Test
  @DisplayName(
      "deactivate_unbekannt_oder_bereits_deactive_liefert_404:"
          + " findByUuid empty ODER ENTITY_STATUS!=ACTIVE -> EntityNotFoundException, kein save")
  void deactivate_unbekannt_oder_bereits_deactive_liefert_404() {
    // (a) unbekannt
    final UUID unknownUuid = UUID.randomUUID();
    when(tagRepository.findByUuid(unknownUuid.toString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.deactivate(unknownUuid))
        .isInstanceOf(EntityNotFoundException.class);

    // (b) bereits DEACTIVE
    final UUID deactiveUuid = UUID.randomUUID();
    final TaskTag deactive = new TaskTag();
    deactive.setUuid(deactiveUuid);
    deactive.setId(1000002003L);
    deactive.setTenantId(0L);
    deactive.setEntityState(EntityState.DEACTIVE);
    when(tagRepository.findByUuid(deactiveUuid.toString())).thenReturn(Optional.of(deactive));
    assertThatThrownBy(() -> service.deactivate(deactiveUuid))
        .isInstanceOf(EntityNotFoundException.class);

    verify(tagRepository, never()).save(any(TaskTag.class));
  }

  // ---- Intake #138 Slice 138-3: delete (fail-closed) ------------------------------------------

  @Test
  @DisplayName(
      "delete_unreferenziert_setzt_deactive:"
          + " Count==0 -> ENTITY_STATUS=DEACTIVE via save (204-Pfad)")
  void delete_unreferenziert_setzt_deactive() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000002010L);
    existing.setEntryValue("Unreferenziert");
    existing.setTenantId(0L);
    existing.setEntityState(EntityState.ACTIVE);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagAssignmentQueryRepository.countActiveAssignmentsByTagIdAcrossTenants(existing.getId()))
        .thenReturn(0L);
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.delete(tagUuid);

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getEntityState()).isEqualTo(EntityState.DEACTIVE);
  }

  @Test
  @DisplayName(
      "delete_aktiver_tag_ausserhalb_gueltigkeit_erreicht_count_pfad:"
          + " status-agnostischer Read -> ein ACTIVE-Tag ausserhalb Gueltigkeit erreicht den"
          + " Referenz-Count (hier 0 -> DEACTIVE), NICHT 404")
  void delete_aktiver_tag_ausserhalb_gueltigkeit_erreicht_count_pfad() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000002012L);
    existing.setEntryValue("AktivAberAbgelaufen");
    existing.setTenantId(0L);
    existing.setEntityState(EntityState.ACTIVE);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagAssignmentQueryRepository.countActiveAssignmentsByTagIdAcrossTenants(existing.getId()))
        .thenReturn(0L);
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.delete(tagUuid);

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getEntityState()).isEqualTo(EntityState.DEACTIVE);
    verify(tagRepository, never()).findActiveByUuid(any());
  }

  @Test
  @DisplayName(
      "delete_referenziert_wirft_business_rule_mit_anzahl_und_bleibt_aktiv:"
          + " Count>0 -> BusinessRuleException (409) mit Anzahl in Meldung, kein save (Tag bleibt"
          + " ACTIVE)")
  void delete_referenziert_wirft_business_rule_mit_anzahl_und_bleibt_aktiv() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000002011L);
    existing.setEntryValue("Referenziert");
    existing.setTenantId(0L);
    existing.setEntityState(EntityState.ACTIVE);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagAssignmentQueryRepository.countActiveAssignmentsByTagIdAcrossTenants(existing.getId()))
        .thenReturn(3L);

    assertThatThrownBy(() -> service.delete(tagUuid))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("3")
        .hasMessageContaining("PATCH tagIds")
        .hasMessageContaining("deactivate");
    // fail-closed: kein save -> Tag bleibt ACTIVE.
    verify(tagRepository, never()).save(any(TaskTag.class));
    assertThat(existing.getEntityState()).isEqualTo(EntityState.ACTIVE);
  }

  @Test
  @DisplayName(
      "delete_unbekannt_oder_bereits_deactive_liefert_404:"
          + " findByUuid empty ODER ENTITY_STATUS!=ACTIVE -> EntityNotFoundException, keine"
          + " Count-Query, kein save")
  void delete_unbekannt_oder_bereits_deactive_liefert_404() {
    // (a) unbekannt
    final UUID unknownUuid = UUID.randomUUID();
    when(tagRepository.findByUuid(unknownUuid.toString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(unknownUuid))
        .isInstanceOf(EntityNotFoundException.class);

    // (b) bereits DEACTIVE
    final UUID deactiveUuid = UUID.randomUUID();
    final TaskTag deactive = new TaskTag();
    deactive.setUuid(deactiveUuid);
    deactive.setId(1000002013L);
    deactive.setTenantId(0L);
    deactive.setEntityState(EntityState.DEACTIVE);
    when(tagRepository.findByUuid(deactiveUuid.toString())).thenReturn(Optional.of(deactive));
    assertThatThrownBy(() -> service.delete(deactiveUuid))
        .isInstanceOf(EntityNotFoundException.class);

    verify(tagAssignmentQueryRepository, never()).countActiveAssignmentsByTagIdAcrossTenants(any());
    verify(tagRepository, never()).save(any(TaskTag.class));
  }
}
