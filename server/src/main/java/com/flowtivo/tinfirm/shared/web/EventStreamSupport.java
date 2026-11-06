package com.flowtivo.tinfirm.shared.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events for controllers that implement generated API interfaces.
 * <p>
 * The OpenAPI generator declares a {@code text/event-stream} operation as {@code ResponseEntity<String>}. Spring MVC
 * inspects the body of a {@code ResponseEntity} at runtime and streams it when it is an emitter, so the controller
 * returns {@link #response(SseEmitter)}.
 */
public final class EventStreamSupport {

    private EventStreamSupport() {
    }

    /**
     * Wraps an emitter in the response type the generated interface declares. Also disables proxy buffering
     * ({@code X-Accel-Buffering: no}) so that events reach the browser at once.
     */
    @SuppressWarnings("unchecked")
    public static ResponseEntity<String> response(SseEmitter emitter) {
        ResponseEntity<?> response = ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store")
            .header("X-Accel-Buffering", "no")
            .body(emitter);
        return (ResponseEntity<String>) response;
    }
}
