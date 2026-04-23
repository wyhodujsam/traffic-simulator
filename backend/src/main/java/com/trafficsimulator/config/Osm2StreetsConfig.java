package com.trafficsimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the osm2streets Rust binary subprocess (Phase 24).
 *
 * <p>Mirrors the shape of {@link ClaudeCliConfig}: a binary path on disk, a hard invocation
 * timeout, and a working directory for transient artefacts. The binary itself is built and
 * provenanced by Phase 24 Plan 24-01 ({@code backend/bin/osm2streets-cli-linux-x64}); this bean is
 * the integration seam that {@code Osm2StreetsService} reads from so the path and timeout can be
 * overridden in tests without touching the service code.
 */
@Component
@ConfigurationProperties(prefix = "osm2streets")
public class Osm2StreetsConfig {

    /** Path to the osm2streets-cli binary. Defaults to {@code backend/bin/osm2streets-cli-linux-x64}. */
    private String binaryPath = "backend/bin/osm2streets-cli-linux-x64";

    /** Hard timeout in seconds for a single osm2streets-cli invocation (24-CONTEXT D10). */
    private int timeoutSeconds = 30;

    /** Directory for temp files (reserved for future use by the mapper in Plan 24-04). */
    private String tempDir = System.getProperty("java.io.tmpdir");

    public String getBinaryPath() {
        return binaryPath;
    }

    public void setBinaryPath(String binaryPath) {
        this.binaryPath = binaryPath;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getTempDir() {
        return tempDir;
    }

    public void setTempDir(String tempDir) {
        this.tempDir = tempDir;
    }
}
