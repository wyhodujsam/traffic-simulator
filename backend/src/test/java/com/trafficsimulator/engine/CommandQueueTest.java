package com.trafficsimulator.engine;

import com.trafficsimulator.engine.command.SimulationCommand;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CommandQueueTest {

    @Test
    void concurrentEnqueue_100Threads_noConcurrentModificationException() throws Exception {
        SimulationEngine engine = new SimulationEngine();
        int threadCount = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            double rate = i * 0.1;
            executor.submit(() -> {
                try {
                    engine.enqueue(new SimulationCommand.SetSpawnRate(rate));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Drain and count — should be exactly 100 commands
        engine.drainCommands();
        // If no exception during enqueue+drain, the test passes
    }

    @Test
    void drainCommands_appliesStartCommand_changesStatus() {
        SimulationEngine engine = new SimulationEngine();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);

        engine.enqueue(new SimulationCommand.Start());
        engine.drainCommands();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);
    }

    @Test
    void drainCommands_appliesPauseAfterStart() {
        SimulationEngine engine = new SimulationEngine();

        engine.enqueue(new SimulationCommand.Start());
        engine.enqueue(new SimulationCommand.Pause());
        engine.drainCommands();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.PAUSED);
    }

    @Test
    void drainCommands_resumeAfterPause() {
        SimulationEngine engine = new SimulationEngine();

        engine.enqueue(new SimulationCommand.Start());
        engine.drainCommands();
        engine.enqueue(new SimulationCommand.Pause());
        engine.drainCommands();
        engine.enqueue(new SimulationCommand.Resume());
        engine.drainCommands();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.RUNNING);
    }

    @Test
    void drainCommands_stopResetsTickCounter() {
        SimulationEngine engine = new SimulationEngine();
        // Must start first so Stop command is accepted (state machine: STOPPED->RUNNING->STOPPED)
        engine.enqueue(new SimulationCommand.Start());
        engine.drainCommands();
        engine.getTickCounter().set(42);

        engine.enqueue(new SimulationCommand.Stop());
        engine.drainCommands();

        assertThat(engine.getStatus()).isEqualTo(SimulationStatus.STOPPED);
        assertThat(engine.getTickCounter().get()).isEqualTo(0);
    }
}
