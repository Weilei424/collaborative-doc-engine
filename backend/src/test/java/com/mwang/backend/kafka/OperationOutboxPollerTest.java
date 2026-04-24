package com.mwang.backend.kafka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperationOutboxPollerTest {

    private OutboxRowProcessor rowProcessor;
    private OperationOutboxPoller poller;

    @BeforeEach
    void setUp() {
        rowProcessor = mock(OutboxRowProcessor.class);
        poller = new OperationOutboxPoller(rowProcessor);
        ReflectionTestUtils.setField(poller, "batchSize", 5);
    }

    @Test
    void poll_stopsImmediatelyWhenOutboxIsEmpty() {
        when(rowProcessor.claimAndProcess(any(Instant.class))).thenReturn(false);

        poller.poll();

        verify(rowProcessor, times(1)).claimAndProcess(any(Instant.class));
    }

    @Test
    void poll_stopsAfterFirstFalse() {
        when(rowProcessor.claimAndProcess(any(Instant.class)))
                .thenReturn(true, true, false);

        poller.poll();

        verify(rowProcessor, times(3)).claimAndProcess(any(Instant.class));
    }

    @Test
    void poll_stopsAtBatchSizeEvenWhenRowsRemain() {
        when(rowProcessor.claimAndProcess(any(Instant.class))).thenReturn(true);

        poller.poll();

        verify(rowProcessor, times(5)).claimAndProcess(any(Instant.class));
    }
}
