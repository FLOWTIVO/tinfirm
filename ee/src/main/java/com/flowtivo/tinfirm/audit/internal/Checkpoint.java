package com.flowtivo.tinfirm.audit.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A checkpoint of the audit chains' heads (ADR-0111, decision 1): the heads that moved since the checkpoint before it
 * (every chain's in the first), its time, its number, and the digest of the checkpoint before it, so that checkpoints
 * are a chain of their own. Its canonical form is ADR-0010's: keys sorted, no whitespace.
 *
 * <pre>
 * {"chains":[{"companyId":null,"hash":"…","seq":5512},…],"instance":"…","issuedAt":"2026-11-07T10:00:00.000000Z",
 *  "keyId":"k2026","kind":"tinfirm.audit.checkpoint","number":1234,"prev":"…","v":1}
 * </pre>
 *
 * It is stored signed: {@code {"checkpoint":{…},"signature":"<base64 of Ed25519 over the canonical bytes>"}}, itself
 * canonical, one line.
 *
 * @param instance the instance's id, the checkpoints' folder in the sink
 * @param number   1, 2, 3 …
 * @param issuedAt when the heads were read, to the microsecond
 * @param prev     {@code hex(sha256)} of the previous checkpoint's canonical form; 64 zeros before the first
 * @param keyId    the key that signs it
 * @param chains   the heads, the instance's chain first, then by company id
 */
record Checkpoint(UUID instance, long number, Instant issuedAt, String prev, String keyId, List<Head> chains) {

    static final String KIND = "tinfirm.audit.checkpoint";

    static final int VERSION = 1;

    /** The {@code prev} of the first checkpoint. */
    static final String NO_PREVIOUS = "0".repeat(64);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
        .withZone(ZoneOffset.UTC);

    /** The instance chain first, then the companies' by id. */
    static final Comparator<Head> ORDER = Comparator
        .comparing((Head head) -> head.companyId() == null ? "" : head.companyId().toString());

    /**
     * A chain's head.
     *
     * @param companyId null for the instance chain
     */
    record Head(@Nullable UUID companyId, long seq, String hash) {
    }

    Checkpoint {
        issuedAt = issuedAt.truncatedTo(ChronoUnit.MICROS);
        List<Head> sorted = new ArrayList<>(chains);
        sorted.sort(ORDER);
        chains = List.copyOf(sorted);
    }

    /** The head of the chain, when this checkpoint names it. */
    Optional<Head> chain(@Nullable UUID companyId) {
        return chains.stream()
            .filter(head -> companyId == null ? head.companyId() == null : companyId.equals(head.companyId()))
            .findFirst();
    }

    /** The canonical form, which is signed and whose digest the next checkpoint names. */
    String canonical() {
        return AuditCanonicalForm.canonicalJson(tree());
    }

    /** {@code hex(sha256(canonical))}. */
    String digest() {
        return sha256(canonical().getBytes(StandardCharsets.UTF_8));
    }

    /** The document a sink keeps: the checkpoint and its signature, one canonical line without a newline. */
    String signedDocument(AnchorKeys keys) {
        byte[] signature = keys.sign(canonical().getBytes(StandardCharsets.UTF_8));
        ObjectNode document = MAPPER.createObjectNode();
        document.set("checkpoint", tree());
        document.put("signature", Base64.getEncoder().encodeToString(signature));
        return AuditCanonicalForm.canonicalJson(document);
    }

    private ObjectNode tree() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("kind", KIND);
        node.put("v", VERSION);
        node.put("instance", instance.toString());
        node.put("number", number);
        node.put("issuedAt", ISSUED_AT.format(issuedAt));
        node.put("prev", prev);
        node.put("keyId", keyId);
        ArrayNode heads = node.putArray("chains");
        for (Head head : chains) {
            ObjectNode chain = heads.addObject();
            if (head.companyId() == null) {
                chain.putNull("companyId");
            }
            else {
                chain.put("companyId", head.companyId().toString());
            }
            chain.put("seq", head.seq());
            chain.put("hash", head.hash());
        }
        return node;
    }

    /**
     * A signed document, read back: the checkpoint, and whether its signature verifies with a trusted key. A
     * checkpoint whose content is not its own canonical form (a key added or changed after signing) does not verify:
     * the signature is checked over the canonical form of what was read, and the form round-trips exactly.
     *
     * @param checkpoint the checkpoint as read
     * @param verified   its signature verifies with the key it names
     * @param reason     why it does not, when it does not
     */
    record Signed(Checkpoint checkpoint, boolean verified, @Nullable String reason) {
    }

    /** Parses a signed document and verifies its signature; refuses one that is not a checkpoint. */
    static Signed parse(String document, AnchorKeys keys) {
        JsonNode root;
        try {
            root = MAPPER.readTree(document.strip());
        }
        catch (JacksonException ex) {
            throw new IllegalArgumentException("Not a checkpoint: not JSON", ex);
        }
        JsonNode body = root.path("checkpoint");
        if (!body.isObject() || !KIND.equals(body.path("kind").asString(null))) {
            throw new IllegalArgumentException("Not a checkpoint: no \"checkpoint\" of kind " + KIND);
        }
        if (body.path("v").asInt(0) != VERSION) {
            throw new IllegalArgumentException("A checkpoint of version " + body.path("v") + ", which this server"
                    + " does not read");
        }
        List<Head> heads = new ArrayList<>();
        for (JsonNode chain : body.path("chains")) {
            JsonNode company = chain.path("companyId");
            heads.add(new Head(company.isNull() || company.isMissingNode() ? null : UUID.fromString(company.asString()),
                    chain.path("seq").asLong(), chain.path("hash").asString()));
        }
        Checkpoint checkpoint = new Checkpoint(UUID.fromString(body.path("instance").asString()),
                body.path("number").asLong(), Instant.parse(body.path("issuedAt").asString()),
                body.path("prev").asString(), body.path("keyId").asString(), heads);
        String signature = root.path("signature").asString(null);
        if (signature == null) {
            return new Signed(checkpoint, false, "no signature");
        }
        String read = AuditCanonicalForm.canonicalJson(body);
        if (!read.equals(checkpoint.canonical())) {
            return new Signed(checkpoint, false, "its content is not in canonical form");
        }
        if (!keys.verifying().containsKey(checkpoint.keyId())) {
            return new Signed(checkpoint, false, "signed with the key " + checkpoint.keyId() + ", which is not trusted");
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(signature);
        }
        catch (IllegalArgumentException ex) {
            return new Signed(checkpoint, false, "its signature is not base64");
        }
        boolean verified = keys.verify(checkpoint.keyId(), read.getBytes(StandardCharsets.UTF_8), signatureBytes);
        return new Signed(checkpoint, verified, verified ? null : "its signature does not verify with " + checkpoint.keyId());
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
