package de.brasi.nova.taskmanagement.division;

import de.brasi.nova.taskmanagement.division.model.DivisionMembersPayload;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Ruft die Mitglieder einer Abteilung (Teilbaum) bei application ab, als aktuellen Stand ({@code
 * self}) oder als Stand einer Revision ({@code snapshot}).
 *
 * <p>Die URL entsteht aus der Basis-URL der Konfiguration und den geprueften Mandanten- und
 * Abteilungs-UUIDs, nie aus Links eines Ereignisses. Statusmatrix: 200 {@link
 * DivisionFetchResult.Members}, 404 {@link DivisionFetchResult.NotFound}, beim Snapshot 410 {@link
 * DivisionFetchResult.Gone} und 400 {@link DivisionFetchResult.BadRevision}; alles andere (401,
 * 403, 5xx, Zeitueberschreitung, I/O) und eine Antwort fuer eine andere Abteilung sind eine {@link
 * DivisionFetchException}.
 */
@Component
public class DivisionMembersClient {

  private static final String EMPLOYEES_PATH =
      "/api/v1/application/{tenantId}/masterdata/divisions/{divisionId}/employees";

  private final RestTemplate restTemplate;
  private final DivisionSyncProperties properties;

  /**
   * Konstruktor-Injektion.
   *
   * @param restTemplate {@code divisionSyncRestTemplate} mit Bearer-Interceptor
   * @param properties Konfiguration des Abteilungs-Abgleichs
   */
  public DivisionMembersClient(
      @Qualifier("divisionSyncRestTemplate") final RestTemplate restTemplate,
      final DivisionSyncProperties properties) {
    this.restTemplate = restTemplate;
    this.properties = properties;
  }

  /**
   * Laedt den aktuellen Stand der Abteilung samt Unterabteilungen.
   *
   * @param tenantUuid Mandanten-UUID aus dem geprueften Ereignis
   * @param divisionRef Abteilungs-UUID aus dem geprueften Ereignis
   * @return {@link DivisionFetchResult.Members} oder {@link DivisionFetchResult.NotFound}
   * @throws DivisionFetchException bei technischem Fehler oder fremder Abteilung in der Antwort
   */
  public DivisionFetchResult fetchCurrent(final UUID tenantUuid, final UUID divisionRef) {
    final URI uri =
        baseUri()
            .path(EMPLOYEES_PATH)
            .queryParam("includeSubDivisions", true)
            .buildAndExpand(tenantUuid, divisionRef)
            .encode()
            .toUri();
    return fetch(uri, divisionRef, false);
  }

  /**
   * Laedt den Stand der Abteilung zu einer Revision.
   *
   * @param tenantUuid Mandanten-UUID aus dem geprueften Ereignis
   * @param divisionRef Abteilungs-UUID aus dem geprueften Ereignis
   * @param revision Revision aus dem geprueften Ereignis
   * @return {@link DivisionFetchResult.Members}, {@link DivisionFetchResult.NotFound}, {@link
   *     DivisionFetchResult.Gone} oder {@link DivisionFetchResult.BadRevision}
   * @throws DivisionFetchException bei technischem Fehler oder fremder Abteilung in der Antwort
   */
  public DivisionFetchResult fetchSnapshot(
      final UUID tenantUuid, final UUID divisionRef, final String revision) {
    final URI uri =
        baseUri()
            .path(EMPLOYEES_PATH + "/snapshots/{revision}")
            .buildAndExpand(tenantUuid, divisionRef, revision)
            .encode()
            .toUri();
    return fetch(uri, divisionRef, true);
  }

  private UriComponentsBuilder baseUri() {
    final String baseUrl = properties.applicationBaseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new DivisionFetchException(
          "Abruf nicht möglich: " + DivisionSyncProperties.PREFIX + ".application-base-url fehlt.");
    }
    return UriComponentsBuilder.fromUriString(baseUrl);
  }

  private DivisionFetchResult fetch(final URI uri, final UUID divisionRef, final boolean snapshot) {
    final DivisionMembersPayload payload;
    try {
      payload =
          restTemplate
              .exchange(
                  RequestEntity.get(uri).accept(MediaType.APPLICATION_JSON).build(),
                  DivisionMembersPayload.class)
              .getBody();
    } catch (final RestClientResponseException e) {
      return mapStatus(e.getStatusCode(), snapshot, divisionRef, e);
    } catch (final RestClientException e) {
      throw new DivisionFetchException(
          "Abruf der Mitglieder von Abteilung "
              + divisionRef
              + " fehlgeschlagen (Verbindung oder Antwort).",
          e);
    }
    if (payload == null) {
      throw new DivisionFetchException(
          "Abruf der Mitglieder von Abteilung " + divisionRef + ": Antwort ohne Inhalt.");
    }
    if (!divisionRef.equals(payload.divisionId())) {
      throw new DivisionFetchException(
          "Abruf der Mitglieder von Abteilung "
              + divisionRef
              + ": Antwort nennt Abteilung "
              + payload.divisionId()
              + ".");
    }
    if (payload.revision() == null || payload.revision().isBlank()) {
      throw new DivisionFetchException(
          "Abruf der Mitglieder von Abteilung " + divisionRef + ": Antwort ohne revision.");
    }
    return new DivisionFetchResult.Members(payload);
  }

  private static DivisionFetchResult mapStatus(
      final HttpStatusCode status,
      final boolean snapshot,
      final UUID divisionRef,
      final RestClientResponseException cause) {
    if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
      return new DivisionFetchResult.NotFound();
    }
    if (snapshot && status.isSameCodeAs(HttpStatus.GONE)) {
      return new DivisionFetchResult.Gone();
    }
    if (snapshot && status.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
      return new DivisionFetchResult.BadRevision();
    }
    throw new DivisionFetchException(
        "Abruf der Mitglieder von Abteilung "
            + divisionRef
            + " fehlgeschlagen: HTTP "
            + status.value(),
        cause);
  }
}
