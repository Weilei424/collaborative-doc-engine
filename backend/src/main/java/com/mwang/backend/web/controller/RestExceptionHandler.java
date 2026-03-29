package com.mwang.backend.web.controller;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.mwang.backend.service.exception.CollaboratorAlreadyExistsException;
import com.mwang.backend.service.exception.CollaboratorNotFoundException;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.service.exception.EmailAlreadyExistsException;
import com.mwang.backend.service.exception.InvalidCollaborationRequestException;
import com.mwang.backend.service.exception.InvalidCredentialsException;
import com.mwang.backend.service.exception.InvalidDocumentRequestException;
import com.mwang.backend.service.exception.InvalidDocumentScopeException;
import com.mwang.backend.service.exception.InvalidOperationException;
import com.mwang.backend.service.exception.OperationConflictException;
import com.mwang.backend.service.exception.UserContextRequiredException;
import com.mwang.backend.service.exception.UserNotFoundException;
import com.mwang.backend.service.exception.UsernameAlreadyExistsException;
import com.mwang.backend.web.model.ApiErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    @ExceptionHandler(InvalidOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOperation(InvalidOperationException ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("INVALID_OPERATION", ex.getMessage()));
    }

    @ExceptionHandler(OperationConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleOperationConflict(OperationConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of("OPERATION_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(CollaboratorNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCollaboratorNotFound(CollaboratorNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("COLLABORATOR_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(CollaboratorAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleCollaboratorAlreadyExists(CollaboratorAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("COLLABORATOR_ALREADY_EXISTS", ex.getMessage()));
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleUsernameAlreadyExists(UsernameAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("USERNAME_ALREADY_EXISTS", ex.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("EMAIL_ALREADY_EXISTS", ex.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of("INVALID_CREDENTIALS", ex.getMessage()));
    }

    @ExceptionHandler(InvalidCollaborationRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCollaborationRequest(InvalidCollaborationRequestException ex) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("INVALID_COLLABORATION_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, InvalidDocumentRequestException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleValidation(Exception ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("VALIDATION_ERROR", "Validation failed", extractDetails(ex)));
    }

    private List<String> extractDetails(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return methodArgumentNotValidException.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .toList();
        }

        if (ex instanceof ConstraintViolationException constraintViolationException) {
            return constraintViolationException.getConstraintViolations().stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .toList();
        }

        if (ex instanceof InvalidDocumentRequestException) {
            return List.of(ex.getMessage());
        }

        if (ex instanceof HttpMessageNotReadableException messageNotReadableException) {
            Throwable cause = messageNotReadableException.getMostSpecificCause();
            if (cause instanceof InvalidFormatException invalidFormatException) {
                return List.of("Invalid value '%s' for %s".formatted(invalidFormatException.getValue(), invalidFormatException.getPathReference()));
            }
            return List.of("Malformed request body");
        }

        if (ex instanceof MethodArgumentTypeMismatchException typeMismatchException) {
            return List.of("Invalid value '%s' for parameter '%s'".formatted(typeMismatchException.getValue(), typeMismatchException.getName()));
        }

        return List.of(ex.getMessage());
    }
}
