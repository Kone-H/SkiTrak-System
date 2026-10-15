import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The type Swipe generator.
 */
// generate swipeEvent and put it inside the blocking queue
public class SwipeGenerator implements Runnable {
  private static final int SEASON_ID = 2026;
  private static final int DAY_ID = 1;         // constant number fixed by assignment 1
  private final BlockingQueue<SwipeEvent> queue;
  private final int totalSwipes;
  private final int numResorts;

  /**
   * Instantiates a new Swipe generator.
   *
   * @param queue       the blockingqueue
   * @param totalSwipes the total swipes in this queue
   * @param numResorts  the num of a specific resort
   */
  public SwipeGenerator(BlockingQueue<SwipeEvent> queue, int totalSwipes, int numResorts) {
    this.queue = queue;
    this.totalSwipes = totalSwipes;
    this.numResorts = numResorts;
  }

  @Override
  public void run() {
    try {
      for (int i = 0; i < totalSwipes; i++) {
        queue.put(new SwipeEvent(
            ThreadLocalRandom.current().nextInt(1, numResorts + 1),   // resortID 1–10
            SEASON_ID,
            DAY_ID,
            ThreadLocalRandom.current().nextInt(1, 100001),          // skierID 1–100000
            ThreadLocalRandom.current().nextInt(1, 361),              // time 1–360
            ThreadLocalRandom.current().nextInt(1, 41)));             // liftID 1–40
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}