package com.mwang.backend.web.controller;

import com.mwang.backend.service.exception.CollaborationSessionAccessDeniedException;
import com.mwang.backend.service.exception.CollaborationSessionNotFoundException;
import com.mwang.backend.service.exception.InvalidCollaborationRequestException;
import com.mwang.backend.service.exception.InvalidPresenceUpdateException;
import com.mwang.backend.web.model.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.MessageConversionException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationMessagingExceptionHandlerTest {

    private final CollaborationMessagingExceptionHandler handler = new CollaborationMessagingExceptionHandler();

    @Test
    void handleSessionNotFoundReturnsDeterministicErrorCode() {
        ApiErrorResponse response = handler.handleSessionNotFound(
                new CollaborationSessionNotFoundException(UUID.fromString("11111111-1111-1111-1111-111111111111"), UUID.fromString("22222222-2222-2222-2222-222222222222"))
        );

        assertThat(response.code()).isEqualTo("COLLABORATION_SESSION_NOT_FOUND");
        assertThat(response.message()).contains("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void handleSessionAccessDeniedReturnsDeterministicErrorCode() {
        ApiErrorResponse response = handler.handleSessionAccessDenied(
                new CollaborationSessionAccessDeniedException(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        UUID.fromString("33333333-3333-3333-3333-333333333333"))
        );

        assertThat(response.code()).isEqualTo("COLLABORATION_SESSION_ACCESS_DENIED");
        assertThat(response.message()).contains("33333333-3333-3333-3333-333333333333");
    }

    @Test
    void handleInvalidPresenceUpdateReturnsDeterministicErrorCode() {
        ApiErrorResponse response = handler.handleInvalidPresenceUpdate(new InvalidPresenceUpdateException("Presence update requires sessionId and type"));

        assertThat(response.code()).isEqualTo("INVALID_PRESENCE_UPDATE");
        assertThat(response.message()).isEqualTo("Presence update requires sessionId and type");
    }

    @Test
    void handleInvalidCollaborationRequestReturnsDeterministicErrorCode() {
        ApiErrorResponse response = handler.handleInvalidCollaborationRequest(
                new InvalidCollaborationRequestException("Leave session requires sessionId")
        );

        assertThat(response.code()).isEqualTo("INVALID_COLLABORATION_REQUEST");
        assertThat(response.message()).isEqualTo("Leave session requires sessionId");
    }

    @Test
    void handleMalformedCollaborationPayloadReturnsDeterministicErrorCode() {
        ApiErrorResponse response = handler.handleMalformedCollaborationPayload(
                new MessageConversionException("Could not read payload")
        );

        assertThat(response.code()).isEqualTo("INVALID_COLLABORATION_REQUEST");
        assertThat(response.message()).isEqualTo("Malformed collaboration message payload");
    }
}