package com.github.obhen233.adapter.internal;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC Driver wrapper that bridges classloader boundaries for plugin-loaded
 * JDBC drivers.
 *
 * <p>When a JDBC driver is loaded from a plugin JAR (via {@link PluginClassLoader}),
 * {@link DriverManager#getConnection(String, Properties)} may fail because it
 * checks whether the caller's classloader can load the driver class. This wrapper
 * is registered with {@link DriverManager} from the system classloader context,
 * and delegates actual work to the plugin-loaded driver while switching the TCCL
 * to the plugin's classloader for each {@link #connect(String, Properties)} call.</p>
 */
public class DelegatingDriver implements Driver {

    private final Driver delegate;

    public DelegatingDriver(Driver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(delegate.getClass().getClassLoader());
            return delegate.connect(url, info);
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return delegate.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    /**
     * Get the underlying plugin-loaded driver.
     */
    public Driver getDelegate() {
        return delegate;
    }
}
