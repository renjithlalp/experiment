package com.example.experiment;

import com.example.experiment.catalogue.InMemoryProductCatalogue;
import com.example.experiment.catalogue.ProductCatalogue;
import com.example.experiment.service.ExperimentService;
import com.example.experiment.service.LifecycleScheduler;
import com.example.experiment.store.InMemoryExperimentRepository;
import com.example.experiment.validation.ExperimentValidator;
import com.example.experiment.web.ApiServer;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Starts the experiment configuration API.
 *
 * <p>Environment: {@code PORT} (default 8090) and {@code TICK_SECONDS}, how often the scheduler checks start and
 * end times (default 30).
 */
public final class ServerMain {

    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());

    public static void main(String[] args) throws Exception {
        int port = intEnv("PORT", 8090);
        int tickSeconds = intEnv("TICK_SECONDS", 30);

        ProductCatalogue catalogue = InMemoryProductCatalogue.sample();
        ExperimentService service = new ExperimentService(new InMemoryExperimentRepository(),
                new ExperimentValidator(catalogue), Clock.systemUTC());
        ApiServer api = new ApiServer(service, catalogue).start(port);
        LifecycleScheduler scheduler = new LifecycleScheduler(service, Duration.ofSeconds(tickSeconds));
        scheduler.start();

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.close();
            api.close();
            stopped.countDown();
        }));
        LOG.info("Experiment configuration API listening on http://localhost:" + api.port()
                + " (scheduler every " + tickSeconds + "s)");
        stopped.await();
    }

    private static int intEnv(String name, int fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
    }
}
