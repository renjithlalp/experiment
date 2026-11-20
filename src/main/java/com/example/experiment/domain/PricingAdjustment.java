package com.example.experiment.domain;

/** How a pricing treatment changes the price shown to the test group. */
public enum PricingAdjustment {
    /** Lower the price by a percentage or an amount. */
    DISCOUNT,
    /** Raise the price by a percentage or an amount. */
    MARKUP,
    /** Replace the price with a fixed amount. */
    FLAT_RATE
}
