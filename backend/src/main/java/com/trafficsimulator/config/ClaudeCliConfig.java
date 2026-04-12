package com.trafficsimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration properties for the Claude CLI integration (AI vision path). */
@Component
@ConfigurationProperties(prefix = "claude.cli")
public class ClaudeCliConfig {

    /** Path to the Claude CLI binary. Defaults to "claude" (assumes it is on PATH). */
    private String path = "claude";

    /** Timeout in seconds for a single Claude CLI invocation. */
    private int timeoutSeconds = 30;

    /** Directory for temporary image files passed to Claude CLI. */
    private String tempDir = System.getProperty("java.io.tmpdir");

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
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
