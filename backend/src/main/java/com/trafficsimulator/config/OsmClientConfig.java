package com.trafficsimulator.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class OsmClientConfig {

    @Bean
    public RestClient overpassRestClient(
            RestClient.Builder builder,
            @Value("${osm.overpass.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${osm.overpass.read-timeout-ms:45000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.requestFactory(factory).build();
    }
}
