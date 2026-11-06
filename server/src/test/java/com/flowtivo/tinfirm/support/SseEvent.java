package com.flowtivo.tinfirm.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One Server-Sent Event as the browser's EventSource would see it (comments are skipped). */
public record SseEvent(String event, String id, String data) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public JsonNode json() {
        return JSON.readTree(data);
    }

    /** Reads events from the lines of a stream, in order, as they arrive. */
    public static Iterator<SseEvent> iterate(Stream<String> lines) {
        Iterator<String> source = lines.iterator();
        return new Iterator<>() {

            private SseEvent next;

            @Override
            public boolean hasNext() {
                if (next == null) {
                    next = read(source);
                }
                return next != null;
            }

            @Override
            public SseEvent next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                SseEvent event = next;
                next = null;
                return event;
            }
        };
    }

    /** All events until the server closes the stream, which must happen within the timeout. */
    public static List<SseEvent> readAll(Stream<String> lines, Duration timeout) {
        return within(timeout, () -> {
            List<SseEvent> events = new ArrayList<>();
            iterate(lines).forEachRemaining(events::add);
            return events;
        });
    }

    /** The next event, which must arrive within the timeout; null when the stream ended. */
    public static SseEvent next(Iterator<SseEvent> events, Duration timeout) {
        return within(timeout, () -> events.hasNext() ? events.next() : null);
    }

    private static <T> T within(Duration timeout, Callable<T> read) {
        FutureTask<T> task = new FutureTask<>(read);
        Thread.ofVirtual().start(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex) {
            task.cancel(true);
            throw new AssertionError("No server-sent event within " + timeout);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
        catch (ExecutionException ex) {
            throw new AssertionError(ex.getCause());
        }
    }

    private static SseEvent read(Iterator<String> lines) {
        String event = null;
        String id = null;
        StringBuilder data = null;
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.isEmpty()) {
                if (data != null) {
                    return new SseEvent(event != null ? event : "message", id, data.toString());
                }
                event = null;
                id = null;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1).stripLeading();
            switch (field) {
                case "event" -> event = value;
                case "id" -> id = value;
                case "data" -> data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                default -> {
                    // retry and unknown fields are ignored
                }
            }
        }
        return data != null ? new SseEvent(event != null ? event : "message", id, data.toString()) : null;
    }
}
