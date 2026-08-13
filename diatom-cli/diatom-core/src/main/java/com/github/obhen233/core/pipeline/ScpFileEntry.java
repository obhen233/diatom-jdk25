package com.github.obhen233.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents a single file transfer entry in an SCP pipeline step.
 * Deserialized from the "files" list in deploy.yaml.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScpFileEntry {

    private String local;
    private String remote;

    public String getLocal() { return local; }
    public void setLocal(String local) { this.local = local; }

    public String getRemote() { return remote; }
    public void setRemote(String remote) { this.remote = remote; }
}
