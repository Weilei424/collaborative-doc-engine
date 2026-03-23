package com.mwang.backend.web.controller;

import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.InvalidDocumentScopeException;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import com.mwang.backend.web.model.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(UserContextRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleUserContextRequired(UserContextRequiredException ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("USER_CONTEXT_REQUIRED", ex.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("USER_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleDocumentNotFound(DocumentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of("DOCUMENT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(DocumentAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleDocumentAccessDenied(DocumentAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("DOCUMENT_ACCESS_DENIED", ex.getMessage()));
    }

    @ExceptionHandler(InvalidDocumentScopeException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidScope(InvalidDocumentScopeException ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("INVALID_SCOPE", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("VALIDATION_ERROR", "Validation failed", details));
    }
}
