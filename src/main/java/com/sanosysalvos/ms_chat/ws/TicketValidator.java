package com.sanosysalvos.ms_chat.ws;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sanosysalvos.ms_chat.config.ChatProperties;

import jakarta.annotation.PostConstruct;

/**
 * Valida el ticket de un solo uso que emite el BFF (POST /api/v1/chat/ticket).
 * El navegador no puede mandar la cookie de sesión a este servicio, así que el
 * BFF, que ya validó la sesión, firma un JWT HS256 de ~30 s con el sub del
 * usuario; acá se verifica firma, audiencia, expiración y que no se haya usado.
 *
 * Todo falla "cerrado": sin clave configurada, o con una clave corta, se
 * rechaza toda conexión.
 */
@Component
public class TicketValidator {

    public static final String AUDIENCIA = "ms-chat";
    private static final int LARGO_MINIMO_SECRETO = 32;
    private static final Logger log = LoggerFactory.getLogger(TicketValidator.class);

    private final byte[] secreto;
    private final Clock clock;
    /* jti -> vencimiento; evita que un ticket interceptado se use dos veces */
    private final Map<String, Instant> usados = new ConcurrentHashMap<>();
    /* la limpieza recorre todo el mapa: se hace como mucho cada 30 s, no en cada conexion (tras un reinicio
       reconectan todos a la vez); un ticket vencido igual se rechaza por su exp, asi que no hay riesgo */
    private volatile Instant ultimaLimpieza = Instant.EPOCH;

    public TicketValidator(ChatProperties props, Clock clock) {
        this.clock = clock;
        String configurado = props.ticketSecret();
        this.secreto = configurado == null ? null : configurado.getBytes(StandardCharsets.UTF_8);
    }

    @PostConstruct
    void avisarSiNoEstaConfigurado() {
        if (secreto == null || secreto.length < LARGO_MINIMO_SECRETO) {
            log.warn("chat.ticket-secret ausente o menor a {} caracteres: se rechazaran todas las conexiones WebSocket",
                    LARGO_MINIMO_SECRETO);
        }
    }

    /** Devuelve el sub del usuario si el ticket es válido; vacío en cualquier otro caso. */
    public Optional<String> validar(String ticket) {
        if (ticket == null || ticket.isBlank() || secreto == null || secreto.length < LARGO_MINIMO_SECRETO) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(ticket);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            if (!jwt.verify(new MACVerifier(secreto))) {
                return Optional.empty();
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant ahora = clock.instant();
            if (claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(ahora)) {
                return Optional.empty();
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(AUDIENCIA)) {
                return Optional.empty();
            }
            String sub = claims.getSubject();
            String jti = claims.getJWTID();
            if (sub == null || sub.isBlank() || jti == null || jti.isBlank()) {
                return Optional.empty();
            }

            limpiarVencidos(ahora);
            if (usados.putIfAbsent(jti, claims.getExpirationTime().toInstant()) != null) {
                return Optional.empty();
            }
            return Optional.of(sub);
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    private void limpiarVencidos(Instant ahora) {
        if (ahora.isBefore(ultimaLimpieza.plusSeconds(30))) {
            return;
        }
        ultimaLimpieza = ahora;
        usados.values().removeIf(vence -> !vence.isAfter(ahora));
    }
}
