package com.mwang.backend.service;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.User;

public interface DocumentAuthorizationService {
    void assertCanRead(Document document, User actor);
    void assertCanWrite(Document document, User actor);
    void assertOwner(Document document, User actor);
    void assertCanAdmin(Document document, User actor);
    String resolveEffectivePermission(Document document, User actor);
}
