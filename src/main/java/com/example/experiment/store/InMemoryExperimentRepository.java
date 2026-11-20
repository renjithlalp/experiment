package com.example.experiment.store;

import com.example.experiment.domain.Experiment;
import com.example.experiment.domain.ExperimentVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe in-memory store. Data is lost on restart; a database implementation replaces it in production. */
public final class InMemoryExperimentRepository implements ExperimentRepository {

    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Experiment> current = new LinkedHashMap<>();
    private final Map<String, List<ExperimentVersion>> history = new HashMap<>();

    @Override
    public String nextId() {
        return String.format("EXP-%06d", sequence.incrementAndGet());
    }

    @Override
    public synchronized Optional<Experiment> find(String id) {
        return Optional.ofNullable(current.get(id));
    }

    @Override
    public synchronized List<Experiment> findAll() {
        List<Experiment> all = new ArrayList<>(current.values());
        all.sort(Comparator.comparing(Experiment::id));
        return all;
    }

    @Override
    public synchronized void save(Experiment experiment, ExperimentVersion version) {
        if (version.version() != experiment.version() || !version.experimentId().equals(experiment.id())) {
            throw new IllegalArgumentException("version does not match the experiment being saved");
        }
        List<ExperimentVersion> versions = history.computeIfAbsent(experiment.id(), k -> new ArrayList<>());
        long expected = versions.size() + 1L;
        if (version.version() != expected) {
            throw new IllegalStateException("expected version " + expected + " but got " + version.version());
        }
        versions.add(version);
        current.put(experiment.id(), experiment);
    }

    @Override
    public synchronized List<ExperimentVersion> versions(String id) {
        return List.copyOf(history.getOrDefault(id, List.of()));
    }
}
