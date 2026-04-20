package com.mwang.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.domain.*;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.service.exception.DocumentAccessDeniedException;
import com.mwang.backend.service.exception.DocumentNotFoundException;
import com.mwang.backend.web.model.OperationPageResponse;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OperationHistoryServiceImplTest {

    private DocumentRepository documentRepository;
    private DocumentOperationRepository operationRepository;
    private DocumentAuthorizationService authorizationService;
    private OperationHistoryServiceImpl service;

    private User actor;
    private Document document;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        operationRepository = mock(DocumentOperationRepository.class);
        authorizationService = mock(DocumentAuthorizationService.class);
        // rateLimitPerMinute=2 so we can exhaust it quickly in tests
        service = new OperationHistoryServiceImpl(
                documentRepository, operationRepository, authorizationService,
                new ObjectMapper().findAndRegisterModules(), 2);

        documentId = UUID.randomUUID();
        actor = new User();
        actor.setId(UUID.randomUUID());

        document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(documentRepository.findDetailedById(documentId)).thenReturn(Optional.of(document));
    }

    @Test
    void returnsOpsAndHasMoreFalseWhenResultsFitInLimit() {
        DocumentOperation op = buildOp(1L);
        when(operationRepository.findPageAfterVersion(eq(documentId), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(op));

        OperationPageResponse result = service.getOperationPage(documentId, 0L, 10, actor);

        assertThat(result.operations()).hasSize(1);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.sinceVersion()).isEqualTo(0L);
        assertThat(result.documentId()).isEqualTo(documentId);
    }

    @Test
    void setsHasMoreTrueWhenRepoReturnsLimitPlusOne() {
        // Service requests limit+1; if it gets limit+1 rows, hasMore=true
        List<DocumentOperation> ops = new ArrayList<>();
        for (int i = 1; i <= 6; i++) ops.add(buildOp(i)); // limit=5, returns 6
        when(operationRepository.findPageAfterVersion(eq(documentId), eq(0L), any(Pageable.class)))
                .thenReturn(ops);

        OperationPageResponse result = service.getOperationPage(documentId, 0L, 5, actor);

        assertThat(result.operations()).hasSize(5);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void throwsDocumentAccessDeniedWhenAclDenies() {
        doThrow(new DocumentAccessDeniedException(documentId, actor.getId()))
                .when(authorizationService).assertCanRead(document, actor);

        assertThatThrownBy(() -> service.getOperationPage(documentId, 0L, 10, actor))
                .isInstanceOf(DocumentAccessDeniedException.class);
    }

    @Test
    void throwsRequestNotPermittedAfterRateLimitExhausted() {
        when(operationRepository.findPageAfterVersion(any(), anyLong(), any())).thenReturn(List.of());

        service.getOperationPage(documentId, 0L, 10, actor); // permit 1
        service.getOperationPage(documentId, 0L, 10, actor); // permit 2 — exhausted
        assertThatThrownBy(() -> service.getOperationPage(documentId, 0L, 10, actor))
                .isInstanceOf(RequestNotPermitted.class);
    }

    @Test
    void capsLimitAt2000() {
        when(operationRepository.findPageAfterVersion(any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        service.getOperationPage(documentId, 0L, 9999, actor);

        verify(operationRepository).findPageAfterVersion(
                eq(documentId), eq(0L),
                argThat(p -> p.getPageSize() == 2001)); // 2000 + 1 for hasMore detection
    }

    @Test
    void throwsDocumentNotFoundWhenDocumentAbsent() {
        UUID unknownId = UUID.randomUUID();
        when(documentRepository.findDetailedById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOperationPage(unknownId, 0L, 10, actor))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    private DocumentOperation buildOp(long serverVersion) {
        User opActor = new User();
        opActor.setId(UUID.randomUUID());
        DocumentOperation op = mock(DocumentOperation.class);
        when(op.getOperationId()).thenReturn(UUID.randomUUID());
        when(op.getServerVersion()).thenReturn(serverVersion);
        when(op.getOperationType()).thenReturn(DocumentOperationType.INSERT_TEXT);
        when(op.getPayload()).thenReturn("{\"path\":[0],\"offset\":0,\"text\":\"x\"}");
        when(op.getClientSessionId()).thenReturn("sess");
        when(op.getCreatedAt()).thenReturn(Instant.now());
        when(op.getActor()).thenReturn(opActor);
        return op;
    }
}
