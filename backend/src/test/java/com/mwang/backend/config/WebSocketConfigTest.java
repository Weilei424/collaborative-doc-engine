package com.mwang.backend.config;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.CurrentUserProvider;
import com.mwang.backend.service.DocumentAuthorizationService;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    private final CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
    private final DocumentRepository documentRepository = Mockito.mock(DocumentRepository.class);
    private final DocumentAuthorizationService documentAuthorizationService = Mockito.mock(DocumentAuthorizationService.class);
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor = Mockito.mock(JwtHandshakeInterceptor.class);
    private final UserPrincipalHandshakeHandler userPrincipalHandshakeHandler = Mockito.mock(UserPrincipalHandshakeHandler.class);
    private final WebSocketConfig webSocketConfig = new WebSocketConfig(
            currentUserProvider,
            documentRepository,
            documentAuthorizationService,
            jwtHandshakeInterceptor,
            userPrincipalHandshakeHandler
    );

    @ParameterizedTest
    @ValueSource(strings = {"sessions", "presence", "operations"})
    void authorizeSubscriptionRejectsProtectedTopicWhenActorCannotReadDocument(String topicSuffix) {
        UUID documentId = UUID.randomUUID();
        User actor = actor();
        Document document = document(documentId, actor);
        DocumentAccessDeniedException accessDeniedException = new DocumentAccessDeniedException(documentId, actor.getId());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/documents/" + documentId + "/" + topicSuffix);
        accessor.setSessionAttributes(new HashMap<>(Map.of("user", actor)));

        when(currentUserProvider.requireCurrentUser(accessor)).thenReturn(actor);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
        doThrow(accessDeniedException).when(documentAuthorizationService).assertCanRead(document, actor);

        assertThatThrownBy(() -> webSocketConfig.authorizeSubscription(accessor))
                .isEqualTo(accessDeniedException);
    }

    @Test
    void authorizeSubscriptionIgnoresUnprotectedDestinations() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/documents/" + UUID.randomUUID() + "/unknown");

        webSocketConfig.authorizeSubscription(accessor);

        verifyNoInteractions(currentUserProvider, documentRepository, documentAuthorizationService);
    }

    private User actor() {
        return User.builder()
                .id(UUID.randomUUID())
                .username("actor")
                .email("actor@example.com")
                .passwordHash("hash")
                .build();
    }

    private Document document(UUID documentId, User owner) {
        return Document.builder()
                .id(documentId)
                .title("Doc")
                .content("Hello")
                .visibility(DocumentVisibility.PRIVATE)
                .owner(owner)
                .currentVersion(1L)
                .build();
    }
}
