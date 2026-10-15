import java.time.Instant;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.Gson;

@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint {

  // resortID -> sessions subscribed to that resort
  private static final Map<String, Set<Session>> subscribers = new ConcurrentHashMap<>();
  private static final ScheduledExecutorService pusher =
      Executors.newSingleThreadScheduledExecutor();
  private static final Gson gson = new Gson();


  static {
    // Dummy: every 500ms, push a synthetic top-N to each resort's subscribers.
    pusher.scheduleAtFixedRate(LeaderboardEndpoint::pushAll, 500, 500, TimeUnit.MILLISECONDS);
  }

  @OnOpen
  public void onOpen(Session session, @PathParam("resortID") String resortID) {
    subscribers.computeIfAbsent(resortID, k -> ConcurrentHashMap.newKeySet()).add(session);
  }

  @OnClose
  public void onClose(Session session, @PathParam("resortID") String resortID) {
    Optional.ofNullable(subscribers.get(resortID)).ifPresent(s -> s.remove(session));
  }

  @OnError
  public void onError(Session session, Throwable t) {
    // log and show error
    System.err.println("Error on " + session.getId() + " - " + t.getMessage());

    // get error session ID
    // then clear this session
    String resortID = session.getPathParameters().get("resortID");
    Optional.ofNullable(subscribers.get(resortID)).ifPresent(s -> s.remove(session));
  }

  // return string of top 10 rank
  private static String syntheticLeaderboard(String resortID) {
    int topNRank = 5;
    int vertical = 60000;
    List<Map<String, Object>> topN = new ArrayList<>();

    for (int i = 1; i <= topNRank; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      vertical -= ThreadLocalRandom.current().nextInt(500,1000);
      row.put("rank", i);
      row.put("skierID", ThreadLocalRandom.current().nextInt(1,10001));
      row.put("vertical", vertical);
      topN.add(row);
    }
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("resortID", resortID);
    msg.put("serverTimestamp", Instant.now().toString());   // client 用"收到的时间 - 这个时间"算 push latency
    msg.put("top", topN);
    return gson.toJson(msg);



  }

  private static void pushAll() {
    for (Map.Entry<String, Set<Session>> e : subscribers.entrySet()) {
      String json = syntheticLeaderboard(e.getKey()); // canned/random top-N + serverTimestamp
      for (Session s : e.getValue()) {
        if (s.isOpen()) s.getAsyncRemote().sendText(json);
      }
    }
  }
}