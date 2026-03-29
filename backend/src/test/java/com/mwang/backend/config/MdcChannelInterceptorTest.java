package com.mwang.backend.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MdcChannelInterceptorTest {

    @InjectMocks
    MdcChannelInterceptor interceptor;

    @AfterEach
    void cleanUpMdc() {
        MDC.clear();
    }

    // --- helpers ---

    private Message<?> buildMessage(String userId, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        if (userId != null) {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn(userId);
            accessor.setUser(principal);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private MessageChannel dummyChannel() {
        return mock(MessageChannel.class);
    }

    // --- preSend: Principal present ---

    @Test
    void preSend_withPrincipal_populatesUserIdInMdc() {
        String userId = "user-abc-123";
        Message<?> message = buildMessage(userId, null);

        interceptor.preSend(message, dummyChannel());

        assertThat(MDC.get("userId")).isEqualTo(userId);
    }

    // --- preSend: null Principal ---

    @Test
    void preSend_withNullPrincipal_doesNotWriteUserIdToMdc() {
        Message<?> message = buildMessage(null, null);

        interceptor.preSend(message, dummyChannel());

        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void preSend_withNullPrincipal_doesNotThrow() {
        Message<?> message = buildMessage(null, null);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> interceptor.preSend(message, dummyChannel())
        );
    }

    // --- preSend: destination matching document pattern ---

    @Test
    void preSend_withMatchingDocumentDestination_populatesDocumentIdInMdc() {
        String documentId = "550e8400-e29b-41d4-a716-446655440000";
        String destination = "/app/documents/" + documentId + "/operations";
        Message<?> message = buildMessage(null, destination);

        interceptor.preSend(message, dummyChannel());

        assertThat(MDC.get("documentId")).isEqualTo(documentId);
    }

    @Test
    void preSend_withNonMatchingDestination_doesNotPopulateDocumentIdInMdc() {
        Message<?> message = buildMessage(null, "/topic/general");

        interceptor.preSend(message, dummyChannel());

        assertThat(MDC.get("documentId")).isNull();
    }

    @Test
    void preSend_withPrincipalAndMatchingDestination_populatesBothMdcKeys() {
        String userId = "user-xyz-456";
        String documentId = "aaaabbbb-cccc-dddd-eeee-ffff00001111";
        String destination = "/app/documents/" + documentId + "/cursor";
        Message<?> message = buildMessage(userId, destination);

        interceptor.preSend(message, dummyChannel());

        assertThat(MDC.get("userId")).isEqualTo(userId);
        assertThat(MDC.get("documentId")).isEqualTo(documentId);
    }

    // --- afterSendCompletion: clears both MDC keys ---

    @Test
    void afterSendCompletion_removesBothMdcKeys() {
        MDC.put("userId", "user-to-remove");
        MDC.put("documentId", "doc-to-remove");

        Message<?> message = buildMessage(null, null);

        interceptor.afterSendCompletion(message, dummyChannel(), true, null);

        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("documentId")).isNull();
    }

    @Test
    void afterSendCompletion_whenKeysAbsent_doesNotThrow() {
        Message<?> message = buildMessage(null, null);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> interceptor.afterSendCompletion(message, dummyChannel(), false, null)
        );
    }
}
