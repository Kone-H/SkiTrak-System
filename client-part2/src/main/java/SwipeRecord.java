// One row of the per-swipe CSV: {timestamp, requestType, latency, statusCode, resortID}
// Immutable, like SwipeEvent: created once by a sender thread, read later by the CSV writer.
public class SwipeRecord {
  public static final String REQUEST_TYPE = "SWIPE";   // the only request type in Assignment 1
  public static final String CSV_HEADER = "timestamp,requestType,latency,statusCode,resortID";

  private final long timestamp;    // wall-clock time (epoch ms) just before the request was sent
  private final long latencyMs;    // time from send to response
  private final int statusCode;    // HTTP status of the response; 0 if no response (network error)
  private final int resortID;

  public SwipeRecord(long timestamp, long latencyMs, int statusCode, int resortID) {
    this.timestamp = timestamp;
    this.latencyMs = latencyMs;
    this.statusCode = statusCode;
    this.resortID = resortID;
  }

  public long getTimestamp() { return timestamp; }
  public long getLatencyMs() { return latencyMs; }
  public int getStatusCode() { return statusCode; }
  public int getResortID() { return resortID; }

  public String toCsvLine() {
    return timestamp + "," + REQUEST_TYPE + "," + latencyMs + "," + statusCode + "," + resortID;
  }
}