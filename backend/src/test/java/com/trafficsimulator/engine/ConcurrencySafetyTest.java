package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.engine.command.SimulationCommand;

/**
 * Concurrency safety tests for ReentrantReadWriteLock synchronization. Verifies that concurrent
 * command dispatch and tick-like operations do not cause ConcurrentModificationException or
 * deadlocks.
 */
class ConcurrencySafetyTest {

    /** Creates a SimulationEngine with a CommandDispatcher wired in (no Spring context needed). */
    private static SimulationEngine createEngine() {
        SimulationEngine engine = new SimulationEngine(null, null);
        CommandDispatcher dispatcher = new CommandDispatcher(engine, null, null, null);
        engine.setCommandDispatcher(dispatcher);
        return engine;
    }

    /**
     * 100 threads send commands while drainCommands() runs concurrently. No
     * ConcurrentModificationException should occur thanks to writeLock.
     */
    @Test
    void concurrentCommandsDuringDrain_noException() throws InterruptedException {
        SimulationEngine engine = createEngine();

        // Start simulation so commands affect RUNNING state
        engine.enqueue(new SimulationCommand.Start());
        engine.drainCommands();

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger errors = new AtomicInteger(0);

        // Spawn 100 command-sending threads that also trigger drainCommands
        for (int i = 0; i < 100; i++) {
            final int idx = i;
            executor.submit(
                    () -> {
                        try {
                            // Mix of different commands
                            if (idx % 3 == 0) {
                                engine.enqueue(new SimulationCommand.SetSpawnRate(1.0 + idx * 0.1));
                            } else if (idx % 3 == 1) {
                                engine.enqueue(new SimulationCommand.SetSpeedMultiplier(1.0));
                            } else {
                                engine.enqueue(new SimulationCommand.SetMaxSpeed(33.33));
                            }
                            // Simultaneously drain commands (simulates tick thread contention)
                            if (idx % 5 == 0) {
                                engine.drainCommands();
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, errors.get(), "Expected zero concurrency errors");
    }

    /**
     * Verify lock doesn't cause deadlock — drainCommands completes within timeout even under heavy
     * concurrent load.
     */
    @Test
    void drainCompletesUnderLoad_noDeadlock() {
        SimulationEngine engine = createEngine();

        engine.enqueue(new SimulationCommand.Start());
        engine.drainCommands();

        // Run 50 concurrent command submissions
        ExecutorService executor = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> engine.enqueue(new SimulationCommand.SetSpawnRate(1.0)));
        }

        // All drainCommands calls must complete within 5 seconds (no deadlock)
        long start = System.currentTimeMillis();
        for (int i = 0; i < 50; i++) {
            engine.drainCommands();
        }
        long elapsed = System.currentTimeMillis() - start;

        executor.shutdown();
        assertTrue(
                elapsed < 5000, "50 drain calls should complete within 5s, took " + elapsed + "ms");
    }

    /**
     * Verify that writeLock is reentrant — drainCommandsUnlocked works correctly when called within
     * a writeLock context (simulating TickEmitter pattern).
     */
    @Test
    void writeLockReentrancy_drainUnlockedInsideWriteLock() {
        SimulationEngine engine = createEngine();

        engine.enqueue(new SimulationCommand.Start());

        // Simulate TickEmitter pattern: acquire writeLock, then drainCommandsUnlocked
        engine.writeLock().lock();
        try {
            engine.drainCommandsUnlocked();
        } finally {
            engine.writeLock().unlock();
        }

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);
    }

    /**
     * Heavy concurrent stress test: many threads enqueue + drain simultaneously. Verifies the lock
     * correctly serializes access without data corruption.
     */
    @Test
    void heavyConcurrentStress_mixedOperations() throws InterruptedException {
        SimulationEngine engine = createEngine();

        engine.enqueue(new SimulationCommand.Start());
        engine.drainCommands();

        int threadCount = 200;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(20);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(
                    () -> {
                        try {
                            startGate.await(5, TimeUnit.SECONDS);

                            // Half enqueue, half drain — maximum contention
                            if (idx % 2 == 0) {
                                engine.enqueue(new SimulationCommand.SetSpawnRate(idx * 0.01));
                            } else {
                                // Simulate tick thread holding writeLock + drainUnlocked
                                engine.writeLock().lock();
                                try {
                                    engine.drainCommandsUnlocked();
                                } finally {
                                    engine.writeLock().unlock();
                                }
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
        }

        // Release all threads at once for maximum contention
        startGate.countDown();
        done.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Final drain
        engine.drainCommands();

        assertEquals(0, errors.get(), "Expected zero concurrency errors under stress");
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);
    }
}
