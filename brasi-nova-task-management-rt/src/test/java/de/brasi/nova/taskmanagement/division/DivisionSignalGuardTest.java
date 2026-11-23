package de.brasi.nova.taskmanagement.division;

import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.body;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.headers;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.snapshotLink;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.stubHeaders;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.textMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prueft {@link DivisionSignalGuard} (Intake #190, Task 4) mit gemockten JMS-Nachrichten: nur ein
 * vollstaendiges {@code DivisionMembershipChanged} mit passendem {@code eventName} in Header und
 * Body, uebereinstimmenden Header- und Body-IDs und einer revision aus 64 Kleinbuchstaben-Hex im
 * Snapshot-Link wird zum {@link DivisionSignal}; jeder Verstoss ergibt {@code Optional.empty()}.
 *
 * <p>Angenommene Signaturen: {@code new DivisionSignalGuard(ObjectMapper)}, {@code
 * Optional<DivisionSignal> parse(Message) throws JMSException}; der Guard liest Header ueber {@code
 * getStringProperty} (die Mocks beantworten auch {@code getObjectProperty} und {@code
 * propertyExists}) und den Body ueber {@link TextMessage#getText()}.
 */
class DivisionSignalGuardTest {

  private static final UUID EVT = UUID.fromString("00000056-0000-0056-eeee-000000000190");
  private static final UUID TEN = UUID.fromString("00000056-0000-0056-0000-000000000561");
  private static final UUID DIV = UUID.fromString("00000056-0000-0056-dddd-000000000001");
  private static final UUID OTHER_TENANT = UUID.fromString("00000056-0000-0056-0000-000000000562");
  private static final String REV = "0123456789abcdef".repeat(4);

  private final DivisionSignalGuard guard = new DivisionSignalGuard(new ObjectMapper());

  @Test
  @DisplayName("gueltiges Ereignis ergibt das Signal mit IDs und revision aus dem Snapshot-Link")
  void i190_valid_signal_parsed() throws JMSException {
    final TextMessage msg = textMessage(headers(EVT, TEN, DIV), validBody());

    assertThat(guard.parse(msg)).contains(new DivisionSignal(EVT, TEN, DIV, REV));
  }

  @Test
  @DisplayName("anderer eventName wird verworfen")
  void i190_wrong_event_name_rejected() throws JMSException {
    final Map<String, String> headers = headers(EVT, TEN, DIV);
    headers.put("eventName", "Other");

    assertThat(guard.parse(textMessage(headers, validBody()))).isEmpty();
  }

  @Test
  @DisplayName("eventName im Body weicht vom Header ab: wird verworfen")
  void i190_body_event_name_mismatch_rejected() throws JMSException, IOException {
    final String body = withBodyEventName(validBody(), "Other");

    assertThat(guard.parse(textMessage(headers(EVT, TEN, DIV), body))).isEmpty();
  }

  @Test
  @DisplayName("eventName fehlt im Body (Pflichtfeld des KSF-Envelope): wird verworfen")
  void i190_body_event_name_missing_rejected() throws JMSException, IOException {
    final String body = withBodyEventName(validBody(), null);

    assertThat(guard.parse(textMessage(headers(EVT, TEN, DIV), body))).isEmpty();
  }

  @Test
  @DisplayName("Mandant im Header und im Body verschieden wird verworfen")
  void i190_header_body_tenant_mismatch_rejected() throws JMSException {
    final String body = body(EVT, OTHER_TENANT, DIV, snapshotLink(OTHER_TENANT, DIV, REV));

    assertThat(guard.parse(textMessage(headers(EVT, TEN, DIV), body))).isEmpty();
  }

  @Test
  @DisplayName("fehlende Ereignis-ID im Header oder im Body wird verworfen")
  void i190_missing_event_id_rejected() throws JMSException {
    final Map<String, String> withoutHeader = headers(EVT, TEN, DIV);
    withoutHeader.remove("novaEventId");
    final String bodyWithoutEventId = body(null, TEN, DIV, snapshotLink(TEN, DIV, REV));

    assertThat(guard.parse(textMessage(withoutHeader, validBody()))).isEmpty();
    assertThat(guard.parse(textMessage(headers(EVT, TEN, DIV), bodyWithoutEventId))).isEmpty();
  }

  @Test
  @DisplayName("Snapshot-Link ohne gueltige revision (Grossbuchstaben, 63 Zeichen) wird verworfen")
  void i190_snapshot_link_without_valid_revision_rejected() throws JMSException {
    final String upperCase = body(EVT, TEN, DIV, snapshotLink(TEN, DIV, "ABC"));
    final String tooShort = body(EVT, TEN, DIV, snapshotLink(TEN, DIV, REV.substring(1)));

    assertThat(REV.substring(1)).hasSize(63);
    assertThat(guard.parse(textMessage(headers(EVT, TEN, DIV), upperCase))).isEmpty();
    assertThat(guard.parse(textMessage(headers(EVT, TEN, DIV), tooShort))).isEmpty();
  }

  @Test
  @DisplayName("Nachricht ohne Text (BytesMessage) wird trotz gueltiger Header verworfen")
  void i190_non_text_message_rejected() throws JMSException {
    final BytesMessage msg = mock(BytesMessage.class);
    stubHeaders(msg, headers(EVT, TEN, DIV));

    assertThat(guard.parse(msg)).isEmpty();
  }

  private static String validBody() {
    return body(EVT, TEN, DIV, snapshotLink(TEN, DIV, REV));
  }

  /** Setzt {@code eventName} im Body auf den Wert oder entfernt es bei {@code null}. */
  private static String withBodyEventName(final String body, final String eventName)
      throws IOException {
    final ObjectNode root = (ObjectNode) new ObjectMapper().readTree(body);
    if (eventName == null) {
      root.remove("eventName");
    } else {
      root.put("eventName", eventName);
    }
    return root.toString();
  }
}
