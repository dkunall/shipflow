package com.shipflow.webhook;

import java.time.Duration;
import java.util.Map;

/** Outbound HTTP abstraction (swappable for tests / other transports). Returns the HTTP status code. */
public interface WebhookTransport {
    int post(String url, Map<String, String> headers, String body, Duration timeout) throws Exception;
}
