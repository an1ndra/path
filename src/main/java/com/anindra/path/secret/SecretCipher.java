package com.anindra.path.secret;

import com.anindra.path.config.GatewayProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * AES-GCM at rest encryption for upstream provider credentials.
 * The key comes from GATEWAY_MASTER_KEY, or is generated once into a 0600 file.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private volatile SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(GatewayProperties props) {
        byte[] raw = loadOrCreateKey(props);
        if (raw.length != 32) {
            throw new IllegalStateException("gateway.master-key must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    private static byte[] loadOrCreateKey(GatewayProperties props) {
        String configured = props.getMasterKey();
        if (configured != null && !configured.isBlank()) {
            return Base64.getDecoder().decode(configured.trim());
        }
        try {
            Path file = Path.of(props.getMasterKeyPath());
            if (Files.exists(file)) {
                return Base64.getDecoder().decode(Files.readString(file).trim());
            }
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(generated));
            try {
                Files.setPosixFilePermissions(file, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem
            }
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load or create gateway master key", e);
        }
    }

    public String encrypt(String plaintext) {
        return encryptWith(key, plaintext, random);
    }

    /** Encrypts under an explicit key; used when rotating the master key. */
    public static String encryptWith(byte[] rawKey, String plaintext) {
        return encryptWith(new SecretKeySpec(rawKey, "AES"), plaintext, new SecureRandom());
    }

    private static String encryptWith(SecretKeySpec key, String plaintext, SecureRandom random) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encrypt provider credential", e);
        }
    }

    /** Validates and decodes a base64 32-byte master key. */
    public static byte[] parseKey(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != 32) {
            throw new IllegalArgumentException("master key must decode to exactly 32 bytes");
        }
        return raw;
    }

    /** Switches the active key; callers must have re-encrypted stored ciphertext first. */
    public void rotate(byte[] rawKey) {
        this.key = new SecretKeySpec(rawKey, "AES");
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        if (!encoded.startsWith("v1:")) {
            throw new IllegalStateException("Unknown ciphertext format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decrypt provider credential", e);
        }
    }
}
