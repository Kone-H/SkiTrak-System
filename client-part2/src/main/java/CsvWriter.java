import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;

// Writes swipe records to a CSV file
// after every sender thread has finished adding records to the shared queue
public class CsvWriter {

  public static void writeSwipeRecords(Collection<SwipeRecord> records, String path)
      throws IOException {
    File file = new File(path);
    File dir = file.getParentFile();
    if (dir != null) {
      dir.mkdirs();   // create results/ if it does not exist yet
    }
    try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
      writer.println(SwipeRecord.CSV_HEADER);
      for (SwipeRecord record : records) {
        writer.println(record.toCsvLine());
      }
    }
  }
}