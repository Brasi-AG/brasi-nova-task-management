package de.brasi.nova.taskmanagement.division;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.keel.framework.integration.event.publish.NovaEventHeaders;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Prueft eine eingegangene JMS-Nachricht auf ein vollstaendiges {@code DivisionMembershipChanged}
 * (Intake #190) und liefert daraus das {@link DivisionSignal}.
 *
 * <p>Verlangt: Header {@code eventName} = {@value #EVENT_NAME}; Header {@code novaEventId}, {@code
 * novaTenantId} und {@code aggregateId} als UUID; eine {@link TextMessage} mit JSON-Envelope,
 * dessen {@code eventName} ebenfalls {@value #EVENT_NAME} ist (das KSF-Envelope traegt es immer,
 * {@code NovaEventEnvelope} verlangt es nicht-null) und dessen {@code eventId}, {@code tenantId}
 * und {@code aggregateId} den Headern gleichen; im Link {@code links.snapshot} als letztes
 * Pfadsegment eine revision aus 64 Kleinbuchstaben-Hex. Jeder Verstoss ergibt {@link
 * Optional#empty()} mit WARN (Grund und Header-IDs, nie der Body): die Nachricht wird verworfen
 * statt wiederholt zugestellt.
 */
@Component
public class DivisionSignalGuard {

  /** Erwarteter Ereignisname. */
  static final String EVENT_NAME = "DivisionMembershipChanged";

  private static final Pattern REVISION = Pattern.compile("^[0-9a-f]{64}$");

  private static final Logger log = LoggerFactory.getLogger(DivisionSignalGuard.class);

  private final ObjectMapper objectMapper;

  /**
   * Konstruktor-Injektion.
   *
   * @param objectMapper JSON-Leser fuer den Body
   */
  public DivisionSignalGuard(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Liest und prueft die Nachricht.
   *
   * @param message eingegangene Nachricht
   * @return das Signal oder leer, wenn die Nachricht kein gueltiges Abteilungs-Ereignis ist
   * @throws JMSException wenn der JMS-Provider Header oder Body nicht liefern kann
   */
  public Optional<DivisionSignal> parse(final Message message) throws JMSException {
    final String eventIdHeader = message.getStringProperty(NovaEventHeaders.EVENT_ID);
    final String tenantHeader = message.getStringProperty(NovaEventHeaders.TENANT_ID);
    final String aggregateHeader = message.getStringProperty(NovaEventHeaders.AGGREGATE_ID);
    final Ids ids = new Ids(eventIdHeader, tenantHeader, aggregateHeader);

    final String eventName = message.getStringProperty(NovaEventHeaders.EVENT_NAME);
    if (!EVENT_NAME.equals(eventName)) {
      return reject("eventName ist nicht " + EVENT_NAME, ids);
    }
    final UUID eventId = uuid(eventIdHeader);
    final UUID tenantUuid = uuid(tenantHeader);
    final UUID divisionRef = uuid(aggregateHeader);
    if (eventId == null || tenantUuid == null || divisionRef == null) {
      return reject("novaEventId, novaTenantId oder aggregateId fehlt oder ist keine UUID", ids);
    }
    if (!(message instanceof TextMessage textMessage)) {
      return reject("keine TextMessage", ids);
    }
    final String body = textMessage.getText();
    if (body == null || body.isBlank()) {
      return reject("Body fehlt", ids);
    }
    final JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (final JsonProcessingException e) {
      return reject("Body ist kein JSON", ids);
    }
    if (root == null || !root.isObject()) {
      return reject("Body ist kein JSON-Objekt", ids);
    }
    if (!EVENT_NAME.equals(text(root.get("eventName")))) {
      return reject("eventName im Body fehlt oder ist nicht " + EVENT_NAME, ids);
    }
    if (!eventId.equals(uuid(text(root.get("eventId"))))
        || !tenantUuid.equals(uuid(text(root.get("tenantId"))))
        || !divisionRef.equals(uuid(text(root.get("aggregateId"))))) {
      return reject("eventId, tenantId oder aggregateId im Body passt nicht zu den Headern", ids);
    }
    final JsonNode links = root.get("links");
    final String revision = revisionOf(links == null ? null : text(links.get("snapshot")));
    if (revision == null) {
      return reject("Snapshot-Link ohne gueltige revision", ids);
    }
    return Optional.of(new DivisionSignal(eventId, tenantUuid, divisionRef, revision));
  }

  /** Letztes Pfadsegment des Links, wenn es eine revision aus 64 Kleinbuchstaben-Hex ist. */
  private static String revisionOf(final String link) {
    if (link == null || link.isBlank()) {
      return null;
    }
    final String path;
    try {
      path = URI.create(link.trim()).getPath();
    } catch (final IllegalArgumentException e) {
      return null;
    }
    if (path == null) {
      return null;
    }
    final String segment = path.substring(path.lastIndexOf('/') + 1);
    return REVISION.matcher(segment).matches() ? segment : null;
  }

  private static String text(final JsonNode node) {
    return node == null || !node.isTextual() ? null : node.asText();
  }

  private static UUID uuid(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (final IllegalArgumentException e) {
      return null;
    }
  }

  private static Optional<DivisionSignal> reject(final String reason, final Ids ids) {
    log.warn(
        "Abteilungs-Ereignis verworfen: {} (novaEventId={}, novaTenantId={}, aggregateId={}).",
        reason,
        ids.eventId(),
        ids.tenantId(),
        ids.aggregateId());
    return Optional.empty();
  }

  /** Header-IDs fuer das Log, ungeprueft. */
  private record Ids(String eventId, String tenantId, String aggregateId) {}
}
