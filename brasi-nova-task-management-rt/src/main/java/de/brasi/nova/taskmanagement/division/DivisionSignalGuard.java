package de.brasi.nova.taskmanagement.division;

import io.keel.framework.api.event.NovaEventSemantics;
import io.keel.framework.integration.event.publish.NovaEventHeaders;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Prueft eine eingegangene JMS-Nachricht auf ein vollstaendiges {@code DivisionMembershipChanged}
 * (Intake #190) und liefert daraus das {@link DivisionSignal}.
 *
 * <p>Geprueft werden nur die JMS-Properties nach {@code NovaEventStandardHeaders} (Vertrag P4
 * §2/§4): {@code eventName} = {@value #EVENT_NAME}; {@code novaEventId}, {@code novaTenantId} und
 * {@code aggregateId} als UUID; {@code aggregateType} = {@value #AGGREGATE_TYPE} und {@code
 * semantics} = {@code FACT}. Der Nachrichtentyp ist gleich: application sendet ueber den
 * KSF-Transport ohne JSON-Konverter eine {@code ObjectMessage}, ebenso zulaessig sind {@code
 * TextMessage} oder {@code BytesMessage}. Der Body wird nie gelesen oder deserialisiert.
 *
 * <p>Jeder Verstoss ergibt {@link Optional#empty()} mit WARN (Grund, Nachrichtenklasse und die
 * Header-IDs, nie der Body): Die Nachricht wird verworfen statt wiederholt zugestellt. Das Profil
 * ({@code brasiProfile}) prueft der Listener vorher mit dem KSF-{@code NovaEventProfileGuard}.
 */
@Component
public class DivisionSignalGuard {

  /** Erwarteter Ereignisname. */
  static final String EVENT_NAME = "DivisionMembershipChanged";

  /** Erwarteter Aggregattyp. */
  static final String AGGREGATE_TYPE = "Division";

  /** Erwartete Semantik. */
  static final String SEMANTICS = NovaEventSemantics.FACT.name();

  private static final Logger log = LoggerFactory.getLogger(DivisionSignalGuard.class);

  /**
   * Liest und prueft die JMS-Properties der Nachricht; den Body liest der Guard nicht.
   *
   * @param message eingegangene Nachricht beliebigen Typs
   * @return das Signal oder leer, wenn die Nachricht kein gueltiges Abteilungs-Ereignis ist
   * @throws JMSException wenn der JMS-Provider die Properties nicht liefern kann
   */
  public Optional<DivisionSignal> parse(final Message message) throws JMSException {
    final String eventIdHeader = message.getStringProperty(NovaEventHeaders.EVENT_ID);
    final String tenantHeader = message.getStringProperty(NovaEventHeaders.TENANT_ID);
    final String aggregateHeader = message.getStringProperty(NovaEventHeaders.AGGREGATE_ID);
    final Context context =
        new Context(
            message.getClass().getSimpleName(), eventIdHeader, tenantHeader, aggregateHeader);

    final String eventName = message.getStringProperty(NovaEventHeaders.EVENT_NAME);
    if (!EVENT_NAME.equals(eventName)) {
      return reject("eventName fehlt oder ist nicht " + EVENT_NAME, context);
    }
    final UUID eventId = uuid(eventIdHeader);
    final UUID tenantUuid = uuid(tenantHeader);
    final UUID divisionRef = uuid(aggregateHeader);
    if (eventId == null || tenantUuid == null || divisionRef == null) {
      return reject("novaEventId, novaTenantId oder aggregateId fehlt oder ist keine UUID", context);
    }
    if (!AGGREGATE_TYPE.equals(message.getStringProperty(NovaEventHeaders.AGGREGATE_TYPE))) {
      return reject("aggregateType fehlt oder ist nicht " + AGGREGATE_TYPE, context);
    }
    if (!SEMANTICS.equals(message.getStringProperty(NovaEventHeaders.SEMANTICS))) {
      return reject("semantics fehlt oder ist nicht " + SEMANTICS, context);
    }
    return Optional.of(new DivisionSignal(eventId, tenantUuid, divisionRef));
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

  private static Optional<DivisionSignal> reject(final String reason, final Context context) {
    log.warn(
        "Abteilungs-Ereignis verworfen: {} (Nachricht={}, novaEventId={}, novaTenantId={},"
            + " aggregateId={}).",
        reason,
        context.messageType(),
        context.eventId(),
        context.tenantId(),
        context.aggregateId());
    return Optional.empty();
  }

  /** Nachrichtenklasse und Header-IDs fuer das Log, ungeprueft. */
  private record Context(String messageType, String eventId, String tenantId, String aggregateId) {}
}
