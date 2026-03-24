package com.mwang.backend.config;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.DocumentAuthorizationService;
import com.mwang.backend.service.HeaderCurrentUserProvider;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@EnableWebSocketMessageBroker
@Configuration
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Pattern PROTECTED_DOCUMENT_TOPIC_PATTERN = Pattern.compile(
            "^/topic/documents/([0-9a-fA-F-]{36})/(sessions|presence)$"
    );

    private final HeaderCurrentUserProvider currentUserProvider;
    private final DocumentRepository documentRepository;
    private final DocumentAuthorizationService documentAuthorizationService;

    public WebSocketConfig(
            HeaderCurrentUserProvider currentUserProvider,
            DocumentRepository documentRepository,
            DocumentAuthorizationService documentAuthorizationService) {
        this.currentUserProvider = currentUserProvider;
        this.documentRepository = documentRepository;
        this.documentAuthorizationService = documentAuthorizationService;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/topic", "/queue");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null) {
                    bindCurrentUserHeader(accessor);
                    authorizeSubscription(accessor);
                }
                return message;
            }
        });
    }

    void bindCurrentUserHeader(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (trimToNull(sessionAttributeValue(sessionAttributes)) != null) {
            return;
        }

        String rawUserId = trimToNull(accessor.getFirstNativeHeader(HeaderCurrentUserProvider.USER_ID_HEADER));
        if (rawUserId == null) {
            return;
        }

        if (sessionAttributes == null) {
            sessionAttributes = new HashMap<>();
            accessor.setSessionAttributes(sessionAttributes);
        }
        sessionAttributes.put(HeaderCurrentUserProvider.USER_ID_HEADER, rawUserId);
    }

    void authorizeSubscription(StompHeaderAccessor accessor) {
        if (accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return;
        }

        UUID documentId = protectedTopicDocumentId(accessor.getDestination());
        if (documentId == null) {
            return;
        }

        User actor = currentUserProvider.requireCurrentUserFromSessionAttributes(accessor.getSessionAttributes());
        Document document = documentRepository.findDetailedById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        documentAuthorizationService.assertCanRead(document, actor);
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

    private String sessionAttributeValue(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) {
            return null;
        }

        Object rawUserId = sessionAttributes.get(HeaderCurrentUserProvider.USER_ID_HEADER);
        return rawUserId == null ? null : rawUserId.toString();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}