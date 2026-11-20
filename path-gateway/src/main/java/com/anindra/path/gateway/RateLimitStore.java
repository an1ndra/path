package com.anindra.path.gateway;

/** Pluggable RPM limiter: in-memory for one node, Valkey for a cluster. */
public interface RateLimitStore {

    /**
     * @param bucket "key:&lt;id&gt;" or "project:&lt;id&gt;"
     * @return 0 when the call may proceed, otherwise the Retry-After seconds.
     */
    int acquire(String bucket, int rpmLimit);

    default int acquire(Long keyId, int rpmLimit) {
        return acquire("key:" + keyId, rpmLimit);
    }
}
