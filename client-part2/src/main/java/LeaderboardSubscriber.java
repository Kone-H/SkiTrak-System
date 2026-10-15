import com.google.gson.JsonParser;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// FromLab 2 Part 6 EchoClient: @ClientEndpoint + @OnMessage,
@ClientEndpoint
public class LeaderboardSubscriber {

  private final AtomicInteger updatesReceived;   // leaderboard messages received by this subscriber
  private final Queue<Long> pushLatencies;       // receive time - serverTimestamp, in ms
  private final AtomicInteger reconnections;
  private final AtomicBoolean ingestDone;        // set by main when all swipes are sent
  private final WebSocketContainer container;
  private final URI uri;

  public LeaderboardSubscriber(AtomicInteger updatesReceived, Queue<Long> pushLatencies,
      AtomicInteger reconnections, AtomicBoolean ingestDone,
      WebSocketContainer container, URI uri) {
    this.updatesReceived = updatesReceived;
    this.pushLatencies = pushLatencies;
    this.reconnections = reconnections;
    this.ingestDone = ingestDone;
    this.container = container;
    this.uri = uri;
  }

  @OnMessage
  public void onMessage(String message) {
    long receiveTime = System.currentTimeMillis();   // take the time before parsing
    String serverTimestamp = JsonParser.parseString(message).getAsJsonObject()
        .get("serverTimestamp").getAsString();
    pushLatencies.add(receiveTime - Instant.parse(serverTimestamp).toEpochMilli());
    updatesReceived.incrementAndGet();   // one leaderboard received
  }

  @OnClose
  public void onClose(Session session) {
    if (!ingestDone.get()) {
      new Thread(this::reconnect).start();   // reconnect off the WebSocket callback thread
    }
  }

  @OnError
  public void onError(Session session, Throwable t) {
    System.out.println("Error on " + session.getId() + ": " + t.getMessage());
  }

  private void reconnect() {
    for (int attempt = 1; attempt <= 5 && !ingestDone.get(); attempt++) {
      try {
        Thread.sleep(1000L * attempt);
        container.connectToServer(this, uri);
        reconnections.incrementAndGet();
        return;
      } catch (Exception e) {
        // server not reachable yet: try again
      }
    }
  }
}