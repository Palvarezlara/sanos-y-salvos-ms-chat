package com.sanosysalvos.ms_chat.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.sanosysalvos.ms_chat.model.Conversacion;

public interface ConversacionRepository extends MongoRepository<Conversacion, String> {

    Optional<Conversacion> findByClave(String clave);

    /* en Mongo, comparar un campo array con un valor matchea los documentos cuyo array lo contiene */
    @Query(value = "{ 'participantes': ?0 }", sort = "{ 'ultimoMensajeEn': -1 }")
    List<Conversacion> buscarDelUsuario(String sub);
}
