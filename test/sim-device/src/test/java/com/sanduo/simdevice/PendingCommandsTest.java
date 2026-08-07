package com.sanduo.simdevice;

import com.sanduo.device.CommandMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingCommandsTest {

    private static CommandMessage cmd(String id) {
        return new CommandMessage().setCommandId(id).setCommand("exec").setParams(java.util.Map.of());
    }

    @Test
    void emptyInitially() {
        PendingCommands p = new PendingCommands();
        assertTrue(p.isEmpty());
        assertEquals(0, p.pendingCount());
        assertNull(p.latest());
    }

    @Test
    void latestReturnsMostRecent() {
        PendingCommands p = new PendingCommands();
        p.add(cmd("c1"));
        p.add(cmd("c2"));
        p.add(cmd("c3"));
        assertEquals("c3", p.latest().commandId());
        assertEquals(3, p.pendingCount());
    }

    @Test
    void removeById() {
        PendingCommands p = new PendingCommands();
        p.add(cmd("c1"));
        p.add(cmd("c2"));
        CommandMessage removed = p.remove("c2");
        assertEquals("c2", removed.commandId());
        assertEquals(1, p.pendingCount());
        assertNull(p.remove("nope"));
        assertEquals(1, p.pendingCount());
    }

    @Test
    void concurrentAddIsSafe() throws InterruptedException {
        PendingCommands p = new PendingCommands();
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        p.add(cmd("t" + tid + "-" + i));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(threads * perThread, p.pendingCount());
    }
}
