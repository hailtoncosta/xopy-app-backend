package br.com.xopy;

import java.util.*;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Component
public class PaymentTokenCipher {
    private final byte[] key;
    private static final SecureRandom RANDOM = new SecureRandom();

    public PaymentTokenCipher(@Value("${app.mp.token-encryption-key:}") String value) {
        byte[] parsed;
        try {
            parsed = Base64.getDecoder().decode(value);
        } catch (Exception e) {
            parsed = new byte[0];
        }
        key = parsed;
    }

    public boolean enabled() {
        return key.length == 32;
    }

    public String encrypt(String value, String context) {
        return run(value, context, true);
    }

    public String decrypt(String value, String context) {
        return run(value, context, false);
    }

    private String run(String value, String context, boolean encrypt) {
        if (!enabled())
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Criptografia dos recebimentos não configurada");
        try {
            byte[] data, iv = new byte[12];
            if (encrypt) {
                RANDOM.nextBytes(iv);
                data = value.getBytes(StandardCharsets.UTF_8);
            } else {
                byte[] raw = Base64.getDecoder().decode(value);
                if (raw.length < 28)
                    throw new IllegalArgumentException();
                System.arraycopy(raw, 0, iv, 0, 12);
                data = Arrays.copyOfRange(raw, 12, raw.length);
            }
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] result = cipher.doFinal(data);
            if (!encrypt)
                return new String(result, StandardCharsets.UTF_8);
            byte[] packed = new byte[iv.length + result.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(result, 0, packed, iv.length, result.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Não foi possível acessar as credenciais de recebimento");
        }
    }
}
