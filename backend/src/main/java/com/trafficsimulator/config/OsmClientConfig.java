package com.trafficsimulator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OsmClientConfig {

    @Value("${osm.overpass.url:https://overpass-api.de}")
    private String overpassBaseUrl;

    @Bean
    public RestClient overpassRestClient(RestClient.Builder builder) {
        return builder.baseUrl(overpassBaseUrl).build();
    }
}
