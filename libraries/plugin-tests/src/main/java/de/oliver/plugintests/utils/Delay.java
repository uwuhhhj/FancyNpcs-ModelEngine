package de.oliver.plugintests.utils;

import java.util.concurrent.*;

public class Delay {

    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(10);

    public static void delay(Runnable runnable) {
        ScheduledFuture<?> f = EXECUTOR.schedule(runnable, 10, TimeUnit.MILLISECONDS);

        try {
            f.get(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            System.out.println("Delay interrupted: " + e.getMessage());
        }
    }

}
