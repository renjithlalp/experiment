package com.example.experiment.service;

import com.example.experiment.domain.BucketingIdentifier;
import com.example.experiment.domain.Experiment;
import com.example.experiment.domain.PricingTreatment;
import com.example.experiment.domain.Split;
import com.example.experiment.domain.TreatmentType;
import java.time.Instant;
import java.util.List;

/**
 * A Running experiment as handed to the bucketing engine. The engine stamps {@code experimentId} and
 * {@code configVersion} on the traffic it assigns, so results can be traced back to the exact settings.
 */
public record ActiveExperiment(
        String experimentId,
        String name,
        long configVersion,
        int priority,
        Split split,
        BucketingIdentifier primaryIdentifier,
        List<BucketingIdentifier> fallbackOrder,
        TreatmentType treatmentType,
        PricingTreatment pricing,
        Instant startAt,
        Instant endAt) {

    static ActiveExperiment of(Experiment e) {
        var c = e.config();
        return new ActiveExperiment(e.id(), c.name(), e.configVersion(), c.priority(), c.split(),
                c.bucketing().primary(), c.bucketing().fallbackOrder(), c.treatmentType(), c.pricing(),
                c.startAt(), c.endAt());
    }
}
