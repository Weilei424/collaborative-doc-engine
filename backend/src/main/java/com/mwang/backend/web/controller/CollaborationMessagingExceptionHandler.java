package com.mwang.backend.web.controller;

import com.mwang.backend.service.exception.CollaborationSessionAccessDeniedException;
import com.mwang.backend.service.exception.CollaborationSessionNotFoundException;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.InvalidCollaborationRequestException;
import com.mwang.backend.service.exception.InvalidPresenceUpdateException;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import com.mwang.backend.web.model.ApiErrorResponse;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;

@ControllerAdvice(annotations = Controller.class)
public class CollaborationMessagingExceptionHandler {

    @MessageExceptionHandler(UserContextRequiredException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleUserContextRequired(UserContextRequiredException ex) {
        return ApiErrorResponse.of("USER_CONTEXT_REQUIRED", ex.getMessage());
    }

    @MessageExceptionHandler(UserNotFoundException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleUserNotFound(UserNotFoundException ex) {
        return ApiErrorResponse.of("USER_NOT_FOUND", ex.getMessage());
    }

    @MessageExceptionHandler(DocumentNotFoundException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleDocumentNotFound(DocumentNotFoundException ex) {
        return ApiErrorResponse.of("DOCUMENT_NOT_FOUND", ex.getMessage());
    }

    @MessageExceptionHandler(DocumentAccessDeniedException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleDocumentAccessDenied(DocumentAccessDeniedException ex) {
        return ApiErrorResponse.of("DOCUMENT_ACCESS_DENIED", ex.getMessage());
    }

    @MessageExceptionHandler(CollaborationSessionNotFoundException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleSessionNotFound(CollaborationSessionNotFoundException ex) {
        return ApiErrorResponse.of("COLLABORATION_SESSION_NOT_FOUND", ex.getMessage());
    }

    @MessageExceptionHandler(CollaborationSessionAccessDeniedException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleSessionAccessDenied(CollaborationSessionAccessDeniedException ex) {
        return ApiErrorResponse.of("COLLABORATION_SESSION_ACCESS_DENIED", ex.getMessage());
    }

    @MessageExceptionHandler(InvalidPresenceUpdateException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleInvalidPresenceUpdate(InvalidPresenceUpdateException ex) {
        return ApiErrorResponse.of("INVALID_PRESENCE_UPDATE", ex.getMessage());
    }

    @MessageExceptionHandler(InvalidCollaborationRequestException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleInvalidCollaborationRequest(InvalidCollaborationRequestException ex) {
        return ApiErrorResponse.of("INVALID_COLLABORATION_REQUEST", ex.getMessage());
    }

    @MessageExceptionHandler(MessageConversionException.class)
    @SendToUser("/queue/errors")
    public ApiErrorResponse handleMalformedCollaborationPayload(MessageConversionException ex) {
        return ApiErrorResponse.of("INVALID_COLLABORATION_REQUEST", "Malformed collaboration message payload");
    }
}