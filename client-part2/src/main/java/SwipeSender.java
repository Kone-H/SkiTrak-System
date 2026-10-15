import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
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

  // Part 2: every sender thread adds one SwipeRecord per swipe to this shared queue
  // (Lab 2 Exercise 3, strategy 3: shared collection, single writer at the end)
  private final Queue<SwipeRecord> records;

  public SwipeSender(String baseUrl, Queue<SwipeRecord> records) {
    this.baseUrl = baseUrl;
    this.records = records;
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

    // Part 2: remember the last attempt, so each swipe produces exactly one record
    long lastTimestamp = 0;
    long lastLatencyMs = 0;
    int lastStatusCode = 0;   // stays 0 if no HTTP response was ever received

    // attempt 0 is the first send; attempts 1..MAX_RETRIES are retries
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        if (attempt > 0) {
          // exponential backoff before each retry: 50, 100, 200, 400, 800 ms
          Thread.sleep(BASE_BACKOFF_MS << (attempt - 1));
        }
        lastTimestamp = System.currentTimeMillis();   // 1. timestamp before send (wall clock, for the CSV)
        long sendStart = System.nanoTime();           //    nanoTime is used for the latency itself
        lastStatusCode = 0;
        try {
          HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
          lastStatusCode = response.statusCode();
        } finally {
          // 2. response received (or the attempt failed): 3. latency in ms
          lastLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sendStart);
        }
        if (lastStatusCode == 201) {
          successCount.incrementAndGet();
          records.add(new SwipeRecord(lastTimestamp, lastLatencyMs, lastStatusCode, swipe.getResortID()));
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
    records.add(new SwipeRecord(lastTimestamp, lastLatencyMs, lastStatusCode, swipe.getResortID()));
  }
}