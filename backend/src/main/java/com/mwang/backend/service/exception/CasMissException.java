package com.mwang.backend.service.exception;

public class CasMissException extends RuntimeException {
    public CasMissException() {
        super("CAS version mismatch — another writer advanced currentVersion");
    }
}
