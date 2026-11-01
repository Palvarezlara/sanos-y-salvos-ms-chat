package com.sanosysalvos.ms_chat.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.sanosysalvos.ms_chat.model.Mensaje;

public interface MensajeRepository extends MongoRepository<Mensaje, String> {

    List<Mensaje> findByConversacionIdAndEnviadoEnBefore(String conversacionId, Instant antes, Pageable pageable);
}
