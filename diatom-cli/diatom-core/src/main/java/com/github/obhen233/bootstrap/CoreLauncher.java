package com.github.obhen233.bootstrap;

import com.github.obhen233.CoreApp;

/**
 * Dedicated launcher for the core App to avoid class name ambiguity.
 * This class exists in diatom-core (under bootstrap package to avoid conflicts)
 * and provides an unambiguous entry point to the core CoreApp.main().
 *
 * The problem: when custom App calls CoreLauncher.launch(), it must delegate to
 * the core application class. The core class was renamed from App to CoreApp to
 * avoid classpath ambiguity with the custom App class (both share the same
 * package but custom-current.jar is first in the classpath).
 */
public class CoreLauncher {

    /**
     * Launch the core CoreApp.main() unambiguously.
     */
    public static void launch(String[] args) {
        CoreApp.main(args);
    }
}
