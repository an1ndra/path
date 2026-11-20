package com.anindra.path.secret;

import com.anindra.path.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static String key() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) i;
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    private SecretCipher cipher() {
        GatewayProperties props = new GatewayProperties();
        props.setMasterKey(key());
        return new SecretCipher(props);
    }

    @Test
    void encryptsAndDecrypts() {
        SecretCipher cipher = cipher();
        String ciphertext = cipher.encrypt("sk-test-value");

        assertThat(ciphertext).startsWith("v1:");
        assertThat(cipher.decrypt(ciphertext)).isEqualTo("sk-test-value");
    }

    @Test
    void rotationReencryptsAndInvalidatesTheOldKey() {
        SecretCipher cipher = cipher();
        String oldCiphertext = cipher.encrypt("sk-rotate-me");

        String newKeyBase64 = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes());
        byte[] newKey = SecretCipher.parseKey(newKeyBase64);

        String reencrypted = SecretCipher.encryptWith(newKey, cipher.decrypt(oldCiphertext));
        cipher.rotate(newKey);

        assertThat(cipher.decrypt(reencrypted)).isEqualTo("sk-rotate-me");
        assertThatThrownBy(() -> cipher.decrypt(oldCiphertext))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWrongLengthKeys() {
        assertThatThrownBy(() -> SecretCipher.parseKey(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
