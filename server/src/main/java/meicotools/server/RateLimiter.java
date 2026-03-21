package meicotools.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimiter {

    private static final int MAX_REQUESTS = 60;
    private static final long WINDOW_MS = 60_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::cleanup, 2, 2, TimeUnit.MINUTES);
    }

    public boolean isAllowed(String clientIp) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(clientIp, (key, w) -> {
            if (w == null || now - w.start > WINDOW_MS) {
                return new Window(now);
            }
            return w;
        });
        return window.count.incrementAndGet() <= MAX_REQUESTS;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().start > WINDOW_MS * 2);
    }

    private static class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger(0);

        Window(long start) {
            this.start = start;
        }
    }
}
