package com.mwang.backend.web.model;

import lombok.Data;

@Data
public class CollaborationMessage {
    private String docId;
    private String userId;
    private String operation; // "insert", "delete", "replace"
    private String content;   // the content change
    private long timestamp;   // epoch milliseconds
}
