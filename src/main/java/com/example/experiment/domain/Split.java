package com.example.experiment.domain;

/**
 * Share of customers in the test and control group, as whole percentages that add up to 100.
 * Boxed so a missing share can be reported instead of silently becoming zero.
 */
public record Split(Integer testPercent, Integer controlPercent) {
}
