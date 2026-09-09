package com.ranikunlabs.platform.sharedaudit.policy;

/** Static residual-loss classification from the AU-G1 policy registry. */
public enum AcceptableLossWindow {
    NONE_AFTER_COMMIT,
    OCCURRENCE_TO_ACK_RESIDUAL,
    SIDE_EFFECT_TO_ACK_RESIDUAL,
    BOUNDED_BEST_EFFORT
}
