package com.trafficsimulator.controller;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SimulationControllerMapTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void listMapsReturnsMapInfoDtos() throws Exception {
        mockMvc.perform(get("/api/maps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(5))))
                .andExpect(jsonPath("$[*].id", hasItem("straight-road")))
                .andExpect(jsonPath("$[*].name", everyItem(notNullValue())));
    }

    @Test
    void listMapsIncludesDescription() throws Exception {
        mockMvc.perform(get("/api/maps"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$[?(@.id == 'phantom-jam-corridor')].description",
                                hasItem(containsStringIgnoringCase("stop-and-go"))));
    }

    @Test
    void postLoadMapCommandAccepted() throws Exception {
        mockMvc.perform(
                        post("/api/command")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\": \"LOAD_MAP\", \"mapId\": \"straight-road\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void postLoadMapUnknownMapIdStillAccepted() throws Exception {
        mockMvc.perform(
                        post("/api/command")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\": \"LOAD_MAP\", \"mapId\": \"nonexistent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
