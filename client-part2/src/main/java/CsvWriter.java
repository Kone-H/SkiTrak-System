import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;

// Writes results to CSV files.
// Lab 2 Exercise 3, strategy 3 ("shared collection, single writer"): called once, by one thread,
// after every sender thread has finished adding records to the shared queue.
public class CsvWriter {

  // One row per swipe: timestamp,requestType,latency,statusCode,resortID
  public static void writeSwipeRecords(Collection<SwipeRecord> records, String path)
      throws IOException {
    try (PrintWriter writer = open(path)) {
      writer.println(SwipeRecord.CSV_HEADER);
      for (SwipeRecord record : records) {
        writer.println(record.toCsvLine());
      }
    }
  }

  // One row per time bucket: second the bucket starts at, swipes/second in that bucket
  public static void writeThroughputBuckets(double[] throughputPerBucket, int bucketSeconds,
      String path) throws IOException {
    try (PrintWriter writer = open(path)) {
      writer.println("bucketStartSecond,swipesPerSecond");
      for (int i = 0; i < throughputPerBucket.length; i++) {
        writer.printf("%d,%.1f%n", i * bucketSeconds, throughputPerBucket[i]);
      }
    }
  }

  // Creates the parent folder (e.g. results/) if needed and opens the file for writing
  private static PrintWriter open(String path) throws IOException {
    File file = new File(path);
    File dir = file.getParentFile();
    if (dir != null) {
      dir.mkdirs();
    }
    return new PrintWriter(new FileWriter(file));
  }
}