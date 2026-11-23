package de.brasi.nova.taskmanagement.division;

import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.headers;
import static de.brasi.nova.taskmanagement.division.DivisionSignalTestSupport.stubHeaders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.ObjectMessage;
import jakarta.jms.TextMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Prueft {@link DivisionSignalGuard} (Intake #190, Fix {@code tm-division-signal-properties}) mit
 * gemockten JMS-Nachrichten: Nur die JMS-Properties zaehlen ({@code eventName}, {@code
 * novaEventId}, {@code novaTenantId}, {@code aggregateId}, {@code aggregateType}, {@code
 * semantics}), jeder Nachrichtentyp ist zulaessig, und der Body wird nie gelesen.
 *
 * <p>Die Header kommen ueber {@link DivisionSignalTestSupport#headers} aus dem KSF-{@code
 * NovaEventStandardHeaders#assemble}, also genau so, wie application sie setzt.
 */
class DivisionSignalGuardTest {

  private static final UUID EVT = UUID.fromString("00000056-0000-0056-eeee-000000000190");
  private static final UUID TEN = UUID.fromString("00000056-0000-0056-0000-000000000561");
  private static final UUID DIV = UUID.fromString("00000056-0000-0056-dddd-000000000001");

  private final DivisionSignalGuard guard = new DivisionSignalGuard();

  @Test
  @DisplayName("ObjectMessage mit allen Properties wird akzeptiert, ohne den Body zu lesen")
  void i190_object_message_accepted_without_body() throws JMSException {
    final ObjectMessage msg = mock(ObjectMessage.class);
    stubHeaders(msg, headers(EVT, TEN, DIV));

    assertThat(guard.parse(msg)).contains(new DivisionSignal(EVT, TEN, DIV));
    verify(msg, never()).getObject();
    verify(msg, never()).getBody(any());
    assertOnlyPropertiesRead(msg);
  }

  @Test
  @DisplayName("TextMessage wird akzeptiert, ohne den Text zu lesen")
  void i190_text_message_accepted_without_body() throws JMSException {
    final TextMessage msg = mock(TextMessage.class);
    stubHeaders(msg, headers(EVT, TEN, DIV));

    assertThat(guard.parse(msg)).contains(new DivisionSignal(EVT, TEN, DIV));
    verify(msg, never()).getText();
    verify(msg, never()).getBody(any());
    assertOnlyPropertiesRead(msg);
  }

  @Test
  @DisplayName("BytesMessage wird akzeptiert, ohne Bytes zu lesen")
  void i190_bytes_message_accepted_without_body() throws JMSException {
    final BytesMessage msg = mock(BytesMessage.class);
    stubHeaders(msg, headers(EVT, TEN, DIV));

    assertThat(guard.parse(msg)).contains(new DivisionSignal(EVT, TEN, DIV));
    verify(msg, never()).readBytes(any(byte[].class));
    verify(msg, never()).getBodyLength();
    verify(msg, never()).getBody(any());
    assertOnlyPropertiesRead(msg);
  }

  @ParameterizedTest(name = "{0} fehlt")
  @ValueSource(
      strings = {
        "eventName",
        "novaEventId",
        "novaTenantId",
        "aggregateId",
        "aggregateType",
        "semantics"
      })
  @DisplayName("fehlende Pflicht-Property: verworfen, Body unberuehrt")
  void i190_missing_property_rejected(final String property) throws JMSException {
    final Map<String, String> headers = headers(EVT, TEN, DIV);
    assertThat(headers).containsKey(property);
    headers.remove(property);
    final ObjectMessage msg = mock(ObjectMessage.class);
    stubHeaders(msg, headers);

    assertThat(guard.parse(msg)).isEmpty();
    verify(msg, never()).getObject();
    assertOnlyPropertiesRead(msg);
  }

  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource({
    "eventName, Other",
    "eventName, divisionmembershipchanged",
    "novaEventId, keine-uuid",
    "novaTenantId, keine-uuid",
    "aggregateId, keine-uuid",
    "novaEventId, ' '",
    "aggregateType, Company",
    "aggregateType, division",
    "semantics, STATE",
    "semantics, fact"
  })
  @DisplayName("falsche Pflicht-Property: verworfen, Body unberuehrt")
  void i190_wrong_property_rejected(final String property, final String value) throws JMSException {
    final Map<String, String> headers = headers(EVT, TEN, DIV);
    headers.put(property, value);
    final ObjectMessage msg = mock(ObjectMessage.class);
    stubHeaders(msg, headers);

    assertThat(guard.parse(msg)).isEmpty();
    verify(msg, never()).getObject();
    assertOnlyPropertiesRead(msg);
  }

  @Test
  @DisplayName("KSF-Header tragen aggregateType Division und semantics FACT (Vertrag §2)")
  void i190_ksf_headers_carry_contract_values() {
    final Map<String, String> headers = headers(EVT, TEN, DIV);

    assertThat(headers)
        .containsEntry("eventName", "DivisionMembershipChanged")
        .containsEntry("novaEventId", EVT.toString())
        .containsEntry("novaTenantId", TEN.toString())
        .containsEntry("aggregateId", DIV.toString())
        .containsEntry("aggregateType", "Division")
        .containsEntry("semantics", "FACT")
        .containsKeys("aggregateVersion", "aggregateRefId", "brasiMessageVersion")
        .containsKey("brasiSourceService");
  }

  @Test
  @DisplayName("Ablehnung nennt Grund und Nachrichtenklasse im WARN")
  void i190_rejection_log_names_reason_and_message_class() throws JMSException {
    final Map<String, String> headers = headers(EVT, TEN, DIV);
    headers.put("semantics", "STATE");
    final ObjectMessage msg = mock(ObjectMessage.class);
    stubHeaders(msg, headers);
    final Logger logger = (Logger) LoggerFactory.getLogger(DivisionSignalGuard.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThat(guard.parse(msg)).isEmpty();
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list).hasSize(1);
    final String line = appender.list.get(0).getFormattedMessage();
    assertThat(line)
        .contains("semantics fehlt oder ist nicht FACT")
        .contains("Nachricht=" + msg.getClass().getSimpleName())
        .contains("ObjectMessage")
        .contains(EVT.toString());
  }

  /** Jeder Zugriff auf die Nachricht war ein Property-Zugriff, keiner auf den Body. */
  private static void assertOnlyPropertiesRead(final Message msg) {
    final List<String> methods =
        mockingDetails(msg).getInvocations().stream()
            .map(invocation -> invocation.getMethod().getName())
            .toList();
    assertThat(methods)
        .isNotEmpty()
        .allMatch(
            name -> name.endsWith("Property") || "propertyExists".equals(name),
            "nur Property-Zugriffe");
  }
}
