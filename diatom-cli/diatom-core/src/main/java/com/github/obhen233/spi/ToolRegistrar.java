package com.github.obhen233.spi;

import com.github.obhen233.core.tool.ToolRegistry;

/**
 * Allows custom modules to register additional tools
 * into the core tool registry.
 */
public interface ToolRegistrar {

    /**
     * Register custom tools into the given registry.
     * @param registry the tool registry to register tools into
     */
    void registerTools(ToolRegistry registry);
}
