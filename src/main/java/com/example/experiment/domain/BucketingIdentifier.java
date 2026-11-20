package com.example.experiment.domain;

/** The piece of information used to recognise a customer when placing them in the test or control group. */
public enum BucketingIdentifier {
    /** Logged-in account; recognised across devices. */
    USER_ID,
    /** Browser cookie or device; works for visitors who are not logged in. */
    DEVICE_COOKIE_ID,
    /** The current visit to the website or app. */
    SESSION_ID,
    /** Mobile app identifier: UDID on iOS, SSAID on Android. */
    MOBILE_APP_ID
}
