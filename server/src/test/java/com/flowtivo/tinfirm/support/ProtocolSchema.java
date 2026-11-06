package com.flowtivo.tinfirm.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.json.JsonMapper;

/**
 * The binding runner protocols, {@code protocol/runner-v1.schema.json} and {@code protocol/runner-v2.schema.json}
 * (JSON Schema 2020-12, with format assertions), for validating frames in tests (CONVENTIONS §14.10, docs/archive/v1/CONVENTIONS.md §15.1).
 */
public final class ProtocolSchema {

    private static final Schema V1 = load(path(1));

    private static final Schema V2 = load(path(2));

    private ProtocolSchema() {
    }

    /** The schema problems of one frame of protocol v1; empty when it is a valid {@code Message}. */
    public static List<String> violations(String frame) {
        return violations(1, frame);
    }

    /** The schema problems of one frame of the given protocol version; empty when it is a valid {@code Message}. */
    public static List<String> violations(int version, String frame) {
        Schema schema = version >= 2 ? V2 : V1;
        return schema.validate(frame, InputFormat.JSON).stream().map(Object::toString).toList();
    }

    public static Path path() {
        return path(1);
    }

    /** The schema of the version: next to the configured v1 schema. */
    public static Path path(int version) {
        String configured = System.getProperty("tinfirm.runner.protocol.schema");
        Path v1 = Path.of(configured != null ? configured : "../protocol/runner-v1.schema.json");
        return version >= 2 ? v1.resolveSibling("runner-v2.schema.json") : v1;
    }

    private static Schema load(Path path) {
        try {
            String text = Files.readString(path);
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                        .formatAssertionsEnabled(true)
                        .build()));
            return registry.getSchema(JsonMapper.builder().build().readTree(text));
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
