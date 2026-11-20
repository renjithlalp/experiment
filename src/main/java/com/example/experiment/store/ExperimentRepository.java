package com.example.experiment.store;

import com.example.experiment.domain.Experiment;
import com.example.experiment.domain.ExperimentVersion;
import java.util.List;
import java.util.Optional;

/**
 * Storage for experiments and their append-only version history. An implementation must save the current state
 * and the new version together (one transaction), and must never change or remove a stored version.
 */
public interface ExperimentRepository {

    String nextId();

    Optional<Experiment> find(String id);

    List<Experiment> findAll();

    /** Stores {@code current} and appends {@code version}; {@code version.version()} must equal {@code current.version()}. */
    void save(Experiment current, ExperimentVersion version);

    /** All versions of an experiment, oldest first; empty when the experiment does not exist. */
    List<ExperimentVersion> versions(String id);
}
