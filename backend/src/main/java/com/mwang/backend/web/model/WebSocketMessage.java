package com.mwang.backend.web.model;

import lombok.*;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebSocketMessage {

    public enum MessageType {
        JOIN,
        LEAVE,
        CURSOR_POSITION,
        TYPING,
        IDLE
    }

    private MessageType type;
    private String username;
    private Map<String, Object> payload;
}
