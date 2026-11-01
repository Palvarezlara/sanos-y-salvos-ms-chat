package com.sanosysalvos.ms_chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sanosysalvos.ms_chat.dto.ConversacionDto;
import com.sanosysalvos.ms_chat.service.ChatService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void sinSesionRechazaLasPeticionesConUn401() throws Exception {
        mockMvc.perform(get("/chat/conversaciones")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/chat/conversaciones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mascotaId\":\"m1\"}")).andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }

    @Test
    void listaLasConversacionesDelUsuarioDelToken() throws Exception {
        when(chatService.listar("usuario-1")).thenReturn(
                List.of(new ConversacionDto("c1", "m1", "Firu", true, Instant.parse("2026-10-30T12:00:00Z"))));

        mockMvc.perform(get("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("c1"))
                .andExpect(jsonPath("$[0].mascotaNombre").value("Firu"))
                .andExpect(jsonPath("$[0].esDueno").value(true));
    }

    @Test
    void elSubSalePorSiempreDelTokenNuncaDelCuerpo() throws Exception {
        when(chatService.iniciarConversacion("usuario-1", "m1")).thenReturn(
                new ConversacionDto("c1", "m1", "Firu", false, Instant.parse("2026-10-30T12:00:00Z")));

        mockMvc.perform(post("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mascotaId\":\"m1\",\"sub\":\"otra-persona\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("c1"));

        verify(chatService).iniciarConversacion("usuario-1", "m1");
    }

    @Test
    void rechazaUnaSolicitudSinMascotaId() throws Exception {
        mockMvc.perform(post("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mascotaId\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void rechazaUnaFechaMalFormadaEnElHistorial() throws Exception {
        mockMvc.perform(get("/chat/conversaciones/c1/mensajes").param("antes", "ayer")
                        .with(jwt().jwt(j -> j.subject("usuario-1"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void devuelveElHistorialPasandoElLimiteYElSub() throws Exception {
        when(chatService.listarMensajes(eq("usuario-1"), eq("c1"), any(), eq(20))).thenReturn(List.of());

        mockMvc.perform(get("/chat/conversaciones/c1/mensajes").param("limite", "20")
                        .with(jwt().jwt(j -> j.subject("usuario-1"))))
                .andExpect(status().isOk());

        verify(chatService).listarMensajes(eq("usuario-1"), eq("c1"), any(), eq(20));
    }
}
