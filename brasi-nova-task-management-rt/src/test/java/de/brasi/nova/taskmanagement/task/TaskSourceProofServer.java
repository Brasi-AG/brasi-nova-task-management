package de.brasi.nova.taskmanagement.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lokaler Testserver fuer die Nachweis-Abrufe der Quellen-Adapter (Kontakt, Kunde, Mitarbeiter,
 * Firma). Gemeinsame Hilfe der vier {@code *TaskSourceAdapterTest}; vorher war diese Logik in jede
 * Testklasse kopiert.
 *
 * <p>Der Server antwortet mit einstellbarem Status, Body, Redirect und Verzoegerung. Mit {@link
 * #stallBody(boolean)} sendet er die Header sofort und haelt den Body (optional schon vor dem
 * ersten Byte) bis {@link #releaseStalledBody()} an. Die Latches melden dem Testthread, wie weit
 * der Server dabei gekommen ist.
 *
 * <p>Der Test muss auf diese Ereignisse warten (Wackler tm-adapter-test-flake): Bei Nicht-200 ist
 * der Adapter schon mit den Headern fertig und wirft bei 403/404 sofort. Der Test pruefte dann,
 * bevor der Serverthread den frueheren Zaehler nach dem Flush des ersten Body-Bytes erhoeht hatte.
 * An der Sichtbarkeit lag es nicht, der Zaehler war ein {@code AtomicInteger}; es fehlte das Warten
 * auf das Ereignis.
 */
final class TaskSourceProofServer implements AutoCloseable {

  private static final long SIGNAL_TIMEOUT_SECONDS = 5;
  private static final long STALL_LIMIT_SECONDS = 10;

  private final HttpServer server;
  private final AtomicReference<String> bearer = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final CountDownLatch stalledHeadersSent = new CountDownLatch(1);
  private final CountDownLatch firstBodyByteSent = new CountDownLatch(1);
  private final CountDownLatch releaseStalledBody = new CountDownLatch(1);
  private volatile int status = 200;
  private volatile String body;
  private volatile String redirectLocation;
  private volatile long delayMillis;
  private volatile boolean stallBody;
  private volatile boolean stallBeforeFirstByte;

  private TaskSourceProofServer(final String body) throws IOException {
    this.body = body;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
  }

  /** Startet den Server auf einem freien Port; {@code body} ist die Antwort bis zur Aenderung. */
  static TaskSourceProofServer start(final String body) throws IOException {
    final TaskSourceProofServer proofServer = new TaskSourceProofServer(body);
    proofServer.server.start();
    return proofServer;
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void status(final int status) {
    this.status = status;
  }

  void body(final String body) {
    this.body = body;
  }

  String body() {
    return body;
  }

  void redirectTo(final String location) {
    redirectLocation = location;
  }

  void delayMillis(final long delayMillis) {
    this.delayMillis = delayMillis;
  }

  /**
   * Header sofort senden, Body bis {@link #releaseStalledBody()} anhalten; mit {@code
   * beforeFirstByte} schon vor dem ersten Body-Byte, sonst nach ihm.
   */
  void stallBody(final boolean beforeFirstByte) {
    stallBeforeFirstByte = beforeFirstByte;
    stallBody = true;
  }

  void releaseStalledBody() {
    releaseStalledBody.countDown();
  }

  String bearer() {
    return bearer.get();
  }

  String path() {
    return path.get();
  }

  int requests() {
    return requests.get();
  }

  /**
   * Wartet, bis der Server die Header vor dem angehaltenen Body geflusht hat.
   *
   * <p>Das Latch zaehlt direkt nach dem Header-Flush herunter. Dieser Flush kann nicht am
   * Schliessen durch den Client scheitern, weil der Client die Header fruehestens danach sieht. Auf
   * das erste Body-Byte wird hier bewusst nicht gewartet: Bei 403/404 entscheidet der Adapter an
   * den Headern und schliesst den Fehlerstrom, ein spaeterer Write des Servers kann daran
   * scheitern. Ohne Signal scheitert der Test nach der Frist hart.
   */
  void assertStalledHeadersSent() throws InterruptedException {
    assertThat(stalledHeadersSent.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .describedAs("Header vor angehaltenem Body gesendet")
        .isTrue();
  }

  /**
   * Wartet, bis der Server das erste Body-Byte geflusht hat.
   *
   * <p>Das Latch zaehlt erst nach dem Flush des ersten Body-Bytes herunter; der Client kann schon
   * vorher zurueckkehren (seine Frist laeuft auf dem angehaltenen Body ab). Deshalb wartet der Test
   * auf dieses Ereignis, statt einen Zaehler abzulesen. Ohne Signal scheitert der Test nach der
   * Frist hart.
   */
  void assertFirstBodyByteSent() throws InterruptedException {
    assertThat(firstBodyByteSent.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .describedAs("erstes Body-Byte gesendet")
        .isTrue();
  }

  /** Haelt den angehaltenen Body nicht laenger fest und stoppt den Server. */
  @Override
  public void close() {
    releaseStalledBody.countDown();
    server.stop(0);
  }

  private void respond(final HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
    path.set(exchange.getRequestURI().getPath());
    if (delayMillis > 0) {
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    if (redirectLocation != null) {
      exchange.getResponseHeaders().set("Location", redirectLocation);
    }
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    try {
      if (stallBody) {
        exchange.sendResponseHeaders(status, 0);
        exchange.getResponseBody().flush();
        stalledHeadersSent.countDown();
        if (!stallBeforeFirstByte) {
          exchange.getResponseBody().write(bytes, 0, 1);
          exchange.getResponseBody().flush();
          firstBodyByteSent.countDown();
        }
        try {
          releaseStalledBody.await(STALL_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        if (stallBeforeFirstByte) {
          exchange.getResponseBody().write(bytes);
        } else {
          exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
        }
      } else {
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    } catch (IOException clientClosed) {
      // Der Client hat vor der Antwort geschlossen (Frist abgelaufen oder Fehlerstrom verworfen).
    } finally {
      exchange.close();
    }
  }
}
