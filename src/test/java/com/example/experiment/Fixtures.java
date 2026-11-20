package com.example.experiment;

import com.example.experiment.domain.Bucketing;
import com.example.experiment.domain.BucketingIdentifier;
import com.example.experiment.domain.ExperimentConfig;
import com.example.experiment.domain.PricingAdjustment;
import com.example.experiment.domain.PricingTreatment;
import com.example.experiment.domain.Split;
import com.example.experiment.domain.TreatmentType;
import com.example.experiment.domain.ValueType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/** Shared test data. */
public final class Fixtures {

    public static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    public static final Instant START = Instant.parse("2027-01-04T00:00:00Z");
    public static final Instant END = Instant.parse("2027-02-01T00:00:00Z");

    private Fixtures() {
    }

    public static ExperimentConfig detailsOnly() {
        return new ExperimentConfig("Extra baggage 10% discount", "Test whether 10% off bags lifts sales",
                START, END, TreatmentType.PRICING, 10, null, null, null);
    }

    public static Split split9010() {
        return new Split(90, 10);
    }

    public static Bucketing bucketing() {
        return new Bucketing(BucketingIdentifier.USER_ID,
                List.of(BucketingIdentifier.DEVICE_COOKIE_ID, BucketingIdentifier.SESSION_ID));
    }

    public static PricingTreatment discount10() {
        return new PricingTreatment(PricingAdjustment.DISCOUNT, ValueType.PERCENT, new BigDecimal("10"), "EUR",
                new BigDecimal("20"), new BigDecimal("120"), List.of("BAG_EXTRA_23KG"), List.of("LIGHT", "STANDARD"));
    }

    public static ExperimentConfig complete() {
        ExperimentConfig d = detailsOnly();
        return new ExperimentConfig(d.name(), d.description(), d.startAt(), d.endAt(), d.treatmentType(),
                d.priority(), split9010(), bucketing(), discount10());
    }

    public static ExperimentConfig withSplit(ExperimentConfig c, Split split) {
        return new ExperimentConfig(c.name(), c.description(), c.startAt(), c.endAt(), c.treatmentType(),
                c.priority(), split, c.bucketing(), c.pricing());
    }

    public static ExperimentConfig withPriority(ExperimentConfig c, int priority) {
        return new ExperimentConfig(c.name(), c.description(), c.startAt(), c.endAt(), c.treatmentType(),
                priority, c.split(), c.bucketing(), c.pricing());
    }

    public static ExperimentConfig withPricing(ExperimentConfig c, PricingTreatment pricing) {
        return new ExperimentConfig(c.name(), c.description(), c.startAt(), c.endAt(), c.treatmentType(),
                c.priority(), c.split(), c.bucketing(), pricing);
    }

    public static ExperimentConfig withEnd(ExperimentConfig c, Instant end) {
        return new ExperimentConfig(c.name(), c.description(), c.startAt(), end, c.treatmentType(),
                c.priority(), c.split(), c.bucketing(), c.pricing());
    }

    /** A clock tests can move forward. */
    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void set(Instant instant) {
            now = instant;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
