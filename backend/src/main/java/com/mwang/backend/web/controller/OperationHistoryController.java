package com.mwang.backend.web.controller;

import com.mwang.backend.domain.User;
import com.mwang.backend.service.OperationHistoryService;
import com.mwang.backend.web.model.OperationPageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Validated
public class OperationHistoryController {

    private final OperationHistoryService operationHistoryService;

    @GetMapping("/{id}/operations")
    public ResponseEntity<OperationPageResponse> getOperations(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) long sinceVersion,
            @RequestParam(defaultValue = "500") @Min(1) @Max(2000) int limit,
            @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(
                operationHistoryService.getOperationPage(id, sinceVersion, limit, actor));
    }
}
