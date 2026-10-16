import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadClientMsg {
  private static final int TOTAL_SWIPES = 500000;
  private static final int NUM_RESORTS = 10;
  private static final int WARMUP_THREADS = 32;
  private static final int ACTUAL_THREADS = 128;   // default main-phase threads if none given

  private static final int WARMUP_SWIPES_PER_THREAD = 1000;
  private static final int WARMUP_TOTAL = WARMUP_THREADS * WARMUP_SWIPES_PER_THREAD;   // 32,000
  private static final int TOMCAT_MAX_KEEPALIVE_REQUESTS = 100;   // Tomcat default maxKeepAliveRequests

  // Subscribe-path state, shared by the subscribers and main
  private static final AtomicBoolean ingestDone = new AtomicBoolean(false);
  private static final AtomicInteger[] updatesPerResort = new AtomicInteger[NUM_RESORTS];
  private static final AtomicInteger reconnections = new AtomicInteger();
  private static final Queue<Long> pushLatencies = new ConcurrentLinkedQueue<>();
  private static final List<Session> sessions = new ArrayList<>();
  private static long totalConnectMs = 0;

  // Program arguments: <baseUrl> [mainThreads]
  public static void main(String[] args) throws Exception {
    String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080/ski-servlet";
    int mainThreads = args.length > 1 ? Integer.parseInt(args[1]) : ACTUAL_THREADS;

    // generate swipe and put in queue
    BlockingQueue<SwipeEvent> queue = new LinkedBlockingQueue<>(10000);
    Thread generator = new Thread(new SwipeGenerator(queue, TOTAL_SWIPES, NUM_RESORTS),
        "generator");
    generator.start();

    // instantiate a sender
    SwipeSender sender = new SwipeSender(baseUrl);

    // 1. open one WebSocket subscriber per resort before any swipe is sent
    connectSubscribers(baseUrl);

    // 2. warmup: 32 threads x 1000 swipes
    long warmupMs = runWarmup(sender, queue);
    double avgWaitTime = (double) warmupMs / (double) WARMUP_TOTAL * WARMUP_THREADS;
    printWarmupSummary(sender, warmupMs, avgWaitTime);

    // keep the warmup counts so the main phase can be reported on its own
    int warmupSuccess = sender.getSuccessCount();
    int warmupFailed = sender.getFailureCount();

    // 3. main phase: send everything warmup did not send (500,000 - 32,000 = 468,000)
    int mainTotal = TOTAL_SWIPES - WARMUP_TOTAL;
    long mainMs = runMainPhase(mainThreads, mainTotal, sender, queue);

    // 4. ingest run is over: close the subscriptions
    closeSubscribers();

    // 5. reports
    double actualThroughput = mainTotal * 1000.0 / mainMs;
    printMainSummary(mainThreads, mainTotal, sender.getSuccessCount() - warmupSuccess,
        sender.getFailureCount() - warmupFailed, mainMs, actualThroughput);
    printLittlesLaw(mainThreads, avgWaitTime, actualThroughput);
    printOverall(sender, warmupMs, mainMs);
    printConnectionStats(mainThreads, sender);
    printSubscribeSummary();

    generator.join();   // the generator stops by itself after producing exactly TOTAL_SWIPES
  }

  // Subscribe path
  private static void connectSubscribers(String baseUrl) throws Exception {
    String wsBaseUrl = baseUrl.replaceFirst("^http", "ws");   // http://... -> ws://...
    WebSocketContainer container = ContainerProvider.getWebSocketContainer();
    for (int r = 1; r <= NUM_RESORTS; r++) {
      updatesPerResort[r - 1] = new AtomicInteger();
      URI uri = URI.create(wsBaseUrl + "/leaderboard/" + r);
      long connectStart = System.nanoTime();
      Session session = container.connectToServer(
          new LeaderboardSubscriber(updatesPerResort[r - 1], pushLatencies, reconnections,
              ingestDone, container, uri),
          uri);
      totalConnectMs += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStart);
      sessions.add(session);
    }
  }

  // Close the subscriptions  set ingestDone
  private static void closeSubscribers() throws IOException {
    ingestDone.set(true);
    for (Session session : sessions) {
      session.close();
    }
  }

  // Warmup Phase: 32 threads, each sends 1000 swipes and then terminates
  private static long runWarmup(SwipeSender sender, BlockingQueue<SwipeEvent> queue)
      throws InterruptedException {
    CountDownLatch warmupDone = new CountDownLatch(WARMUP_THREADS);
    long warmupStart = System.nanoTime();
    for (int t = 0; t < WARMUP_THREADS; t++) { // for every thread
      new Thread(() -> {
        try {
          for (int i = 0; i < WARMUP_SWIPES_PER_THREAD; i++) { // send 1000 msg
            sender.send(queue.take()); //send from queue
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          warmupDone.countDown();
        }
      }, "warmup-" + t).start();
    }
    warmupDone.await();
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - warmupStart);
  }

  // Main Phase: every thread keeps claiming one swipe from a shared counter until none are left
  private static long runMainPhase(int mainThreads, int mainTotal, SwipeSender sender,
      BlockingQueue<SwipeEvent> queue) throws InterruptedException {
    AtomicInteger remaining = new AtomicInteger(mainTotal);
    CountDownLatch mainDone = new CountDownLatch(mainThreads);
    long mainStart = System.nanoTime();
    for (int t = 0; t < mainThreads; t++) { // for every thread
      new Thread(() -> {
        try {
          while (remaining.getAndDecrement() > 0) { // claim one swipe; stop when none are left
            sender.send(queue.take()); //send from queue
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          mainDone.countDown();
        }
      }, "main-" + t).start();
    }
    mainDone.await();
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - mainStart);
  }

  private static void printWarmupSummary(SwipeSender sender, long warmupMs, double avgWaitTime) {
    System.out.println("===== Summary of Warmup Phase =====");
    System.out.println("Threads:         " + WARMUP_THREADS);
    System.out.println("Swipes sent:     " + WARMUP_TOTAL);
    System.out.println("Successful:      " + sender.getSuccessCount());
    System.out.println("Failed:          " + sender.getFailureCount());
    System.out.println("Wall time:       " + warmupMs + " ms");
    System.out.println("Avg latency:     " + avgWaitTime + " ms");
    System.out.printf("Throughput:      %.1f swipes/s%n", WARMUP_TOTAL * 1000.0 / warmupMs);
  }

  private static void printMainSummary(int mainThreads, int mainTotal, int mainSuccess,
      int mainFailed, long mainMs, double actualThroughput) {
    double mainAvgWaitTime = (double) mainMs / (double) mainTotal * mainThreads;
    System.out.println();
    System.out.println("===== Summary of Main Phase =====");
    System.out.println("Threads:         " + mainThreads);
    System.out.println("Swipes sent:     " + mainTotal);
    System.out.println("Successful:      " + mainSuccess);
    System.out.println("Failed:          " + mainFailed);
    System.out.println("Wall time:       " + mainMs + " ms");
    System.out.printf("Avg latency:     %.1f ms%n", mainAvgWaitTime);
    System.out.printf("Throughput:      %.1f swipes/s%n", actualThroughput);
  }

  // predict according to Little's Law: throughput = N / W, using the warmup latency as W
  private static void printLittlesLaw(int mainThreads, double avgWaitTime, double actualThroughput) {
    double predictedThroughput = mainThreads * 1000.0 / avgWaitTime;
    System.out.println();
    System.out.println("===== Little's Law: Predicted vs Actual (main phase) =====");
    System.out.println("Warmup latency  :      " + avgWaitTime + " ms");
    System.out.printf("Predicted throughput:  %.1f swipes/s  (%d threads / W)%n",
        predictedThroughput, mainThreads);
    System.out.printf("Actual throughput:     %.1f swipes/s%n", actualThroughput);
    System.out.printf("Actual / Predicted:    %.1f%%%n", actualThroughput / predictedThroughput * 100);
  }

  private static void printOverall(SwipeSender sender, long warmupMs, long mainMs) {
    long totalMs = warmupMs + mainMs;
    System.out.println();
    System.out.println("===== Overall (all " + TOTAL_SWIPES + " swipes) =====");
    System.out.println("Successful:      " + sender.getSuccessCount());
    System.out.println("Failed:          " + sender.getFailureCount());
    System.out.println("Total wall time: " + totalMs + " ms  (warmup " + warmupMs + " ms + main " + mainMs + " ms)");
    System.out.printf("Throughput:      %.1f swipes/s%n", TOTAL_SWIPES * 1000.0 / totalMs);
  }

  // Required metric 5: connection statistics (connections, reconnections).
  // HttpClient does not expose its pool, so the HTTP numbers come from the configuration:
  // one keep-alive connection per sender thread, and Tomcat closes a keep-alive connection
  // after 100 requests (maxKeepAliveRequests default), so the client reconnects about once
  // every 100 swipes instead of once per swipe.
  private static void printConnectionStats(int mainThreads, SwipeSender sender) {
    int httpReconnects = sender.getSuccessCount() / TOMCAT_MAX_KEEPALIVE_REQUESTS;
    System.out.println();
    System.out.println("===== Connection Statistics =====");
    System.out.println("HTTP connections:          1 shared HttpClient pool, up to "
        + Math.max(WARMUP_THREADS, mainThreads) + " concurrent (one per sender thread)");
    System.out.println("HTTP reconnections (est.): ~" + httpReconnects
        + " (Tomcat closes a keep-alive connection every " + TOMCAT_MAX_KEEPALIVE_REQUESTS + " requests)");
    System.out.println("WebSocket connections:     " + sessions.size());
    System.out.println("WebSocket reconnections:   " + reconnections.get());
  }

  private static void printSubscribeSummary() {
    System.out.println();
    System.out.println("===== Subscribe Path (WebSocket leaderboard) =====");
    int totalUpdates = 0;
    for (int r = 1; r <= NUM_RESORTS; r++) {
      System.out.println("Resort " + r + " updates:   " + updatesPerResort[r - 1].get());
      totalUpdates += updatesPerResort[r - 1].get();
    }
    System.out.println("Subscribers:         " + NUM_RESORTS);
    System.out.println("Total updates:       " + totalUpdates);
    System.out.println("Reconnections:       " + reconnections.get());
    System.out.printf("Avg connect time:    %.1f ms%n", (double) totalConnectMs / NUM_RESORTS);
    if (!pushLatencies.isEmpty()) {
      LatencyStats push = new LatencyStats(new ArrayList<>(pushLatencies));
      System.out.println("Push latency (receive time - serverTimestamp; includes clock skew):");
      System.out.printf("  mean=%.1f ms, median=%d ms, p95=%d ms, p99=%d ms%n",
          push.mean(), push.percentile(50), push.percentile(95), push.percentile(99));
    }
  }
}