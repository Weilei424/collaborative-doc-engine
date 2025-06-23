package com.mwang.backend.web.model;

import lombok.Data;

import java.util.UUID;

@Data
public class CollaborationMessage {
    private String documentId;
    private UUID userId;
    private String username;
    private String operation; // "insert", "delete", "replace"
    private String content;   // the content change
    private long timestamp;   // epoch milliseconds
}
