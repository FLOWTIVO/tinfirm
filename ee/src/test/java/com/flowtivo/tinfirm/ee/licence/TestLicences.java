package com.flowtivo.tinfirm.ee.licence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.flowtivo.tinfirm.shared.licence.Edition;
import com.flowtivo.tinfirm.shared.licence.Licence;
import com.flowtivo.tinfirm.shared.licence.LicenceStatus;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.support.TestDatabase;

/**
 * TEST FIXTURE ONLY: a key pair the tests sign their licence keys with, made at every run and never stored, trusted
 * beside FLOWTIVO's public key through {@link TestLicenceTrust} (refused outside the {@code test} profile).
 * {@link #key} signs a payload as {@code ee/tools/LicenceKeyTool.java} does. The tests share one database: a test that
 * adds keys removes them all after it ({@link #clear}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestLicences {

    private static final KeyPair PAIR = pair();

    @Bean
    TestLicenceTrust testLicenceTrust() {
        return new TestLicenceTrust(PAIR.getPublic());
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** A Pro key of {@code seats} from a year ago to a year ahead, with a licence id of its own. */
    public static String pro(int seats) {
        return key("PRO", seats, null, today().minusYears(1), today().plusYears(1));
    }

    public static String key(String edition, @Nullable Integer seats, @Nullable Integer companies,
            LocalDate startsAt, LocalDate endsAt) {
        return key("test-" + UUID.randomUUID(), 1, edition, seats, companies, startsAt, endsAt);
    }

    /** A key of the licence {@code id}, revision {@code rev}. */
    public static String key(String id, int rev, String edition, @Nullable Integer seats, @Nullable Integer companies,
            LocalDate startsAt, LocalDate endsAt) {
        return key(payload(id, rev, "Test Customer", edition, seats, companies, startsAt, endsAt), PAIR.getPrivate());
    }

    public static String payload(String id, int rev, String customer, String edition, @Nullable Integer seats,
            @Nullable Integer companies, LocalDate startsAt, LocalDate endsAt) {
        return "{\"v\":2,\"kid\":\"" + LicenceTrust.TEST_KEY_ID + "\",\"id\":\"" + id + "\",\"rev\":" + rev
                + ",\"customer\":\"" + customer
                + "\",\"edition\":\"" + edition
                + "\",\"seats\":" + seats + ",\"companies\":" + companies + ",\"startsAt\":\"" + startsAt
                + "\",\"endsAt\":\"" + endsAt + "\"}";
    }

    /** The key of the payload, signed by the tests' key. */
    public static String key(String payload) {
        return key(payload, PAIR.getPrivate());
    }

    static String key(String payload, PrivateKey signingKey) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(signingKey);
            signer.update(bytes);
            Base64.Encoder base64url = Base64.getUrlEncoder().withoutPadding();
            return "TINFIRM1." + base64url.encodeToString(bytes) + "." + base64url.encodeToString(signer.sign());
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static java.security.PublicKey publicKey() {
        return PAIR.getPublic();
    }

    /** The tests' public key under its key id, as {@link LicenceTrust} trusts it. */
    static java.util.Map<String, java.security.PublicKey> trusted() {
        return java.util.Map.of(LicenceTrust.TEST_KEY_ID, PAIR.getPublic());
    }

    /**
     * A {@link Licensing} that answers {@code status} always, for a unit test that builds a service by hand: Community
     * without a key, or Enterprise without a seat count or a cap of companies.
     */
    public static Licensing licensing(LicenceStatus status) {
        Licence fixed = new Licence() {
            @Override
            public LicenceStatus status() {
                return status;
            }

            @Override
            public int seatsUsed() {
                return 0;
            }

            @Override
            public boolean seatAvailableFor(UUID userId) {
                return true;
            }

            @Override
            public boolean seatAvailableForReactivation(UUID userId) {
                return true;
            }

            @Override
            public @Nullable Integer seatLimit() {
                return status.seatsLicensed();
            }
        };
        return new Licensing(new StaticListableBeanFactory(java.util.Map.of("licence", fixed))
            .getBeanProvider(Licence.class));
    }

    public static final LicenceStatus ENTERPRISE = Licences.status(new LicencePayload("test-enterprise", 1,
            "Test Customer", Edition.ENTERPRISE, null, null, LocalDate.of(2000, 1, 1), LocalDate.of(2999, 12, 31)),
            null, LocalDate.now(ZoneOffset.UTC));

    /**
     * Holds a key in force from {@code startsAt} to {@code endsAt} in place of any, as the owner of the schema: a key
     * that ended too, which the API refuses to apply (a licence in its grace, or expired).
     */
    public static void hold(String edition, @Nullable Integer seats, LocalDate startsAt, LocalDate endsAt) {
        String id = "test-" + UUID.randomUUID();
        String key = key(id, 1, edition, seats, null, startsAt, endsAt);
        try (Connection connection = TestDatabase.connectAsOwner();
                java.sql.PreparedStatement insert = connection.prepareStatement("INSERT INTO licence_key (slot, "
                        + "licence_id, rev, customer, edition, seats, companies, starts_at, ends_at, key_text, "
                        + "applied_at) VALUES ('CURRENT', ?, 1, 'Test Customer', ?, ?, NULL, ?, ?, ?, now())")) {
            connection.createStatement().executeUpdate("DELETE FROM licence_key");
            insert.setString(1, id);
            insert.setString(2, edition);
            insert.setObject(3, seats);
            insert.setObject(4, startsAt);
            insert.setObject(5, endsAt);
            insert.setString(6, key);
            insert.executeUpdate();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Removes every key, as the owner of the schema: the instance runs as Community again. */
    public static void clear() {
        try (Connection connection = TestDatabase.connectAsOwner(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM licence_key");
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
