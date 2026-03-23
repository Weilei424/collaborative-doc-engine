package com.mwang.backend.web.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Deprecated
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
