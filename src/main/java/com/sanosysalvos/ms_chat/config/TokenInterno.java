package com.sanosysalvos.ms_chat.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Secreto de servicio (INTERNAL_API_TOKEN, el mismo de ms-mascotas y el BFF). El BFF lo manda junto con los
 * headers de identidad que arma desde el id_token: sin él, X-User-Email se ignora, porque cualquiera que llegue
 * directo a este servicio podría poner el correo de otra persona y usar la cuenta oficial para escribirle.
 */
@Component
public class TokenInterno {

    private final byte[] secreto;

    public TokenInterno(@Value("${internal.api-token:}") String secreto) {
        if (secreto == null || secreto.strip().length() < 32) {
            throw new IllegalStateException(
                    "INTERNAL_API_TOKEN no esta definido (minimo 32 caracteres): ms-mascotas rechazaria las consultas internas");
        }
        this.secreto = secreto.strip().getBytes(StandardCharsets.UTF_8);
    }

    /** Comparación en tiempo constante. */
    public boolean valido(String presentado) {
        return presentado != null && MessageDigest.isEqual(secreto, presentado.strip().getBytes(StandardCharsets.UTF_8));
    }
}
