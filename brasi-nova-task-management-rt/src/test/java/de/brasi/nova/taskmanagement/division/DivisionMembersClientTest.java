package de.brasi.nova.taskmanagement.division;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.brasi.nova.taskmanagement.division.DivisionFetchResult.BadRevision;
import de.brasi.nova.taskmanagement.division.DivisionFetchResult.Gone;
import de.brasi.nova.taskmanagement.division.DivisionFetchResult.Members;
import de.brasi.nova.taskmanagement.division.DivisionFetchResult.NotFound;
import de.brasi.nova.taskmanagement.division.model.DivisionMemberPayload;
import de.brasi.nova.taskmanagement.division.model.DivisionMembersPayload;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Prueft {@link DivisionMembersClient} (Intake #190, Task 2) gegen einen {@link
 * MockRestServiceServer}: URL-Aufbau aus der Basis-URL, Bearer-Header ueber den Interceptor des
 * {@code divisionSyncRestTemplate} aus {@link DivisionHttpConfig}, Statusmatrix (200, 404, 410,
 * 400) und technische Fehler als {@link DivisionFetchException}.
 *
 * <p>Angenommene Signaturen: {@code
 * DivisionHttpConfig#divisionSyncRestTemplate(RestTemplateBuilder, DivisionSyncProperties,
 * DivisionSyncTokenProvider)} und {@code new DivisionMembersClient(RestTemplate,
 * DivisionSyncProperties)}.
 */
class DivisionMembersClientTest {

  private static final String BASE_URL = "https://application.example.test";
  private static final String ACCESS_TOKEN = "s2s-test-token";
  private static final String REV = "a".repeat(64);

  private static final UUID TENANT = UUID.fromString("00000056-0000-0056-0000-000000000561");
  private static final UUID DIVISION = UUID.fromString("00000056-0000-0056-dddd-000000000001");
  private static final UUID EMPLOYEE_A = UUID.fromString("00000056-0000-0056-eeee-00000000000a");
  private static final UUID EMPLOYEE_B = UUID.fromString("00000056-0000-0056-eeee-00000000000b");

  private MockRestServiceServer server;
  private DivisionMembersClient client;

  @BeforeEach
  void setUp() {
    final DivisionSyncProperties properties = properties();
    final DivisionSyncTokenProvider tokenProvider = mock(DivisionSyncTokenProvider.class);
    when(tokenProvider.accessToken()).thenReturn(ACCESS_TOKEN);
    final RestTemplate restTemplate =
        new DivisionHttpConfig()
            .divisionSyncRestTemplate(new RestTemplateBuilder(), properties, tokenProvider);
    server = MockRestServiceServer.bindTo(restTemplate).build();
    client = new DivisionMembersClient(restTemplate, properties);
  }

  @Test
  void i190_current_200_maps_members() {
    server
        .expect(once(), requestTo(currentUrl(DIVISION)))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
        .andRespond(withSuccess(membersJson(DIVISION, ""), MediaType.APPLICATION_JSON));

    final DivisionFetchResult result = client.fetchCurrent(TENANT, DIVISION);

    assertThat(result).isInstanceOf(Members.class);
    final DivisionMembersPayload payload = ((Members) result).payload();
    assertThat(payload.divisionId()).isEqualTo(DIVISION);
    assertThat(payload.revision()).isEqualTo(REV);
    assertThat(payload.divisionName()).isNull();
    assertThat(payload.members())
        .containsExactly(
            new DivisionMemberPayload(EMPLOYEE_A, "Anna Alpha", "Anna", "Alpha", "aalpha", true),
            new DivisionMemberPayload(EMPLOYEE_B, "Bernd Beta", "Bernd", "Beta", "bbeta", false));
    server.verify();
  }

  @Test
  void i190_current_404_is_not_found() {
    server
        .expect(once(), requestTo(currentUrl(DIVISION)))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(client.fetchCurrent(TENANT, DIVISION)).isInstanceOf(NotFound.class);
    server.verify();
  }

  @Test
  void i190_snapshot_410_is_gone() {
    server
        .expect(once(), requestTo(snapshotUrl(DIVISION, REV)))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
        .andRespond(withStatus(HttpStatus.GONE));

    assertThat(client.fetchSnapshot(TENANT, DIVISION, REV)).isInstanceOf(Gone.class);
    server.verify();
  }

