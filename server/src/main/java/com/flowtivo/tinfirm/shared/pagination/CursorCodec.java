package com.flowtivo.tinfirm.shared.pagination;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Encodes keyset positions as opaque cursors: each key is base64url-encoded and the keys are joined with dots.
 * Clients must treat cursors as opaque. A cursor only names a position; it grants no access, because every page
 * query still applies authorisation and row-level security.
 */
final class CursorCodec {

    private static final int MAX_CURSOR_LENGTH = 2048;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CursorCodec() {
    }

    static String encode(List<String> keys) {
        List<String> parts = new ArrayList<>(keys.size());
        for (String key : keys) {
            parts.add(ENCODER.encodeToString(key.getBytes(StandardCharsets.UTF_8)));
        }
        return String.join(".", parts);
    }

    static List<String> decode(String cursor) {
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw ApiException.validation("Invalid cursor");
        }
        try {
            List<String> keys = new ArrayList<>();
            for (String part : cursor.split("\\.", -1)) {
                keys.add(new String(DECODER.decode(part), StandardCharsets.UTF_8));
            }
            return keys;
        }
        catch (IllegalArgumentException ex) {
            throw ApiException.validation("Invalid cursor");
        }
    }
}
