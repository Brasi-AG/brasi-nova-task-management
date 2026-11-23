package de.brasi.nova.taskmanagement.division;

import io.keel.framework.integration.event.consume.NovaEventProfileGuard;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Empfaengt {@code DivisionMembershipChanged} auf der geteilten, dauerhaften Subscription
 * (Intake #190), nur bei {@code task-management.division-sync.enabled=true}.
 *
 * <p>Je Nachricht: {@link NovaEventProfileGuard} (fremdes oder fehlendes {@code brasiProfile}:
 * verwerfen, WARN des Guards), {@link DivisionSignalGuard} (ungueltig: verwerfen mit WARN), dann
 * {@link DivisionSignalProcessor#process} mit dem Zustellversuch aus {@code JMSXDeliveryCount}
 * (fehlend oder unlesbar: 1). Verworfene Nachrichten werden bestaetigt, kein Poison-Loop; eine
 * Ausnahme des Processors rollt die Session zurueck und loest die Redelivery aus.
 */
@Component
@ConditionalOnProperty(
    prefix = DivisionSyncProperties.PREFIX,
    name = "enabled",
    havingValue = "true")
public class DivisionMembershipListener {

  /** JMS-Property mit dem Zustellversuch. */
  static final String DELIVERY_COUNT = "JMSXDeliveryCount";

  private final NovaEventProfileGuard profileGuard;
  private final DivisionSignalGuard signalGuard;
  private final DivisionSignalProcessor processor;

  /**
   * Konstruktor-Injektion.
   *
   * @param profileGuard KSF-Pruefung des {@code brasiProfile}
   * @param signalGuard Pruefung von Header und Body
   * @param processor Verarbeitung des Signals
   */
  public DivisionMembershipListener(
      final NovaEventProfileGuard profileGuard,
      final DivisionSignalGuard signalGuard,
      final DivisionSignalProcessor processor) {
    this.profileGuard = profileGuard;
    this.signalGuard = signalGuard;
    this.processor = processor;
  }

  /**
   * Verarbeitet eine Nachricht des Topics.
   *
   * @param message eingegangene Nachricht
   * @throws JMSException wenn der JMS-Provider Header oder Body nicht liefern kann
   */
  @JmsListener(
      destination = "${task-management.division-sync.destination}",
      subscription = "${task-management.division-sync.subscription-name}",
      containerFactory = "divisionSyncListenerFactory")
  public void onSignal(final Message message) throws JMSException {
    if (!profileGuard.accepts(message)) {
      return;
    }
    final Optional<DivisionSignal> signal = signalGuard.parse(message);
    if (signal.isEmpty()) {
      return;
    }
    processor.process(signal.get(), deliveryCount(message));
  }

  /** Zustellversuch laut Broker; fehlend, unlesbar oder kleiner 1 ergibt 1. */
  static int deliveryCount(final Message message) {
    try {
      if (!message.propertyExists(DELIVERY_COUNT)) {
        return 1;
      }
      return Math.max(1, message.getIntProperty(DELIVERY_COUNT));
    } catch (final JMSException | RuntimeException e) {
      return 1;
    }
  }
}
