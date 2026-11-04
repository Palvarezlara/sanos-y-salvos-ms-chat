package com.sanosysalvos.ms_chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
        when(chatService.iniciarConversacion("usuario-1", "m1", null)).thenReturn(
                new ConversacionDto("c1", "m1", "Firu", false, Instant.parse("2026-10-30T12:00:00Z")));

        mockMvc.perform(post("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mascotaId\":\"m1\",\"sub\":\"otra-persona\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("c1"));

        verify(chatService).iniciarConversacion("usuario-1", "m1", null);
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

    @Test
    void elCorreoDelHeaderQueMandaElBffLlegaAlServicio() throws Exception {
        when(chatService.iniciarConversacion("usuario-1", "m1", "persona@example.com")).thenReturn(
                new ConversacionDto("c1", "m1", "Firu", false, Instant.parse("2026-10-30T12:00:00Z")));

        mockMvc.perform(post("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-1")))
                        .header("X-User-Email", "persona@example.com")
                        .header("X-Internal-Token", "secreto-de-servicio-de-prueba-de-al-menos-32-caracteres")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mascotaId\":\"m1\"}"))
                .andExpect(status().isOk());

        verify(chatService).iniciarConversacion("usuario-1", "m1", "persona@example.com");
    }

    @Test
    void sinElSecretoDeServicioElCorreoDelHeaderSeIgnora() throws Exception {
        when(chatService.iniciarConversacion("usuario-1", "m1", null)).thenReturn(
                new ConversacionDto("c1", "m1", "Firu", false, Instant.parse("2026-10-30T12:00:00Z")));

        /* un usuario autenticado que llama directo (sin pasar por el BFF) y pone el correo de otra persona */
        mockMvc.perform(post("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-1")))
                        .header("X-User-Email", "victima@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mascotaId\":\"m1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-1")))
                        .header("X-User-Email", "victima@example.com")
                        .header("X-Internal-Token", "token-equivocado-token-equivocado-123456")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mascotaId\":\"m1\"}"))
                .andExpect(status().isOk());

        verify(chatService, times(2)).iniciarConversacion("usuario-1", "m1", null);
        verify(chatService, never()).iniciarConversacion("usuario-1", "m1", "victima@example.com");
    }

    @Test
    void unParticipanteBorraSuConversacion() throws Exception {
        mockMvc.perform(delete("/chat/conversaciones/c1").with(jwt().jwt(j -> j.subject("usuario-1"))))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/chat/conversaciones/c2").param("bloquear", "true")
                        .with(jwt().jwt(j -> j.subject("usuario-1"))))
                .andExpect(status().isNoContent());

        verify(chatService).eliminarConversacion("usuario-1", "c1", false);
        verify(chatService).eliminarConversacion("usuario-1", "c2", true);
    }

    @Test
    void borrarSinSesionSeRechaza() throws Exception {
        mockMvc.perform(delete("/chat/conversaciones/c1")).andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }

    @Test
    void elRestTieneLimiteDeUsoPorUsuarioYResponde429() throws Exception {
        /* 60 por minuto por defecto: la 61 se rechaza, y a otro usuario no le afecta */
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(get("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-spam"))))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-spam"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mockMvc.perform(get("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-tranquilo"))))
                .andExpect(status().isOk());
    }

    @Test
    void losErroresDeSpringNoSalenComoErrorInterno() throws Exception {
        mockMvc.perform(get("/chat/no-existe").with(jwt().jwt(j -> j.subject("usuario-1"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/chat/conversaciones").with(jwt().jwt(j -> j.subject("usuario-1"))))
                .andExpect(status().isMethodNotAllowed());
    }
}
