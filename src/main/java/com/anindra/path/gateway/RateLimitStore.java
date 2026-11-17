package com.anindra.path.gateway;

/** Pluggable RPM limiter: in-memory for one node, Valkey for a cluster. */
public interface RateLimitStore {

    /** @return 0 when the call may proceed, otherwise the Retry-After seconds. */
    int acquire(Long keyId, int rpmLimit);
}
