package com.github.obhen233.core.tool.builtin;

import org.junit.Test;

import static org.junit.Assert.*;

public class JarManagerTest {

    @Test
    public void testAddPomDependencyInsertsBeforeDependenciesEnd() {
        String pom = "<project>\n" +
                "    <dependencies>\n" +
                "    </dependencies>\n" +
                "</project>";

        String updated = JarManager.addOrUpdatePomDependency(pom, "org.example", "example-lib", "1.2.3");

        assertTrue(updated.contains("<groupId>org.example</groupId>"));
        assertTrue(updated.contains("<artifactId>example-lib</artifactId>"));
        assertTrue(updated.contains("<version>1.2.3</version>"));
        assertTrue(updated.indexOf("<artifactId>example-lib</artifactId>") < updated.indexOf("</dependencies>"));
    }

    @Test
    public void testAddPomDependencyAvoidsDuplicates() {
        String pom = "<project>\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>org.example</groupId>\n" +
                "            <artifactId>example-lib</artifactId>\n" +
                "            <version>1.2.3</version>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n" +
                "</project>";

        String updated = JarManager.addOrUpdatePomDependency(pom, "org.example", "example-lib", "9.9.9");

        assertEquals(pom, updated);
    }
}
