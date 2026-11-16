package com.anindra.path.gateway;

import java.util.Map;

/** A fully prepared upstream HTTP call. */
public record UpstreamRequest(String url, Map<String, String> headers, String body) {
}
