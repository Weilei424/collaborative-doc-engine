package com.mwang.backend.config;

import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MdcChannelInterceptor implements ChannelInterceptor {

    private static final Pattern DOCUMENT_DESTINATION_PATTERN = Pattern.compile(
            "^/app/documents/([0-9a-fA-F-]{36})/.*$"
    );

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
        if (accessor != null) {
            // Read userId from the STOMP Principal set by UserPrincipalHandshakeHandler
            if (accessor.getUser() != null) {
                String userId = accessor.getUser().getName();
                if (userId != null) {
                    MDC.put("userId", userId);
                }
            }
            String destination = accessor.getDestination();
            if (destination != null) {
                Matcher matcher = DOCUMENT_DESTINATION_PATTERN.matcher(destination);
                if (matcher.matches()) {
                    MDC.put("documentId", matcher.group(1));
                }
            }
        }
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel,
                                    boolean sent, Exception ex) {
        MDC.remove("userId");
        MDC.remove("documentId");
    }
}
