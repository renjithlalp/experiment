package com.example.experiment.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The primary bucketing identifier and the ordered identifiers to try when it is missing. */
public record Bucketing(BucketingIdentifier primary, List<BucketingIdentifier> fallbackOrder) {

    public Bucketing {
        // ArrayList rather than List.copyOf, so a null element survives long enough to be reported by validation
        fallbackOrder = fallbackOrder == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(fallbackOrder));
    }
}
