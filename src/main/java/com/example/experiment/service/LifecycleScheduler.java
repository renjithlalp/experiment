package com.example.experiment.service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runs {@link ExperimentService#tick()} at a fixed interval so experiments start and end on time. */
public final class LifecycleScheduler implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LifecycleScheduler.class.getName());

    private final ExperimentService service;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public LifecycleScheduler(ExperimentService service, Duration interval) {
        this.service = service;
        this.interval = interval;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "experiment-lifecycle");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::runOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void runOnce() {
        try {
            int changed = service.tick();
            if (changed > 0) {
                LOG.info(() -> "Lifecycle scheduler moved " + changed + " experiment(s)");
            }
        } catch (RuntimeException e) {
            // Keep the schedule alive: an exception would otherwise cancel all future runs
            LOG.log(Level.SEVERE, "Lifecycle scheduler run failed", e);
        }
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
