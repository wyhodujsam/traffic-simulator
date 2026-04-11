package com.trafficsimulator.bdd;

import org.springframework.boot.test.context.SpringBootTest;

import io.cucumber.spring.CucumberContextConfiguration;

@CucumberContextConfiguration
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.task.scheduling.pool.size=0",
            "simulation.tick-emitter.enabled=false"
        })
public class CucumberSpringConfig {}
