import com.lpsm.vod.LifecycleExecutor;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Standalone regression for the RejectedExecutionException reported on Android. */
public class LifecycleExecutorRegression {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        ExecutorService old = Executors.newFixedThreadPool(1);
        old.shutdownNow();
        boolean reproduced = false;
        try { old.execute(() -> {}); }
        catch (RejectedExecutionException expected) { reproduced = true; }
        require(reproduced, "Negative control must reproduce the original exception");

        LifecycleExecutor healthy = new LifecycleExecutor(1);
        CountDownLatch ran = new CountDownLatch(1);
        require(healthy.execute(ran::countDown), "Live screen must accept work");
        require(ran.await(3, TimeUnit.SECONDS), "Normal loading must still run");
        healthy.shutdownNow();
        require(!healthy.execute(() -> { throw new AssertionError("Late task ran"); }),
            "Delayed activation/category callback must be cancelled after close");
        healthy.shutdownNow();

        LifecycleExecutor queued = new LifecycleExecutor(1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), ended = new CountDownLatch(1);
        AtomicInteger pendingRan = new AtomicInteger();
        queued.execute(() -> {
            started.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            ended.countDown();
        });
        require(started.await(3, TimeUnit.SECONDS), "Worker should start");
        queued.execute(pendingRan::incrementAndGet);
        queued.shutdownNow();
        release.countDown();
        require(ended.await(3, TimeUnit.SECONDS), "Running work should finish");
        require(pendingRan.get() == 0, "Queued work from a destroyed screen must not run");

        for (int round = 0; round < 30; round++) {
            LifecycleExecutor racing = new LifecycleExecutor(2);
            CyclicBarrier start = new CyclicBarrier(5);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread[] submitters = new Thread[4];
            for (int i = 0; i < submitters.length; i++) {
                submitters[i] = new Thread(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 2000; j++) racing.execute(() -> {});
                    } catch (Throwable unexpected) { error.compareAndSet(null, unexpected); }
                });
                submitters[i].start();
            }
            start.await();
            racing.shutdownNow();
            for (Thread thread : submitters) thread.join(3000);
            require(error.get() == null, "Submission/shutdown race must not throw: " + error.get());
            for (Thread thread : submitters) require(!thread.isAlive(), "No submission deadlock");
            require(!racing.execute(() -> {}), "Closed executor must keep rejecting safely");
        }
        System.out.println("PASS: original failure reproduced; live loading preserved; late and queued work cancelled; 240000 concurrent submissions checked.");
    }
}
