package com.mwang.backend.collaboration;

import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.User;
import com.mwang.backend.service.OperationHistoryService;
import com.mwang.backend.web.model.AcceptedOperationResponse;
import com.mwang.backend.web.model.OperationPageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReconnectReplayListenerTest {

    private OperationHistoryService operationHistoryService;
    private SimpMessagingTemplate messagingTemplate;
    private ReconnectReplayListener listener;

    private User actor;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        operationHistoryService = mock(OperationHistoryService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        listener = new ReconnectReplayListener(operationHistoryService, messagingTemplate);

        actor = new User();
        actor.setId(UUID.randomUUID());
        documentId = UUID.randomUUID();
    }

    @Test
    void replaysOpsToUserQueueOnConnect() {
        AcceptedOperationResponse op = new AcceptedOperationResponse(
                UUID.randomUUID(), documentId, 3L, DocumentOperationType.INSERT_TEXT,
                null, actor.getId(), "sess", Instant.now());
        OperationPageResponse page = new OperationPageResponse(documentId, 2L, List.of(op), false);

        when(operationHistoryService.getOperationPage(eq(documentId), eq(2L), eq(500), eq(actor)))
                .thenReturn(page);

        listener.onApplicationEvent(buildEvent(actor, documentId, 2L, "sess-1"));

        verify(messagingTemplate).convertAndSendToUser(
                eq(actor.getId().toString()),
                eq("/queue/catchup." + documentId),
                eq(op),
                any(MessageHeaders.class));
    }

    @Test
    void deliveryIsScopedToReconnectingSession() {
        String sessionId = "sess-42";
        AcceptedOperationResponse op = buildOp(documentId, actor.getId(), 1L);
        OperationPageResponse page = new OperationPageResponse(documentId, 0L, List.of(op), false);

        when(operationHistoryService.getOperationPage(eq(documentId), eq(0L), eq(500), eq(actor)))
                .thenReturn(page);

        listener.onApplicationEvent(buildEvent(actor, documentId, 0L, sessionId));

        ArgumentCaptor<MessageHeaders> headersCaptor = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messagingTemplate).convertAndSendToUser(
                any(), any(), any(), headersCaptor.capture());

        String capturedSessionId = headersCaptor.getValue()
                .get(SimpMessageHeaderAccessor.SESSION_ID_HEADER, String.class);
        assertThat(capturedSessionId).isEqualTo(sessionId);
    }

    @Test
    void doesNotReplayWhenHeaderAbsent() {
        listener.onApplicationEvent(buildEventNoHeader(actor));

        verifyNoInteractions(messagingTemplate);
        verifyNoInteractions(operationHistoryService);
    }

    @Test
    void doesNotReplayWhenNoUserInSession() {
        listener.onApplicationEvent(buildEventNoUser(documentId, 0L));

        verifyNoInteractions(messagingTemplate);
        verifyNoInteractions(operationHistoryService);
    }

    @Test
    void pagingContinuesWhileHasMoreTrue() {
        AcceptedOperationResponse op1 = buildOp(documentId, actor.getId(), 1L);
        AcceptedOperationResponse op2 = buildOp(documentId, actor.getId(), 2L);
        OperationPageResponse page1 = new OperationPageResponse(documentId, 0L, List.of(op1), true);
        OperationPageResponse page2 = new OperationPageResponse(documentId, 1L, List.of(op2), false);

        when(operationHistoryService.getOperationPage(eq(documentId), eq(0L), eq(500), eq(actor)))
                .thenReturn(page1);
        when(operationHistoryService.getOperationPage(eq(documentId), eq(1L), eq(500), eq(actor)))
                .thenReturn(page2);

        listener.onApplicationEvent(buildEvent(actor, documentId, 0L, "sess-1"));

        verify(messagingTemplate).convertAndSendToUser(any(), any(), eq(op1), any(MessageHeaders.class));
        verify(messagingTemplate).convertAndSendToUser(any(), any(), eq(op2), any(MessageHeaders.class));
    }

    @Test
    void cursorAdvancesToLastOpVersionInMultiOpPage() {
        AcceptedOperationResponse op1 = buildOp(documentId, actor.getId(), 3L);
        AcceptedOperationResponse op2 = buildOp(documentId, actor.getId(), 5L);
        OperationPageResponse page1 = new OperationPageResponse(documentId, 0L, List.of(op1, op2), true);
        OperationPageResponse page2 = new OperationPageResponse(documentId, 5L, List.of(), false);

        when(operationHistoryService.getOperationPage(eq(documentId), eq(0L), eq(500), eq(actor)))
                .thenReturn(page1);
        when(operationHistoryService.getOperationPage(eq(documentId), eq(5L), eq(500), eq(actor)))
                .thenReturn(page2);

        listener.onApplicationEvent(buildEvent(actor, documentId, 0L, "sess-1"));

        verify(messagingTemplate).convertAndSendToUser(any(), any(), eq(op1), any(MessageHeaders.class));
        verify(messagingTemplate).convertAndSendToUser(any(), any(), eq(op2), any(MessageHeaders.class));
        // Verify second page was fetched with cursor at 5 (last op's serverVersion)
        verify(operationHistoryService).getOperationPage(eq(documentId), eq(5L), eq(500), eq(actor));
    }

    @Test
    void doesNotReplayForNonCatchupSubscription() {
        listener.onApplicationEvent(buildEventWrongDest(actor));

        verifyNoInteractions(messagingTemplate);
        verifyNoInteractions(operationHistoryService);
    }

    // --- helpers ---

    private SessionSubscribeEvent buildEvent(User user, UUID docId, long sinceVersion, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user/queue/catchup." + docId);
        accessor.setNativeHeader("X-Last-Server-Version", String.valueOf(sinceVersion));
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("user", user);
        accessor.setSessionAttributes(attrs);
        accessor.setSessionId(sessionId);
        Message<byte[]> msg = MessageBuilder.withPayload(new byte[0])
                .copyHeaders(accessor.getMessageHeaders()).build();
        return new SessionSubscribeEvent(listener, msg);
    }

    private SessionSubscribeEvent buildEventNoHeader(User user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user/queue/catchup." + UUID.randomUUID());
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("user", user);
        accessor.setSessionAttributes(attrs);
        accessor.setSessionId("sess-2");
        Message<byte[]> msg = MessageBuilder.withPayload(new byte[0])
                .copyHeaders(accessor.getMessageHeaders()).build();
        return new SessionSubscribeEvent(listener, msg);
    }

    private SessionSubscribeEvent buildEventNoUser(UUID docId, long sinceVersion) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user/queue/catchup." + docId);
        accessor.setNativeHeader("X-Last-Server-Version", String.valueOf(sinceVersion));
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setSessionId("sess-3");
        Message<byte[]> msg = MessageBuilder.withPayload(new byte[0])
                .copyHeaders(accessor.getMessageHeaders()).build();
        return new SessionSubscribeEvent(listener, msg);
    }

    private SessionSubscribeEvent buildEventWrongDest(User user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/documents/" + UUID.randomUUID() + "/operations");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("user", user);
        accessor.setSessionAttributes(attrs);
        accessor.setSessionId("sess-4");
        Message<byte[]> msg = MessageBuilder.withPayload(new byte[0])
                .copyHeaders(accessor.getMessageHeaders()).build();
        return new SessionSubscribeEvent(listener, msg);
    }

    private AcceptedOperationResponse buildOp(UUID docId, UUID actorId, long serverVersion) {
        return new AcceptedOperationResponse(
                UUID.randomUUID(), docId, serverVersion,
                DocumentOperationType.INSERT_TEXT, null, actorId, "sess", Instant.now());
    }
}
