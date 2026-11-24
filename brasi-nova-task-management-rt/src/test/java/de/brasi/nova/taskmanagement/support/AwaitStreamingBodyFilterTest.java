package de.brasi.nova.taskmanagement.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;

/**
 * Deterministischer Regressionstest fuer {@link AwaitStreamingBodyFilter} (Wackler i152).
 *
 * <p>Die {@code fetch}-Aufrufe der ITs bestehen auch ohne den Filter, solange der Wettlauf nicht
 * auftritt. Hier startet die Filterkette eine Async-Verarbeitung, deren Ergebnis erst abgelegt
 * wird, nachdem der Request-Thread im Filter wartet. Ohne Wartefilter kehrt {@code doFilter} vor
 * der Ablage zurueck und der Test scheitert unabhaengig vom Timing.
 */
class AwaitStreamingBodyFilterTest {

  private static final String URI = "/api/v1/tasks/7/attachments/9/content";
  private static final long SIGNAL_TIMEOUT_SECONDS = 5;

  private final AwaitStreamingBodyFilter filter = new AwaitStreamingBodyFilter();
  private final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("await-filter-");
  private final CountDownLatch bodyStarted = new CountDownLatch(1);
  private final CountDownLatch releaseBody = new CountDownLatch(1);
  private final AtomicBoolean releasedWhileRequestThreadWaited = new AtomicBoolean();

  @AfterEach
  void releaseAsyncBody() {
    releaseBody.countDown();
    executor.close();
  }

  @Test
  void returnsOnlyAfterAsyncResultIsStored() throws Exception {
    final MockHttpServletRequest request = request();
    final Thread requestThread = Thread.currentThread();
    final Thread releaser =
        new Thread(() -> releaseWhenWaiting(requestThread), "await-filter-releaser");
    releaser.start();

    filter.doFilter(request, new MockHttpServletResponse(), startAsyncBody(5_000, this::deliver));

    final WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
    assertThat(asyncManager.hasConcurrentResult())
        .describedAs("Async-Ergebnis bei Rueckkehr aus dem Filter abgelegt")
        .isTrue();
    assertThat(asyncManager.getConcurrentResult()).isEqualTo("ausgeliefert");
    releaser.join(TimeUnit.SECONDS.toMillis(SIGNAL_TIMEOUT_SECONDS));
    assertThat(releasedWhileRequestThreadWaited).isTrue();
  }

  @Test
  void failsAtAsyncTimeoutNamingMethodAndUri() {
    final MockHttpServletRequest request = request();
    final FilterChain stalledChain = startAsyncBody(100, this::deliver);

    assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), stalledChain))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GET " + URI)
        .hasMessageContaining("PT0.1S");
    assertThat(WebAsyncUtils.getAsyncManager(request).hasConcurrentResult()).isFalse();
  }

  @Test
  void unboundedAsyncTimeoutFailsNamingMethodAndUri() {
    final MockHttpServletRequest request = request();
    final FilterChain endlessChain = startAsyncBody(0, this::deliver);

    assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), endlessChain))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nicht begrenzt")
        .hasMessageContaining("GET " + URI);
  }

  @Test
  void synchronousRequestPassesThrough() throws Exception {
    final MockHttpServletRequest request = request();
    final AtomicBoolean chainCalled = new AtomicBoolean();

    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> chainCalled.set(true));

    assertThat(chainCalled).isTrue();
    assertThat(request.isAsyncStarted()).isFalse();
  }

  private static MockHttpServletRequest request() {
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", URI);
    request.setAsyncSupported(true);
    return request;
  }

  /** Filterkette, die wie eine Auslieferung per {@code StreamingResponseBody} Async startet. */
  private FilterChain startAsyncBody(final long timeoutMillis, final Callable<String> body) {
    return (request, response) -> {
      final StandardServletAsyncWebRequest asyncRequest =
          new StandardServletAsyncWebRequest(
              (HttpServletRequest) request, (HttpServletResponse) response);
      asyncRequest.setTimeout(timeoutMillis);
      final WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
      asyncManager.setTaskExecutor(executor);
      asyncManager.setAsyncWebRequest(asyncRequest);
      try {
        asyncManager.startCallableProcessing(body);
      } catch (Exception failure) {
        throw new ServletException(failure);
      }
    };
  }

  /** Async-Auslieferung: haelt an, bis der Test sie freigibt. */
  private String deliver() throws InterruptedException {
    bodyStarted.countDown();
    releaseBody.await();
    return "ausgeliefert";
  }

  /** Gibt die Auslieferung erst frei, wenn der Request-Thread nach der Kette im Filter wartet. */
  private void releaseWhenWaiting(final Thread requestThread) {
    try {
      if (!bodyStarted.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return;
    }
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SIGNAL_TIMEOUT_SECONDS);
    while (requestThread.getState() != Thread.State.TIMED_WAITING) {
      if (System.nanoTime() - deadline > 0) {
        return;
      }
      Thread.onSpinWait();
    }
    releasedWhileRequestThreadWaited.set(true);
    releaseBody.countDown();
  }
}
