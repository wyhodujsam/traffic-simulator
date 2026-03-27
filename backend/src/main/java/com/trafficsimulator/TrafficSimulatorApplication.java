package com.trafficsimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TrafficSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrafficSimulatorApplication.class, args);
    }
}
