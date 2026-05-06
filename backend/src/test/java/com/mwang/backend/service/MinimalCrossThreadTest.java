package com.mwang.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

interface SimpleService {
    void doWork(String arg);
}

@ExtendWith(MockitoExtension.class)
class MinimalCrossThreadTest {

    @Mock SimpleService service;

    @Test
    void schedulerCallable_visibleToAwaitility() {
        // Stub it
        doNothing().when(service).doWork(anyString());
        
        // Call from scheduler thread
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "test-scheduler"));
        exec.schedule(() -> service.doWork("hello"), 5, TimeUnit.MILLISECONDS);
        
        // Verify with Awaitility (which runs verify() in a thread pool thread)
        await().atMost(200, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> verify(service, atLeastOnce()).doWork(anyString()));
        
        exec.shutdown();
    }
}
