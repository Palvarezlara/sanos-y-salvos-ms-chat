package com.sanosysalvos.ms_chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Instant;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.sanosysalvos.ms_chat.model.Conversacion;

/**
 * Las consultas con @Query/@Update se prueban contra un Mongo real (los mocks no validan la sintaxis ni la
 * conversión del id a ObjectId). Usa la base "sanos_chat_test" de un Mongo en localhost:27017; si no hay uno
 * disponible (por ejemplo en CI) las pruebas se omiten.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.mongodb.uri=mongodb://localhost:27017/sanos_chat_test?serverSelectionTimeoutMS=1500")
class ConversacionRepositoryMongoTest {

    private static final Instant T0 = Instant.parse("2026-11-03T12:00:00Z");

    @Autowired
    private ConversacionRepository repo;
    @Autowired
    private MongoTemplate mongo;

    @BeforeEach
    void requiereMongo() {
        boolean disponible;
        try {
            mongo.getDb().runCommand(new Document("ping", 1));
            disponible = true;
        } catch (RuntimeException e) {
            disponible = false;
        }
        assumeTrue(disponible, "No hay un Mongo en localhost:27017: se omiten las pruebas de repositorio");
        mongo.getDb().drop();
    }

    private Conversacion nueva(String mascotaId, String dueno, String interesado) {
        return repo.save(new Conversacion(mascotaId, "Firu", dueno, interesado, T0));
    }

    @Test
    void elSelloDeAvisoLoGanaUnSoloMensajeDentroDelIntervalo() {
        Conversacion c = nueva("m1", "dueno", "interesado");
        Instant limite = T0.minus(Duration.ofMinutes(30));

        long primero = repo.sellarAvisoDueno(c.getId(), limite, T0);
        long segundo = repo.sellarAvisoDueno(c.getId(), limite, T0.plusSeconds(1));

        assertThat(primero).isEqualTo(1);
        assertThat(segundo).as("el segundo mensaje no debe poder avisar").isEqualTo(0);
        /* pasado el intervalo, vuelve a poder avisar */
        assertThat(repo.sellarAvisoDueno(c.getId(), T0.plus(Duration.ofMinutes(31)).minus(Duration.ofMinutes(30)),
                T0.plus(Duration.ofMinutes(31)))).isEqualTo(1);
    }

    @Test
    void losSellosDeDuenoEInteresadoSonIndependientes() {
        Conversacion c = nueva("m1", "dueno", "interesado");
        Instant limite = T0.minus(Duration.ofMinutes(30));

        assertThat(repo.sellarAvisoDueno(c.getId(), limite, T0)).isEqualTo(1);
        assertThat(repo.sellarAvisoInteresado(c.getId(), limite, T0)).isEqualTo(1);
    }

    @Test
    void liberarElSelloPermiteReintentarSoloSiSigueSiendoElNuestro() {
        Conversacion c = nueva("m1", "dueno", "interesado");
        Instant limite = T0.minus(Duration.ofMinutes(30));
        repo.sellarAvisoDueno(c.getId(), limite, T0);

        assertThat(repo.liberarAvisoDueno(c.getId(), T0.plusSeconds(5))).as("sello de otro momento").isEqualTo(0);
        assertThat(repo.liberarAvisoDueno(c.getId(), T0)).isEqualTo(1);
        assertThat(repo.sellarAvisoDueno(c.getId(), limite, T0.plusSeconds(10))).isEqualTo(1);
    }

    @Test
    void elUltimoMensajeNuncaRetrocede() {
        Conversacion c = nueva("m1", "dueno", "interesado");

        repo.registrarUltimoMensaje(c.getId(), T0.plusSeconds(60));
        repo.registrarUltimoMensaje(c.getId(), T0.plusSeconds(10));   // un mensaje más viejo llega después

        assertThat(repo.findById(c.getId()).orElseThrow().getUltimoMensajeEn()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void lasConversacionesBloqueadasNoSeListan() {
        nueva("m1", "dueno", "interesado");
        Conversacion bloqueada = nueva("m2", "dueno", "interesado");
        bloqueada.bloquear("dueno");
        repo.save(bloqueada);

        assertThat(repo.buscarDelUsuario("dueno")).extracting(Conversacion::getMascotaId).containsExactly("m1");
        assertThat(repo.buscarDelUsuario("interesado")).extracting(Conversacion::getMascotaId).containsExactly("m1");
    }

    @Test
    void seEncuentranLasConversacionesDeUnReporte() {
        nueva("m1", "dueno", "a");
        nueva("m1", "dueno", "b");
        nueva("m2", "dueno", "a");

        assertThat(repo.findByMascotaId("m1")).hasSize(2);
    }
}
