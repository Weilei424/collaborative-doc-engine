package com.mwang.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.testcontainers.AbstractIntegrationTest;
import com.mwang.backend.web.model.SubmitOperationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CollaborationEndToEndTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DocumentOperationRepository operationRepository;

    @Autowired
    private DocumentRepository documentRepository;

    // FALLBACK: if WebSocket handshake proves unreliable, use service layer directly —
    // inject DocumentOperationService and call submitOperation(...) with a synthetic
    // SimpMessageHeaderAccessor populated from a test user, bypassing STOMP entirely.

    @Test
    void fullWebSocketFlow_submitOperation_broadcastsAcceptedMessage() throws Exception {
        // Step 1: Register a user and obtain a JWT token
        String username = "e2euser_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> registerBody = Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "password123"
        );
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        @SuppressWarnings("unchecked")
        Map<String, Object> authResponse = restTemplate.postForObject(
                "/api/auth/register",
                new HttpEntity<>(objectMapper.writeValueAsString(registerBody), jsonHeaders),
                Map.class
        );
        assertThat(authResponse).isNotNull();
        String token = (String) authResponse.get("token");
        assertThat(token).isNotBlank();

        // Step 2: Create a document
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setContentType(MediaType.APPLICATION_JSON);
        authHeaders.setBearerAuth(token);

        @SuppressWarnings("unchecked")
        Map<String, Object> docResponse = restTemplate.postForObject(
                "/api/documents",
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("title", "E2E Test Doc")), authHeaders),
                Map.class
        );
        assertThat(docResponse).isNotNull();
        String documentIdStr = (String) docResponse.get("id");
        assertThat(documentIdStr).isNotBlank();
        UUID documentId = UUID.fromString(documentIdStr);

        // Step 3: Connect via WebSocket / STOMP (SockJS) with JWT as query param
        SockJsClient sockJsClient = new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))
        );
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        stompClient.setMessageConverter(converter);

        String wsUrl = "ws://localhost:" + port + "/ws?token=" + token;
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();

        StompSession session = stompClient
                .connectAsync(wsUrl, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);

        // Step 4: Subscribe to the document's operations topic
        String topicDestination = "/topic/documents/" + documentId + "/operations";
        session.subscribe(topicDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                received.offer((Map<String, Object>) payload);
            }
        });

        // Brief pause to ensure subscription is active before sending
        Thread.sleep(200);

        // Step 5: Submit an INSERT_TEXT operation
        UUID operationId = UUID.randomUUID();
        SubmitOperationRequest submitRequest = new SubmitOperationRequest(
                operationId,
                0L,
                DocumentOperationType.INSERT_TEXT,
                objectMapper.readTree("{\"path\":[0],\"offset\":0,\"text\":\"hello e2e\"}")
        );

        String submitDestination = "/app/documents/" + documentId + "/operations.submit";
        session.send(submitDestination, submitRequest);

        // Step 6: Await the accepted-operation broadcast (10s timeout)
        Map<String, Object> message = received.poll(10, TimeUnit.SECONDS);

        // Step 7: Assert message received with serverVersion == 1
        assertThat(message)
                .as("Expected an accepted-operation broadcast within 10 seconds")
                .isNotNull();
        assertThat(message.get("serverVersion"))
                .as("serverVersion should be 1 for the first operation on this document")
                .isEqualTo(1);

        // Verify the operation was persisted and the document version advanced
        assertThat(operationRepository
                .findByDocumentIdAndServerVersionGreaterThanOrderByServerVersionAsc(documentId, 0L))
                .as("operation must be persisted in the database")
                .hasSize(1);

        assertThat(documentRepository.findById(documentId).orElseThrow().getCurrentVersion())
                .as("document currentVersion must advance to 1")
                .isEqualTo(1L);

        session.disconnect();
        stompClient.stop();
    }
}
