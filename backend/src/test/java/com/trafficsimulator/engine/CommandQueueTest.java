package com.trafficsimulator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.trafficsimulator.engine.command.SimulationCommand;

class CommandQueueTest {

    /** Creates a SimulationEngine with a CommandDispatcher wired in (no Spring context needed). */
    private static SimulationEngine createEngine() {
        SimulationEngine engine = new SimulationEngine(null, null);
        CommandDispatcher dispatcher = new CommandDispatcher(engine, null, null, null);
        engine.setCommandDispatcher(dispatcher);
        return engine;
    }

    @Test
    void concurrentEnqueue_100Threads_noConcurrentModificationException()
            throws InterruptedException {
        SimulationEngine engine = createEngine();
        int threadCount = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            double rate = i * 0.1;
            executor.submit(
                    () -> {
                        try {
                            engine.enqueue(new SimulationCommand.SetSpawnRate(rate));
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Drain all enqueued commands — verifies no ConcurrentModificationException thrown
        engine.drainCommands();
        // All threads completed and drain succeeded — engine is in a valid state
        assertThat(engine.getStatus()).isNotNull();
    }

    @Test
    void concurrentEnqueue_1000Threads_duringActiveTick_noConcurrentModificationException()
            throws InterruptedException {
        SimulationEngine engine = createEngine();
        // Start the simulation so commands are processed against RUNNING state
        engine.enqueue(new SimulationCommand.Start(null));
        engine.drainCommands();

        int threadCount = 1000;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(50);

        for (int i = 0; i < threadCount; i++) {
            double rate = i * 0.01;
            executor.submit(
                    () -> {
                        try {
                            startGate.await(5, TimeUnit.SECONDS);
                            engine.enqueue(new SimulationCommand.SetSpawnRate(rate));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
        }

        // Release all threads simultaneously for maximum contention
        startGate.countDown();

        // Simulate tick thread draining commands concurrently
        Thread tickThread =
                new Thread(
                        () -> {
                            for (int i = 0; i < 100; i++) {
                                engine.drainCommands();
                                Thread.yield();
                            }
                        });
        tickThread.start();

        done.await(10, TimeUnit.SECONDS);
        tickThread.join(5000);
        executor.shutdown();

        // Final drain to catch any stragglers
        engine.drainCommands();

        // If no ConcurrentModificationException was thrown, the test passes
        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);
    }

    @Test
    void drainCommands_appliesStartCommand_changesStatus() {
        SimulationEngine engine = createEngine();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);

        engine.enqueue(new SimulationCommand.Start(null));
        engine.drainCommands();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);
    }

    @Test
    void drainCommands_appliesPauseAfterStart() {
        SimulationEngine engine = createEngine();

        engine.enqueue(new SimulationCommand.Start(null));
        engine.enqueue(new SimulationCommand.Pause());
        engine.drainCommands();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.PAUSED);
    }

    @Test
    void drainCommands_resumeAfterPause() {
        SimulationEngine engine = createEngine();

        engine.enqueue(new SimulationCommand.Start(null));
        engine.drainCommands();
        engine.enqueue(new SimulationCommand.Pause());
        engine.drainCommands();
        engine.enqueue(new SimulationCommand.Resume());
        engine.drainCommands();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);
    }

    @Test
    void drainCommands_stopResetsTickCounter() {
        SimulationEngine engine = createEngine();
        // Must start first so Stop command is accepted (state machine: STOPPED->RUNNING->STOPPED)
        engine.enqueue(new SimulationCommand.Start(null));
        engine.drainCommands();
        engine.getTickCounter().set(42);

        engine.enqueue(new SimulationCommand.Stop());
        engine.drainCommands();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);
        assertThat(engine.getTickCounter().get()).isZero();
    }
}
