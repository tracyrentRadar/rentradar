package com.rentradar.backend.ml;

/**
 * The inference tier failed, refused, or answered with something other than what
 * was asked for.
 *
 * <p>Separate from a generic runtime failure because the gateway treats these
 * differently: a refusal is a 502 to the caller with the detail preserved, while
 * a model mismatch is a 500, because it means the two tiers disagree about
 * reality and no answer from either can be trusted until that is resolved.
 */
public class MlServiceException extends RuntimeException {

    private final boolean upstreamRefusal;

    public MlServiceException(String message, boolean upstreamRefusal) {
        super(message);
        this.upstreamRefusal = upstreamRefusal;
    }

    public MlServiceException(String message, Throwable cause) {
        super(message, cause);
        this.upstreamRefusal = false;
    }

    /** True when the inference tier declined deliberately rather than broke. */
    public boolean isUpstreamRefusal() {
        return upstreamRefusal;
    }
}