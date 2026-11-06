package com.flowtivo.tinfirm.vault.internal;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.vault.InstanceKeys;

/**
 * The keys of the instance (ADR-0045), envelope-encrypted like company secrets under the AAD
 * {@code instance|name|version}: RSA key pairs and HMAC-SHA256 keys. A key is generated once per instance: concurrent
 * first calls on several replicas all insert with {@code ON CONFLICT DO NOTHING} and then read the one row that won.
 */
@Service
class InstanceKeysImpl implements InstanceKeys {

    private static final Logger log = LoggerFactory.getLogger(InstanceKeysImpl.class);

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    private static final String RSA = "RSA";

    private static final String HMAC = "HMAC";

    private static final String HMAC_SHA256 = "HmacSHA256";

    private static final int RSA_BITS = 2048;

    private static final int HMAC_BYTES = 32;

    private static final int VERSION = 1;

    private final InstanceKeyStore keys;

    private final EnvelopeCipher cipher;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final TransactionTemplate transactions;

    private final SecureRandom random = new SecureRandom();

    InstanceKeysImpl(InstanceKeyStore keys, EnvelopeCipher cipher, AuditLog auditLog, TimeSource time,
            PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.cipher = cipher;
        this.auditLog = auditLog;
        this.time = time;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A key just generated: its material (wiped once stored) and its size, for the audit. */
    private record Generated(byte[] material, int bits) {
    }

    @Override
    public RsaKey rsaKey(String name) {
        return toRsaKey(stored(name, RSA, () -> {
            KeyPair pair = generateRsa();
            return new Generated(pair.getPrivate().getEncoded(), RSA_BITS);
        }));
    }

    @Override
    public byte[] hmacSha256(String name, byte[] data) {
        InstanceKeyStore.StoredKey stored = stored(name, HMAC, () -> {
            byte[] material = new byte[HMAC_BYTES];
            random.nextBytes(material);
            return new Generated(material, HMAC_BYTES * 8);
        });
        byte[] key = cipher.decrypt(stored.aad(), stored.value());
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data);
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException(HMAC_SHA256 + " is not available", ex);
        }
        finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The stored key of this name, generated on its first use; it must be of this algorithm. */
    private InstanceKeyStore.StoredKey stored(String name, String algorithm, Supplier<Generated> generate) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a key name: " + name);
        }
        InstanceKeyStore.StoredKey stored = keys.findByName(name)
            .orElseGet(() -> create(name, algorithm, generate.get()));
        if (!stored.algorithm().equals(algorithm)) {
            throw new IllegalStateException("The instance key " + name + " is not an " + algorithm + " key");
        }
        return stored;
    }

    private InstanceKeyStore.StoredKey create(String name, String algorithm, Generated generated) {
        byte[] material = generated.material();
        try {
            byte[] kidBytes = new byte[16];
            random.nextBytes(kidBytes);
            String kid = Base64.getUrlEncoder().withoutPadding().encodeToString(kidBytes);
            InstanceKeyAad aad = new InstanceKeyAad(name, VERSION);
            InstanceKeyStore.StoredKey candidate = new InstanceKeyStore.StoredKey(Ids.newId(), name, kid, algorithm,
                    VERSION, cipher.encrypt(aad, material));
            return transactions.execute(status -> {
                if (keys.insertIfAbsent(candidate, time.now())) {
                    auditLog.record(AuditRecord.instance(AuditAction.SECRET_CREATED, Actor.SYSTEM)
                        .target("instance_key", candidate.id())
                        .detail("name", name)
                        .detail("kid", kid)
                        .detail("algorithm", algorithm)
                        .detail("bits", generated.bits())
                        .build());
                    log.info("Created the instance key {} (kid {})", name, kid);
                }
                return keys.findByName(name)
                    .orElseThrow(() -> new IllegalStateException("Instance key " + name + " was not stored"));
            });
        }
        finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    private KeyPair generateRsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA);
            generator.initialize(RSA_BITS, random);
            return generator.generateKeyPair();
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("RSA is not available", ex);
        }
    }

    private RsaKey toRsaKey(InstanceKeyStore.StoredKey stored) {
        byte[] pkcs8 = cipher.decrypt(stored.aad(), stored.value());
        try {
            KeyFactory factory = KeyFactory.getInstance(RSA);
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            RSAPublicKey publicKey = (RSAPublicKey) factory
                .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RsaKey(stored.kid(), publicKey, privateKey);
        }
        catch (GeneralSecurityException | ClassCastException ex) {
            throw new VaultCryptoException("The instance key " + stored.name() + " is not a usable RSA key", ex);
        }
        finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }
}
