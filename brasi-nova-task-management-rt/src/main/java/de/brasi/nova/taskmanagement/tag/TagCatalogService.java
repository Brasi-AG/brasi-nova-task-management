package de.brasi.nova.taskmanagement.tag;

import de.brasi.nova.taskmanagement.entity.TaskTag;
import de.brasi.nova.taskmanagement.entity.dictionary.code.TagSeverityColors;
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
import java.util.regex.Pattern;
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

  /**
   * Max. Laenge des Tag-Namens (BEZEICHNUNG) - entspricht der V1-Spalte {@code nvarchar(200)}. Die
   * geerbte {@code NovaDictionaryEntry.entryValue}-Validierung erlaubt 255; ohne diesen expliziten
   * Check wuerde ein 201-255-Zeichen-Name die DB-Zeile mit einem rohen DB-Fehler (500) statt einem
   * sauberen 400 ablehnen. Deshalb in beiden Write-Paths (create/update) explizit validieren.
   */
  private static final int MAX_NAME_LENGTH = 200;

  /**
   * Zulaessige Hex-Farbsyntax: {@code #RGB} (3 Stellen) oder {@code #RRGGBB} (6 Stellen),
   * Gross-/Kleinschreibung egal. Wird nach erfolgreicher Pruefung auf Grossbuchstaben normalisiert.
   */
  private static final Pattern HEX_COLOR =
      Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

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
   * <p>Name muss nicht leer sein. {@code severityCode} und {@code color} sind beide optional (Intake
   * #135, Phase 1):
   *
   * <ul>
   *   <li>gueltiger {@code color} (Hex) -> normalisierte Farbe; severity wie angegeben
   *   <li>nur {@code severityCode} (kanonisch) -> Farbe wird aus der severity abgeleitet
   *   <li>weder color noch severity -> neutraler Tag (COLOR null, SEVERITY_ID null)
   * </ul>
   *
   * <p>Ein ungueltiger Hex, ein unbekannter oder ein nicht-kanonischer {@code severityCode} fuehren
   * zu 400. Der neue Tag erhaelt {@code TENANT_ID=0} und {@code ENTRY_TYPE='SYSTEM_PUBLIC'} (R-TAG).
   *
   * @param in Eingabe-DTO
   * @return externe UUID des angelegten Tags
   * @throws EntityValidationException bei leerem Namen, ungueltigem Hex oder ungueltigem
   *     severityCode (HTTP 400)
   */
  @Transactional
  public UUID create(final TagCreateInput in) {
    // Name-Validierung
    if (in.name() == null || in.name().isBlank()) {
      throw new EntityValidationException("Tag-Name darf nicht leer sein.");
    }
    if (in.name().length() > MAX_NAME_LENGTH) {
      throw new EntityValidationException(
          "Tag-Name darf hoechstens " + MAX_NAME_LENGTH + " Zeichen haben.");
    }

    final boolean hasSeverity = in.severityCode() != null && !in.severityCode().isBlank();
    final boolean hasColor = in.color() != null && !in.color().isBlank();

    // color validieren/normalisieren (ungueltiger Hex -> 400)
    final String normColor = hasColor ? normalizeColor(in.color()) : null;

    // severityCode: canonical-Check VOR dem Resolver; dann aufloesen (unbekannt -> 400)
    final Long severityId;
    if (hasSeverity) {
      final String code = in.severityCode();
      requireCanonicalSeverity(code);
      severityId = resolveSeverity(code);
    } else {
      severityId = null;
    }

    // color-Endwert: expliziter Hex > Ableitung aus severity > null
    final String colorValue;
    if (hasColor) {
      colorValue = normColor;
    } else if (hasSeverity) {
      // kanonischer Code => BY_CODE.get(code) nie null (Ableitung)
      colorValue = TagSeverityColors.BY_CODE.get(in.severityCode());
    } else {
      colorValue = null;
    }

    // Tag anlegen im globalen Scope
    final TaskTag tag = new TaskTag();
    tag.setTenantId(0L);
    tag.setEntryValue(in.name());
    tag.setDictionaryEntryType("SYSTEM_PUBLIC");
    tag.setSeverityId(severityId);
    tag.setColor(colorValue);
    tag.setValidFrom(LocalDate.now());

    final TaskTag saved =
        TenantContextHolder.runWithTenant(TenantInfo.global(), () -> tagRepository.save(tag));

    log.info("TagCatalogService.create: Neuer Tag '{}' (id={}) angelegt.", in.name(), saved.getUuid());
    return saved.getUuid();
  }

  /**
   * Aktualisiert einen bestehenden Tag im globalen Katalog.
   *
   * <p>Nur die in der Eingabe gesetzten (present) Felder werden angefasst. Alle Validierungen laufen
   * VOR jeder Mutation (validate-Phase), damit ein spaeter Fehler keine Teil-Aenderung hinterlaesst.
   *
   * <ul>
   *   <li>{@code name}: present -> validieren + setzen; absent -> unveraendert
   *   <li>{@code severityCode}: absent -> unveraendert; present+null/blank -> 400 (F-P2a);
   *       present+nicht-kanonisch/unbekannt -> 400; present+gueltig -> setzen
   *   <li>{@code color}: absent -> unveraendert; present+gueltiger Hex -> normalisieren+setzen;
   *       present+ungueltiger Hex -> 400; present+null/blank -> bewusste Loeschung
   *       (COLOR null UND SEVERITY_ID null)
   * </ul>
   *
   * <p>Konflikt: color present+null/blank (Loeschung) UND severityCode present+Wert in derselben
   * Anfrage -> 400. Write-Invariante: wird severity neu gesetzt, color-Patch ist absent und die
   * aktuelle Farbe ist null, so wird color aus der neuen severity abgeleitet.
   *
   * @param tagExternalId externe UUID des Tags
   * @param in            Patch-Eingabe
   * @throws EntityNotFoundException   wenn Tag nicht gefunden (HTTP 404)
   * @throws EntityValidationException bei leerem Namen, ungueltigem Hex, ungueltigem severityCode
   *     oder widerspruechlicher Kombination (HTTP 400)
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

    // ---- validate-Phase (keine Mutation vor einem moeglichen Throw) ----

    // name
    String newName = null;
    final boolean patchName = in.name().isPresent();
    if (patchName) {
      newName = in.name().get();
      if (newName == null || newName.isBlank()) {
        throw new EntityValidationException("Tag-Name darf nicht leer sein.");
      }
      if (newName.length() > MAX_NAME_LENGTH) {
        throw new EntityValidationException(
            "Tag-Name darf hoechstens " + MAX_NAME_LENGTH + " Zeichen haben.");
      }
    }

    // severityCode
    final boolean patchSeverity = in.severityCode().isPresent();
    Long newSeverityId = null;
    String newSeverityCode = null;
    if (patchSeverity) {
      newSeverityCode = in.severityCode().get();
      if (newSeverityCode == null || newSeverityCode.isBlank()) {
        // severity allein kann man nicht per null leeren (F-P2a bleibt)
        throw new EntityValidationException("severityCode darf nicht leer sein.");
      }
      requireCanonicalSeverity(newSeverityCode);
      newSeverityId = resolveSeverity(newSeverityCode);
    }

    // color
    final boolean patchColor = in.color().isPresent();
    final String rawColor = patchColor ? in.color().get() : null;
    final boolean clearColor = patchColor && (rawColor == null || rawColor.isBlank());
    String normColor = null;
    if (patchColor && !clearColor) {
      normColor = normalizeColor(rawColor);
    }

    // Konflikt: color-Loeschung + severity-Set in derselben Anfrage ist widerspruechlich
    if (clearColor && patchSeverity) {
      throw new EntityValidationException(
          "Widerspruch: color darf nicht geleert und severityCode gleichzeitig gesetzt werden.");
    }

    // ---- mutate-Phase (ab hier kein Throw mehr) ----

    if (patchName) {
      tag.setEntryValue(newName);
    }

    if (patchSeverity) {
      tag.setSeverityId(newSeverityId);
    }

    if (patchColor) {
      if (clearColor) {
        // bewusste Loeschung: Farbe UND severity entfernen (voll neutralisieren)
        tag.setColor(null);
        tag.setSeverityId(null);
      } else {
        tag.setColor(normColor);
      }
    } else if (patchSeverity && tag.getColor() == null) {
      // Write-Invariante (authored writes): severity gesetzt, color-Patch absent, aktuell keine
      // Farbe -> Farbe aus der neuen severity ableiten (kanonisch => nie null). Die Invariante
      // "nie (COLOR null AND SEVERITY_ID non-null)" wird NUR auf Pfaden erzwungen, die color/severity
      // selbst setzen (create, severity-set, color-Loeschung). Eine vorbestehende Alt-Writer-Anomalie
      // (color null + severity gesetzt) wird von einem reinen Namens-Patch BEWUSST NICHT geheilt
      // (AC5: Namens-Patch laesst color unveraendert); sie ist nach dem atomaren V8-Deploy (Backfill
      // + P2-Preflight + Single-Instance) unerreichbar und sonst Sache der owner-gated D1-Detektion
      // vor Phase 3.
      tag.setColor(TagSeverityColors.BY_CODE.get(newSeverityCode));
    }

    TenantContextHolder.runWithTenant(TenantInfo.global(), () -> tagRepository.save(tag));
    log.info("TagCatalogService.update: Tag {} aktualisiert.", tagExternalId);
  }

  // ---- interne Helfer --------------------------------------------------------------------------

  /**
   * Prueft, dass {@code code} einer der fuenf kanonischen Severity-CODEs ist. Ein auflösbarer, aber
   * nicht-kanonischer Code wird mit 400 abgelehnt - noch VOR einem Resolver-Zugriff.
   *
   * @param code der zu pruefende severityCode (nicht null/blank)
   * @throws EntityValidationException wenn {@code code} nicht in {@link TagSeverityColors#BY_CODE}
   */
  private static void requireCanonicalSeverity(final String code) {
    if (!TagSeverityColors.BY_CODE.containsKey(code)) {
      throw new EntityValidationException(
          "Ungueltiger severityCode: '" + code + "' (nicht kanonisch).");
    }
  }

  /**
   * Loest einen kanonischen severityCode auf die interne ID auf (unbekannt -> 400).
   *
   * @param code kanonischer severityCode
   * @return interne Severity-ID
   * @throws EntityValidationException wenn der Resolver keinen eindeutigen Treffer liefert
   */
  private Long resolveSeverity(final String code) {
    try {
      return resolver.resolveTagSeverity(code);
    } catch (DictionaryResolverException e) {
      throw new EntityValidationException("Ungueltiger severityCode: '" + code + "'.");
    }
  }

  /**
   * Normalisiert einen Hex-Farbwert: trimmen, gegen {@link #HEX_COLOR} pruefen, auf Grossbuchstaben
   * heben. Der Aufrufer stellt sicher, dass {@code raw} nicht blank ist.
   *
   * @param raw roher Hex-String (nicht blank)
   * @return normalisierter Hex-String ({@code #RGB}/{@code #RRGGBB}, uppercase)
   * @throws EntityValidationException wenn {@code raw} kein gueltiger Hex-Farbwert ist
   */
  private static String normalizeColor(final String raw) {
    final String trimmed = raw.trim();
    if (!HEX_COLOR.matcher(trimmed).matches()) {
      throw new EntityValidationException(
          "Ungueltige Farbe: '" + raw + "' (erwartet #RGB oder #RRGGBB).");
    }
    return trimmed.toUpperCase(java.util.Locale.ROOT);
  }

  // ---- Mapping ---------------------------------------------------------------------------------

  /**
   * Mappt eine {@link TaskTag}-Entity auf ein {@link TagModel}. Null-safe fuer {@code severityId}:
   * bei {@code null} wird der Reverse-Resolver NICHT aufgerufen und {@code severityCode = null}.
   *
   * @param tag die Tag-Entity
   * @return gemapptes Modell
   */
  private TagModel toModel(final TaskTag tag) {
    final String severityCode =
        tag.getSeverityId() == null ? null : resolver.resolveTagSeverityCode(tag.getSeverityId());
    return new TagModel(tag.getUuid(), tag.getEntryValue(), severityCode, tag.getColor());
  }
}
