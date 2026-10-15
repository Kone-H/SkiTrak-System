import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Summary statistics for a list of latencies in milliseconds
public class LatencyStats {
  private final List<Long> sorted;

  public LatencyStats(List<Long> latenciesMs) {
    this.sorted = new ArrayList<>(latenciesMs);
    Collections.sort(this.sorted);
  }

  public int count() { return sorted.size(); }
  public long min() { return sorted.get(0); }
  public long max() { return sorted.get(sorted.size() - 1); }

  public double mean() {
    long sum = 0;
    for (long v : sorted) {
      sum += v;
    }
    return (double) sum / sorted.size();
  }

  // Nearest-rank percentile: the smallest value that at least p% of the values are <= to
  public long percentile(double p) {
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
  }
}