package com.flowtivo.tinfirm.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.api.model.SecretCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SecretUpdateRequest;

/** The customised generator template masks writeOnly properties, so logging a request DTO never leaks a secret. */
class GeneratedModelRedactionTest {

    @Test
    void secretValuesAreMaskedInToString() {
        assertThat(new SecretCreateRequest("TOKEN", "super-secret-value").toString()).contains("TOKEN")
            .doesNotContain("super-secret-value");
        assertThat(new SecretUpdateRequest("super-secret-value").toString()).doesNotContain("super-secret-value");
    }
}