  @Test
  void i190_snapshot_400_is_bad_revision() {
    server
        .expect(once(), requestTo(snapshotUrl(DIVISION, REV)))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST));

    assertThat(client.fetchSnapshot(TENANT, DIVISION, REV)).isInstanceOf(BadRevision.class);
    server.verify();
  }

  @Test
  void i190_403_is_technical_failure() {
    server
        .expect(once(), requestTo(currentUrl(DIVISION)))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> client.fetchCurrent(TENANT, DIVISION))
        .isInstanceOf(DivisionFetchException.class)
        .hasMessageContaining("403")
        .hasMessageNotContaining(ACCESS_TOKEN);
    server.verify();
  }

  @Test
  void i190_division_id_mismatch_is_failure() {
    final UUID other = UUID.fromString("00000056-0000-0056-dddd-000000000099");
    server
        .expect(once(), requestTo(currentUrl(DIVISION)))
        .andRespond(withSuccess(membersJson(other, ""), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.fetchCurrent(TENANT, DIVISION))
        .isInstanceOf(DivisionFetchException.class);
    server.verify();
  }

  @Test
  void i190_missing_members_is_failure() {
    final String withoutField = "{\"divisionId\":\"" + DIVISION + "\",\"revision\":\"" + REV + "\"}";
    final String nullField =
        "{\"divisionId\":\"" + DIVISION + "\",\"revision\":\"" + REV + "\",\"members\":null}";
    server
        .expect(once(), requestTo(currentUrl(DIVISION)))
        .andRespond(withSuccess(withoutField, MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(currentUrl(DIVISION)))
        .andRespond(withSuccess(nullField, MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(snapshotUrl(DIVISION, REV)))
        .andRespond(withSuccess(withoutField, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.fetchCurrent(TENANT, DIVISION))
        .isInstanceOf(DivisionFetchException.class)
        .hasMessageContaining("ohne members")
        .hasMessageNotContaining(ACCESS_TOKEN);
    assertThatThrownBy(() -> client.fetchCurrent(TENANT, DIVISION))
        .isInstanceOf(DivisionFetchException.class)
        .hasMessageContaining("ohne members")
        .hasMessageNotContaining(ACCESS_TOKEN);
    assertThatThrownBy(() -> client.fetchSnapshot(TENANT, DIVISION, REV))
        .isInstanceOf(DivisionFetchException.class)
        .hasMessageContaining("ohne members")
        .hasMessageNotContaining(ACCESS_TOKEN);
    server.verify();
  }

  @Test
  void i190_null_member_entry_is_failure() {
    final String json =
        "{\"divisionId\":\""
            + DIVISION
            + "\",\"revision\":\""
            + REV
            + "\",\"members\":[null]}";
    server
        .expect(once(), requestTo(currentUrl(DIVISION)))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.fetchCurrent(TENANT, DIVISION))
        .isInstanceOf(DivisionFetchException.class)
        .hasMessageContaining("leerem Eintrag")
        .hasMessageNotContaining(ACCESS_TOKEN);
    server.verify();
  }

  @Test
  void i190_empty_members_is_valid() {
    final String json =
        "{\"divisionId\":\""
            + DIVISION
            + "\",\"revision\":\""
            + REV
            + "\",\"members\":[]}";
    server
        .expect(once(), requestTo(currentUrl(DIVISION)))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

    final DivisionFetchResult result = client.fetchCurrent(TENANT, DIVISION);

    assertThat(result).isInstanceOf(Members.class);
    final DivisionMembersPayload payload = ((Members) result).payload();
    assertThat(payload.members()).isNotNull().isEmpty();
    assertThat(payload.revision()).isEqualTo(REV);
    server.verify();
  }

  @Test
  void i190_unknown_fields_ignored_and_division_name_read() {
    server
        .expect(once(), requestTo(snapshotUrl(DIVISION, REV)))
        .andRespond(
            withSuccess(
                membersJson(DIVISION, "\"divisionName\":\"Vertrieb\",\"futureField\":{\"x\":1},"),
                MediaType.APPLICATION_JSON));

    final DivisionFetchResult result = client.fetchSnapshot(TENANT, DIVISION, REV);

    assertThat(result).isInstanceOf(Members.class);
    final DivisionMembersPayload payload = ((Members) result).payload();
    assertThat(payload.divisionName()).isEqualTo("Vertrieb");
    assertThat(payload.members()).hasSize(2);
    server.verify();
  }

  // ---- Hilfsmethoden --------------------------------------------------------------------------

  private static String currentUrl(final UUID division) {
    return BASE_URL
        + "/api/v1/application/"
        + TENANT
        + "/masterdata/divisions/"
        + division
        + "/employees?includeSubDivisions=true";
  }

  private static String snapshotUrl(final UUID division, final String revision) {
    return BASE_URL
        + "/api/v1/application/"
        + TENANT
        + "/masterdata/divisions/"
        + division
        + "/employees/snapshots/"
        + revision;
  }

  /**
   * Antwort von application; {@code extraFields} wird vor {@code divisionId} eingefuegt (mit
   * abschliessendem Komma). Ist es nicht leer, traegt das zweite Mitglied zusaetzlich ein
   * unbekanntes Feld.
   */
  private static String membersJson(final UUID divisionId, final String extraFields) {
    final String memberExtra = extraFields.isEmpty() ? "" : ",\"memberSince\":\"2026-01-01\"";
    return "{"
        + extraFields
        + "\"divisionId\":\""
        + divisionId
        + "\",\"revision\":\""
        + REV
        + "\",\"members\":["
        + "{\"employeeId\":\""
        + EMPLOYEE_A
        + "\",\"displayName\":\"Anna Alpha\",\"firstName\":\"Anna\",\"lastName\":\"Alpha\","
        + "\"loginName\":\"aalpha\",\"coreMembership\":true},"
        + "{\"employeeId\":\""
        + EMPLOYEE_B
        + "\",\"displayName\":\"Bernd Beta\",\"firstName\":\"Bernd\",\"lastName\":\"Beta\","
        + "\"loginName\":\"bbeta\",\"coreMembership\":false"
        + memberExtra
        + "}]}";
  }

  private static DivisionSyncProperties properties() {
    return new DivisionSyncProperties(
        true,
        "jms.topic.brasi-nova.dev-ui.divisions.DivisionMembershipChanged",
        "task-management.division-membership",
        BASE_URL,
        new DivisionSyncProperties.OAuth2(
            "https://identity.example.test/oauth2/token",
            "tm-test-client",
            "test-secret-value",
            "masterdata.sync.read"),
        5,
        new DivisionSyncProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(15)),
        new DivisionSyncProperties.Resync("0 30 3 * * *"),
        Duration.ofDays(30));
  }
}
