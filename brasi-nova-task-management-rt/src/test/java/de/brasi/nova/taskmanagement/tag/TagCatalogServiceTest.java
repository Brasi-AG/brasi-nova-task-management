package de.brasi.nova.taskmanagement.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.repository.TaskTagRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.resolver.DictionaryResolverException;
import de.brasi.nova.taskmanagement.tag.model.TagCreateInput;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import de.brasi.nova.taskmanagement.tag.model.TagUpdateInput;
import io.keel.framework.api.exception.EntityNotFoundException;
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
 * <ol>
 *   <li>create: name validiert (blank -> 400), severityCode -> Id aufgeloest, TENANT_ID=0
 *   <li>update: name/severity patchbar, nicht gefunden -> 404
 *   <li>list: severityId -> severityCode per Resolver aufgeloest
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
  @Mock private DictionaryCodeResolver resolver;

  private TagCatalogService service;

  @BeforeEach
  void setUp() {
    service = new TagCatalogService(tagRepository, resolver);

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
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> {
      final TaskTag t = inv.getArgument(0);
      t.setUuid(tagUuid);
      t.setId(1000001000L);
      return t;
    });

    // Act
    final UUID result = service.create(new TagCreateInput("Bug", SEVERITY_CODE_PRIMARY));

    // Assert: UUID zurueckgegeben
    assertThat(result).isEqualTo(tagUuid);

    // gespeicherter Tag: TENANT_ID=0, ENTRY_TYPE=SYSTEM_PUBLIC, BEZEICHNUNG=Bug
    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    final TaskTag saved = captor.getValue();
    assertThat(saved.getTenantId()).as("TENANT_ID muss 0 sein (globaler Katalog)").isEqualTo(0L);
    assertThat(saved.getDictionaryEntryType()).as("ENTRY_TYPE muss SYSTEM_PUBLIC sein").isEqualTo("SYSTEM_PUBLIC");
    assertThat(saved.getEntryValue()).as("BEZEICHNUNG muss 'Bug' sein").isEqualTo("Bug");
    assertThat(saved.getSeverityId()).as("SEVERITY_ID muss aufgeloest sein").isEqualTo(SEVERITY_ID_PRIMARY);
  }

  @Test
  @DisplayName("create_blank_name_liefert_400: EntityValidationException bei leerem Namen")
  void create_blank_name_liefert_400() {
    assertThatThrownBy(() -> service.create(new TagCreateInput("  ", SEVERITY_CODE_PRIMARY)))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("create_null_name_liefert_400: EntityValidationException bei null-Namen")
  void create_null_name_liefert_400() {
    assertThatThrownBy(() -> service.create(new TagCreateInput(null, SEVERITY_CODE_PRIMARY)))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName(
      "create_name_ueber_200_liefert_400: Name > 200 Zeichen (nvarchar(200)-Spalte) -> 400 statt DB-500")
  void create_name_ueber_200_liefert_400() {
    assertThatThrownBy(
            () -> service.create(new TagCreateInput("x".repeat(201), SEVERITY_CODE_PRIMARY)))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName(
      "update_name_ueber_200_liefert_400: Name-Patch > 200 Zeichen -> 400 (keine DB-500)")
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
                    new TagUpdateInput(PatchValue.of("x".repeat(201)), PatchValue.absent())))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("create_unbekannter_severityCode_liefert_400: EntityValidationException bei unbekanntem Code")
  void create_unbekannter_severity_code_liefert_400() {
    when(resolver.resolveTagSeverity("GIBTS_NICHT"))
        .thenThrow(new DictionaryResolverException("CODE 'GIBTS_NICHT' nicht gefunden."));

    assertThatThrownBy(() -> service.create(new TagCreateInput("TestTag", "GIBTS_NICHT")))
        .isInstanceOf(EntityValidationException.class)
        .hasMessageContaining("GIBTS_NICHT");
  }

  @Test
  @DisplayName("create_null_severityCode_liefert_400: EntityValidationException bei null severityCode (F-P2a)")
  void create_null_severity_code_liefert_400() {
    // null severityCode darf kein NPE (500) auslösen, sondern 400
    assertThatThrownBy(() -> service.create(new TagCreateInput("ValidName", null)))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("create_blank_severityCode_liefert_400: EntityValidationException bei leerem severityCode (F-P2a)")
  void create_blank_severity_code_liefert_400() {
    assertThatThrownBy(() -> service.create(new TagCreateInput("ValidName", "  ")))
        .isInstanceOf(EntityValidationException.class);
  }

  @Test
  @DisplayName("update_present_null_severityCode_liefert_400: EntityValidationException bei PatchValue present+null (F-P2a)")
  void update_present_null_severity_code_liefert_400() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001099L);
    existing.setEntryValue("SomeName");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));

    // PatchValue.ofNull() = present(null) -> muss 400 sein, nicht NPE (500)
    assertThatThrownBy(() -> service.update(tagUuid,
        new TagUpdateInput(PatchValue.absent(), PatchValue.ofNull())))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- update ---------------------------------------------------------------------------------

  @Test
  @DisplayName("update_name_und_severity_patchen: Tag-Name und Severity werden aktualisiert")
  void update_name_und_severity_patchen() {
    // Arrange: existierender Tag
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001001L);
    existing.setEntryValue("OldName");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    // Act
    service.update(tagUuid, new TagUpdateInput(
        PatchValue.of("NewName"),
        PatchValue.of(SEVERITY_CODE_DANGER)));

    // Assert: Name + Severity aktualisiert
    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getEntryValue()).isEqualTo("NewName");
    assertThat(captor.getValue().getSeverityId()).isEqualTo(SEVERITY_ID_DANGER);
  }

  @Test
  @DisplayName("update_nur_name_patchen: nur Name aendern, severity unberuehrt")
  void update_nur_name_patchen() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001002L);
    existing.setEntryValue("OldName");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    existing.setTenantId(0L);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));
    when(tagRepository.save(any(TaskTag.class))).thenAnswer(inv -> inv.getArgument(0));

    service.update(tagUuid, new TagUpdateInput(PatchValue.of("NeuName"), PatchValue.absent()));

    final ArgumentCaptor<TaskTag> captor = ArgumentCaptor.forClass(TaskTag.class);
    verify(tagRepository).save(captor.capture());
    assertThat(captor.getValue().getEntryValue()).isEqualTo("NeuName");
    assertThat(captor.getValue().getSeverityId()).isEqualTo(SEVERITY_ID_PRIMARY); // unveraendert
  }

  @Test
  @DisplayName("update_nicht_gefunden_liefert_404: EntityNotFoundException bei unbekannter UUID")
  void update_nicht_gefunden_liefert_404() {
    when(tagRepository.findByUuid(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(UUID.randomUUID(),
        new TagUpdateInput(PatchValue.of("Test"), PatchValue.absent())))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  @DisplayName("update_blank_name_liefert_400: EntityValidationException bei leerem Namen im Update")
  void update_blank_name_liefert_400() {
    final UUID tagUuid = UUID.randomUUID();
    final TaskTag existing = new TaskTag();
    existing.setUuid(tagUuid);
    existing.setId(1000001003L);
    existing.setEntryValue("OldName");
    existing.setSeverityId(SEVERITY_ID_PRIMARY);
    when(tagRepository.findByUuid(tagUuid.toString())).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.update(tagUuid,
        new TagUpdateInput(PatchValue.of("  "), PatchValue.absent())))
        .isInstanceOf(EntityValidationException.class);
  }

  // ---- list -----------------------------------------------------------------------------------

  @Test
  @DisplayName("list_mapping_severity_id_zu_code: severityId wird per Resolver auf CODE aufgeloest")
  void list_mapping_severity_id_zu_code() {
    // Arrange: zwei Tags mit unterschiedlichen Severities
    final UUID tagUuid1 = UUID.randomUUID();
    final UUID tagUuid2 = UUID.randomUUID();

    final TaskTag tag1 = new TaskTag();
    tag1.setUuid(tagUuid1);
    tag1.setId(1000001010L);
    tag1.setEntryValue("Fehler");
    tag1.setSeverityId(SEVERITY_ID_DANGER);
    tag1.setTenantId(0L);

    final TaskTag tag2 = new TaskTag();
    tag2.setUuid(tagUuid2);
    tag2.setId(1000001011L);
    tag2.setEntryValue("Hauptfunktion");
    tag2.setSeverityId(SEVERITY_ID_PRIMARY);
    tag2.setTenantId(0L);

    when(tagRepository.findAllValidAndActive()).thenReturn(List.of(tag1, tag2));

    // Act
    final List<TagModel> models = service.list();

    // Assert: Mapping korrekt
    assertThat(models).hasSize(2);
    assertThat(models.get(0).id()).isEqualTo(tagUuid1);
    assertThat(models.get(0).name()).isEqualTo("Fehler");
    assertThat(models.get(0).severityCode()).isEqualTo(SEVERITY_CODE_DANGER);
    assertThat(models.get(1).id()).isEqualTo(tagUuid2);
    assertThat(models.get(1).name()).isEqualTo("Hauptfunktion");
    assertThat(models.get(1).severityCode()).isEqualTo(SEVERITY_CODE_PRIMARY);
  }
}
