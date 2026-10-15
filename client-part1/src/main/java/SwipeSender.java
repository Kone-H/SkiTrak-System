import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

// Sends swipes to the server. All sender threads share this one instance,
// so they share one HttpClient and therefore one connection pool.
public class SwipeSender {
  private static final int MAX_RETRIES = 5;
  private static final long BASE_BACKOFF_MS = 50;

  private final String baseUrl;
  private final HttpClient httpClient;

  // Updated by many threads at once, so they must be atomic
  private final AtomicInteger successCount = new AtomicInteger();
  private final AtomicInteger failureCount = new AtomicInteger();

  public SwipeSender(String baseUrl) {
    this.baseUrl = baseUrl;
    // HttpClient is thread-safe and keeps finished connections open for reuse (keep-alive),
    // so later requests skip the TCP handshake
    this.httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)     // Tomcat's port 8080 speaks HTTP/1.1
        .connectTimeout(Duration.ofSeconds(10))   // max wait to establish a TCP connection
        .build();
  }

  public int getSuccessCount() { return successCount.get(); }
  public int getFailureCount() { return failureCount.get(); }

  // Sends one swipe. Retries up to MAX_RETRIES times with exponential backoff;
  // counts it as failed if every attempt fails.
  public void send(SwipeEvent swipe) {
    String url = String.format("%s/skiers/%d/seasons/%d/days/%d/skiers/%d",
        baseUrl, swipe.getResortID(), swipe.getSeasonID(), swipe.getDayID(), swipe.getSkierID());
    String body = String.format("{\"time\":%d,\"liftID\":%d}", swipe.getTime(), swipe.getLiftID());

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10))          // max wait for the response once the request is sent
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    // attempt 0 is the first send; attempts 1..MAX_RETRIES are retries
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        if (attempt > 0) {
          // exponential backoff before each retry: 50, 100, 200, 400, 800 ms
          Thread.sleep(BASE_BACKOFF_MS << (attempt - 1));
        }
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 201) {
          successCount.incrementAndGet();
          return;
        }
        // any other status code: fall through to the next attempt
      } catch (IOException e) {
        // network error (connection refused, timeout, connection dropped): try again
      } catch (InterruptedException e) {
        // this thread was asked to stop, during send() or the backoff sleep: give up
        Thread.currentThread().interrupt();
        break;
      }
    }
    failureCount.incrementAndGet();   // every attempt failed
  }
}