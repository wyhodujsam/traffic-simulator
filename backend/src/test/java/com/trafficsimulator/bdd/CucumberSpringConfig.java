package com.trafficsimulator.bdd;

import static org.mockito.Mockito.mock;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.trafficsimulator.scheduler.TickEmitter;

import io.cucumber.spring.CucumberContextConfiguration;

@CucumberContextConfiguration
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.task.scheduling.pool.size=0",
            "simulation.tick-emitter.enabled=false"
        })
public class CucumberSpringConfig {}
