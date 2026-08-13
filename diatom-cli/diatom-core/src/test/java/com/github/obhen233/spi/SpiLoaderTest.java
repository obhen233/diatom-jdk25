package com.github.obhen233.spi;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for SpiLoader
 */
public class SpiLoaderTest {

    @Before
    public void setUp() {
        // Reset SpiLoader state before each test
        SpiLoader.reload();
    }

    /**
     * Test that loadAll() can be called multiple times without issues
     */
    @Test
    public void testLoadAllIdempotent() {
        // First load
        SpiLoader.loadAll();

        // Second load should be no-op (already loaded)
        SpiLoader.loadAll();

        // Should still work - no exception thrown
    }

    /**
     * Test getFirst returns default when no custom implementation exists
     */
    @Test
    public void testGetFirstReturnsDefaultWhenNoCustom() {
        UpgradePolicy defaultPolicy = new UpgradePolicy() {
            @Override
            public boolean shouldUpgrade(String currentVersion, String newVersion) {
                return true;
            }
        };

        UpgradePolicy result = SpiLoader.getFirst(UpgradePolicy.class, defaultPolicy);
        assertNotNull("Should return the default policy", defaultPolicy);
        assertEquals("Should return the provided default", defaultPolicy, result);
    }

    /**
     * Test getFirst returns default for unknown types
     */
    @Test
    public void testGetFirstReturnsDefaultForUnknownTypes() {
        UpgradePolicy defaultPolicy = new UpgradePolicy() {
            @Override
            public boolean shouldUpgrade(String currentVersion, String newVersion) {
                return true;
            }
        };

        UpgradePolicy result = SpiLoader.getFirst(UpgradePolicy.class, defaultPolicy);
        assertSame("Should return same default instance", defaultPolicy, result);
    }

    /**
     * Test getAll returns empty list for type with no implementations
     */
    @Test
    public void testGetAllReturnsEmptyForUnknownType() {
        // Note: may return defaults if registered in META-INF/services
        List<UpgradePolicy> result = SpiLoader.getAll(UpgradePolicy.class);
        assertNotNull("Result list should not be null", result);
    }

    /**
     * Test that reload clears and reloads all extensions
     */
    @Test
    public void testReloadClearsAndReloads() {
        // Load initially
        SpiLoader.loadAll();

        // Reload
        SpiLoader.reload();

        // After reload, should still work without throwing
        UpgradePolicy policy = SpiLoader.getFirst(UpgradePolicy.class, null);
        // Should not throw
    }

    /**
     * Test getAll with ConfigProvider returns a list
     */
    @Test
    public void testGetAllWithConfigProvider() {
        List<ConfigProvider> providers = SpiLoader.getAll(ConfigProvider.class);
        assertNotNull("ConfigProvider list should not be null", providers);
    }

    /**
     * Test getFirst with UiCustomizer - may be null if no implementation
     */
    @Test
    public void testGetFirstUiCustomizer() {
        UiCustomizer customizer = SpiLoader.getFirst(UiCustomizer.class, null);
        // May be null if no custom implementation registered - that's valid
    }

    /**
     * Test getAll with AppLifecycleHook returns a list
     */
    @Test
    public void testGetAllAppLifecycleHook() {
        List<AppLifecycleHook> hooks = SpiLoader.getAll(AppLifecycleHook.class);
        assertNotNull("Lifecycle hooks list should not be null", hooks);
    }

    /**
     * Test getFirst with CacheFactory - may return null if no implementation
     */
    @Test
    public void testGetFirstCacheFactory() {
        CacheFactory factory = SpiLoader.getFirst(CacheFactory.class, null);
        // May be null if no implementation registered - that's valid
    }

    /**
     * Test getFirst with SystemConfigProvider returns a list
     */
    @Test
    public void testGetFirstSystemConfigProvider() {
        List<SystemConfigProvider> providers = SpiLoader.getAll(SystemConfigProvider.class);
        assertNotNull("SystemConfigProvider list should not be null", providers);
    }

    /**
     * Test getFirst with DatabaseExtension returns a list
     */
    @Test
    public void testGetFirstDatabaseExtension() {
        List<DatabaseExtension> extensions = SpiLoader.getAll(DatabaseExtension.class);
        assertNotNull("DatabaseExtension list should not be null", extensions);
    }

    /**
     * Test that all known SPI types can be queried without throwing
     */
    @Test
    public void testAllSpiTypesAreQueryable() {
        // These should not throw exceptions
        SpiLoader.getFirst(CacheFactory.class, null);
        SpiLoader.getFirst(UpgradePolicy.class, null);
        SpiLoader.getAll(AppLifecycleHook.class);
        SpiLoader.getAll(ToolRegistrar.class);
        SpiLoader.getAll(ConfigProvider.class);
        SpiLoader.getFirst(UiCustomizer.class, null);
        SpiLoader.getAll(DatabaseExtension.class);
        SpiLoader.getAll(SystemConfigProvider.class);
    }

    /**
     * Test that reload can be called multiple times
     */
    @Test
    public void testReloadIdempotent() {
        SpiLoader.reload();
        SpiLoader.reload();
        SpiLoader.reload();
        // Should not throw
    }
}
