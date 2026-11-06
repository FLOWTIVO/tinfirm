package com.flowtivo.tinfirm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Tinfirm server. Modules (Spring Modulith): one top-level package each (CONVENTIONS §10).
 * Authentication is custom (JSON login and OIDC), so Boot's in-memory user with a generated password is disabled.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class TinfirmApplication {

    public static void main(String[] args) {
        SpringApplication.run(TinfirmApplication.class, args);
    }
}
