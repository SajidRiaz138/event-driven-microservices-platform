package com.sajidriaz.orderplatform.paymentservice.entity;

/** The kinds of provider call payment-service makes (ADR-0016 §1). */
public enum OperationType {

    /** Place a hold on funds. Pre-pivot: still compensatable by a void. */
    AUTHORIZE,

    /** Take the held funds. This is the saga pivot — after it, the saga rolls forward. */
    CAPTURE,

    /** Return captured funds. Only legal against a positively SUCCEEDED capture. */
    REFUND,

    /** Release an authorization hold that will never be captured. */
    VOID
}
