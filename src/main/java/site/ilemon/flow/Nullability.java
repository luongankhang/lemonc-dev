package site.ilemon.flow;

/**
 * Three-state lattice for nullability analysis of reference and pointer variables.
 */
public enum Nullability {
    /** Proven to be non-null at the current program point. */
    NON_NULL,

    /** Proven to be null at the current program point. */
    NULL,

    /** Unknown nullability: may be null or non-null. */
    UNKNOWN;

    /**
     * Merges two nullability states across control-flow joins.
     */
    public Nullability merge(Nullability other) {
        if (other == null || other == UNKNOWN || this == UNKNOWN) {
            return UNKNOWN;
        }
        if (this == other) {
            return this;
        }
        return UNKNOWN;
    }
}
