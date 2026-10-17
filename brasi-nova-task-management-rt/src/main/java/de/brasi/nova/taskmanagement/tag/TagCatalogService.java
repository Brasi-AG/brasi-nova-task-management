package de.brasi.nova.taskmanagement.tag;

import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.repository.TaskTagRepository;
import de.brasi.nova.taskmanagement.resolver.DictionaryCodeResolver;
import de.brasi.nova.taskmanagement.resolver.DictionaryResolverException;
import de.brasi.nova.taskmanagement.tag.model.TagCreateInput;
import de.brasi.nova.taskmanagement.tag.model.TagModel;
import de.brasi.nova.taskmanagement.tag.model.TagUpdateInput;
import io.keel.framework.api.exception.EntityNotFoundException;
import io.keel.framework.api.security.tenant.TenantContextHolder;
import io.keel.framework.api.security.tenant.TenantInfo;
import io.keel.framework.api.validation.EntityValidationException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaktionale Kommando- und Lese-Operationen fuer den globalen Tag-Katalog.
 *
 * <p>Tags sind global (TENANT_ID=0). Alle Operationen laufen daher unter
 * {@code TenantContextHolder.runWithTenant(TenantInfo.global(), ...)}, damit die
 * {@link io.keel.framework.api.annotation.db.NovaQuery}-Methoden des Repositories den
 * globalen Tenant-Kontext korrekt nutzen.
 *
 * <p><b>R-TAG:</b> Neu angelegte Tags erhalten {@code ENTRY_TYPE='SYSTEM_PUBLIC'} und
 * {@code TENANT_ID=0}, damit der {@code NOVA:DICTIONARY_VISIBILITY}-Marker sie fuer ALLE
 * Tenants sichtbar macht.
 */
@Service
public class TagCatalogService {

  private static final Logger log = LoggerFactory.getLogger(TagCatalogService.class);

  private final TaskTagRepository tagRepository;
  private final DictionaryCodeResolver resolver;

  /** Konstruktor-Injektion aller Abhaengigkeiten. */
  public TagCatalogService(
      final TaskTagRepository tagRepository,
      final DictionaryCodeResolver resolver) {
    this.tagRepository = tagRepository;
    this.resolver = resolver;
  }

  /**
   * Liefert alle aktiven Tags des globalen Katalogs.
   *
   * @return Liste der Tags als Modell-DTOs (leer wenn kein Tag vorhanden)
   */
  public List<TagModel> list() {
    final List<TaskTag> tags =
        TenantContextHolder.runWithTenant(TenantInfo.global(), tagRepository::findAllValidAndActive);
    return tags.stream().map(this::toModel).collect(Collectors.toList());
  }

  /**
   * Legt einen neuen Tag im globalen Katalog an.
   *
   * <p>Name muss nicht leer sein; unbekannter {@code severityCode} -> 400.
   * Der neue Tag erhaelt {@code TENANT_ID=0} und {@code ENTRY_TYPE='SYSTEM_PUBLIC'} (R-TAG).
   *
   * @param in Eingabe-DTO
   * @return externe UUID des angelegten Tags
   * @throws EntityValidationException bei leerem Namen oder unbekanntem severityCode (HTTP 400)
   */
  @Transactional
  public UUID create(final TagCreateInput in) {
    // Name-Validierung
    if (in.name() == null || in.name().isBlank()) {
      throw new EntityValidationException("Tag-Name darf nicht leer sein.");
    }

    // severityCode-Pflichtfeld: null/blank -> 400 (verhindert NPE in resolveTagSeverity, F-P2a)
    if (in.severityCode() == null || in.severityCode().isBlank()) {
      throw new EntityValidationException("severityCode darf nicht leer sein.");
    }

    // SeverityCode -> interne ID (unbekannter Code -> 400)
    final Long severityId;
    try {
      severityId = resolver.resolveTagSeverity(in.severityCode());
    } catch (DictionaryResolverException e) {
      throw new EntityValidationException(
          "Ungueltiger severityCode: '" + in.severityCode() + "'.");
    }

    // Tag anlegen im globalen Scope
    final TaskTag tag = new TaskTag();
    tag.setTenantId(0L);
    tag.setEntryValue(in.name());
    tag.setDictionaryEntryType("SYSTEM_PUBLIC");
    tag.setSeverityId(severityId);
    tag.setValidFrom(LocalDate.now());

    final TaskTag saved =
        TenantContextHolder.runWithTenant(TenantInfo.global(), () -> tagRepository.save(tag));

    log.info("TagCatalogService.create: Neuer Tag '{}' (id={}) angelegt.", in.name(), saved.getUuid());
    return saved.getUuid();
  }

  /**
   * Aktualisiert einen bestehenden Tag im globalen Katalog.
   *
   * <p>Nur die in der Eingabe gesetzten Felder werden aktualisiert.
   * Nicht gefunden -> 404 neutral; unbekannter severityCode -> 400.
   *
   * @param tagExternalId externe UUID des Tags
   * @param in            Patch-Eingabe
   * @throws EntityNotFoundException   wenn Tag nicht gefunden (HTTP 404)
   * @throws EntityValidationException bei leerem Namen oder unbekanntem severityCode (HTTP 400)
   */
  @Transactional
  public void update(final UUID tagExternalId, final TagUpdateInput in) {
    // Tag global laden (404-Masking neutral)
    final Optional<TaskTag> found =
        TenantContextHolder.runWithTenant(
            TenantInfo.global(), () -> tagRepository.findByUuid(tagExternalId.toString()));
    if (found.isEmpty()) {
      throw new EntityNotFoundException("Tag nicht gefunden.");
    }
    final TaskTag tag = found.get();

    // name-Patch
    if (in.name().isPresent()) {
      final String newName = in.name().get();
      if (newName == null || newName.isBlank()) {
        throw new EntityValidationException("Tag-Name darf nicht leer sein.");
      }
      tag.setEntryValue(newName);
    }

    // severityCode-Patch: present + null/blank -> 400 (F-P2a); unbekannter Code -> 400
    if (in.severityCode().isPresent()) {
      final String newSeverityCode = in.severityCode().get();
      if (newSeverityCode == null || newSeverityCode.isBlank()) {
        throw new EntityValidationException("severityCode darf nicht leer sein.");
      }
      final Long newSeverityId;
      try {
        newSeverityId = resolver.resolveTagSeverity(newSeverityCode);
      } catch (DictionaryResolverException e) {
        throw new EntityValidationException(
            "Ungueltiger severityCode: '" + newSeverityCode + "'.");
      }
      tag.setSeverityId(newSeverityId);
    }

    TenantContextHolder.runWithTenant(TenantInfo.global(), () -> tagRepository.save(tag));
    log.info("TagCatalogService.update: Tag {} aktualisiert.", tagExternalId);
  }

  // ---- Mapping ---------------------------------------------------------------------------------

  /**
   * Mappt eine {@link TaskTag}-Entity auf ein {@link TagModel}.
   *
   * @param tag die Tag-Entity
   * @return gemapptes Modell
   */
  private TagModel toModel(final TaskTag tag) {
    final String severityCode = resolver.resolveTagSeverityCode(tag.getSeverityId());
    return new TagModel(tag.getUuid(), tag.getEntryValue(), severityCode);
  }
}
