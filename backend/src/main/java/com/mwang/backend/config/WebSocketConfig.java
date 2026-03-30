package com.mwang.backend.config;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.CurrentUserProvider;
import com.mwang.backend.service.DocumentAuthorizationService;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@EnableWebSocketMessageBroker
@Configuration
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Pattern PROTECTED_DOCUMENT_TOPIC_PATTERN = Pattern.compile(
            "^/topic/documents/([0-9a-fA-F-]{36})/(sessions|presence|operations|access/[^/]+)$"
    );

    private final CurrentUserProvider currentUserProvider;
    private final DocumentRepository documentRepository;
    private final DocumentAuthorizationService documentAuthorizationService;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final UserPrincipalHandshakeHandler userPrincipalHandshakeHandler;

    public WebSocketConfig(
            CurrentUserProvider currentUserProvider,
            DocumentRepository documentRepository,
            DocumentAuthorizationService documentAuthorizationService,
            JwtHandshakeInterceptor jwtHandshakeInterceptor,
            UserPrincipalHandshakeHandler userPrincipalHandshakeHandler) {
        this.currentUserProvider = currentUserProvider;
        this.documentRepository = documentRepository;
        this.documentAuthorizationService = documentAuthorizationService;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
        this.userPrincipalHandshakeHandler = userPrincipalHandshakeHandler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(jwtHandshakeInterceptor)
                .setHandshakeHandler(userPrincipalHandshakeHandler)
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/topic", "/queue");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new MdcChannelInterceptor(), new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null) {
                    authorizeSubscription(accessor);
                }
                return message;
            }
        });
    }

    void authorizeSubscription(StompHeaderAccessor accessor) {
        if (accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return;
        }

        UUID documentId = protectedTopicDocumentId(accessor.getDestination());
        if (documentId == null) {
            return;
        }

        User actor = currentUserProvider.requireCurrentUser(accessor);
        Document document = documentRepository.findDetailedById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        documentAuthorizationService.assertCanRead(document, actor);

        // Access-revocation topics are private per user: only the addressed user
        // may subscribe, even if another reader has document-read permission.
        String accessUserId = accessTopicUserId(accessor.getDestination());
        if (accessUserId != null && !actor.getId().toString().equals(accessUserId)) {
            throw new DocumentAccessDeniedException(documentId, actor.getId());
        }
    }

    private UUID protectedTopicDocumentId(String destination) {
        if (destination == null) {
            return null;
        }

        Matcher matcher = PROTECTED_DOCUMENT_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return null;
        }

        return UUID.fromString(matcher.group(1));
    }

    private static final Pattern ACCESS_TOPIC_PATTERN = Pattern.compile(
            "^/topic/documents/[0-9a-fA-F-]{36}/access/(.+)$"
    );

    /** Returns the userId segment from an access-revocation topic, or null for other topics. */
    private String accessTopicUserId(String destination) {
        if (destination == null) {
            return null;
        }
        Matcher matcher = ACCESS_TOPIC_PATTERN.matcher(destination);
        return matcher.matches() ? matcher.group(1) : null;
    }

}
