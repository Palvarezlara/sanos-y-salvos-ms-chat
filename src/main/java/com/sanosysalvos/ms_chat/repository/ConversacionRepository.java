package com.sanosysalvos.ms_chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import com.sanosysalvos.ms_chat.model.Conversacion;

public interface ConversacionRepository extends MongoRepository<Conversacion, String> {

    Optional<Conversacion> findByClave(String clave);

    /* en Mongo, comparar un campo array con un valor matchea los documentos cuyo array lo contiene; las
       conversaciones bloqueadas (sin datos) no se listan */
    @Query(value = "{ 'participantes': ?0, 'bloqueadaPor': null }", sort = "{ 'ultimoMensajeEn': -1 }")
    List<Conversacion> buscarDelUsuario(String sub);

    List<Conversacion> findByMascotaId(String mascotaId);

    /* Actualizaciones atómicas de un solo campo: guardar el documento entero pisaba los cambios de otro mensaje
       simultáneo (retrocedía ultimoMensajeEn y dejaba pasar dos avisos por correo). */

    @Query("{ 'id': ?0 }")
    @Update("{ '$max': { 'ultimoMensajeEn': ?1 } }")
    long registrarUltimoMensaje(String id, Instant momento);

    /* Devuelve 1 solo si nadie avisó dentro del intervalo (limite = ahora - intervalo): quien recibe 1 es quien avisa */
    @Query("{ 'id': ?0, '$or': [ { 'ultimoAvisoDueno': null }, { 'ultimoAvisoDueno': { '$lte': ?1 } } ] }")
    @Update("{ '$set': { 'ultimoAvisoDueno': ?2 } }")
    long sellarAvisoDueno(String id, Instant limite, Instant ahora);

    @Query("{ 'id': ?0, '$or': [ { 'ultimoAvisoInteresado': null }, { 'ultimoAvisoInteresado': { '$lte': ?1 } } ] }")
    @Update("{ '$set': { 'ultimoAvisoInteresado': ?2 } }")
    long sellarAvisoInteresado(String id, Instant limite, Instant ahora);

    /* si el aviso no pudo publicarse, se quita el sello (solo si sigue siendo el nuestro) para reintentar después */
    @Query("{ 'id': ?0, 'ultimoAvisoDueno': ?1 }")
    @Update("{ '$unset': { 'ultimoAvisoDueno': '' } }")
    long liberarAvisoDueno(String id, Instant sello);

    @Query("{ 'id': ?0, 'ultimoAvisoInteresado': ?1 }")
    @Update("{ '$unset': { 'ultimoAvisoInteresado': '' } }")
    long liberarAvisoInteresado(String id, Instant sello);
}
