package com.mwang.backend.web.model;

import java.util.UUID;

public record JoinSessionRequest(UUID clientSessionHint) {
}
