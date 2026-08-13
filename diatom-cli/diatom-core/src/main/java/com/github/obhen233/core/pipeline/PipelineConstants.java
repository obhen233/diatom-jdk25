package com.github.obhen233.core.pipeline;

/**
 * Shared constants for pipeline variable keys, action types, and status indicators.
 */
public final class PipelineConstants {

    private PipelineConstants() {}

    // ========== Status Indicators ==========
    public static final String CHECK  = "\u2713";
    public static final String CROSS  = "\u2717";

    // ========== Built-in Variable Keys ==========
    public static final String VAR_PROJECT_DIR   = "PROJECT_DIR";
    public static final String VAR_PROJECT_NAME  = "PROJECT_NAME";
    public static final String VAR_WORKSPACE_DIR = "WORKSPACE_DIR";
    public static final String VAR_TIMESTAMP     = "timestamp";

    // ========== Cluster Strategy Constants ==========
    public static final String STRATEGY_ALL     = "all";
    public static final String STRATEGY_ROLLING = "rolling";
    public static final String STRATEGY_CANARY  = "canary";

    // ========== Health Check Type Constants ==========
    public static final String HEALTH_CHECK_HTTP    = "http";
    public static final String HEALTH_CHECK_TCP     = "tcp";
    public static final String HEALTH_CHECK_COMMAND = "command";
    public static final String HEALTH_CHECK_NONE    = "none";

    // ========== SSH Variable Keys ==========
    public static final String VAR_SSH_USER           = "SSH_USER";
    public static final String VAR_SSH_PASSWORD       = "SSH_PASSWORD";
    public static final String VAR_SSH_KEY_PATH       = "SSH_KEY_PATH";
    public static final String VAR_STRICT_HOST_KEY_CHECK = "STRICT_HOST_KEY_CHECK";

    // ========== Docker Variable Keys ==========
    public static final String VAR_DOCKER_USER       = "DOCKER_USER";
    public static final String VAR_DOCKER_PASSWORD   = "DOCKER_PASSWORD";
    public static final String VAR_DOCKER_REGISTRY   = "DOCKER_REGISTRY";

    // ========== Maven Variable Keys ==========
    public static final String VAR_MAVEN_SETTINGS       = "MAVEN_SETTINGS";
    public static final String VAR_MAVEN_USER_SETTINGS  = "MAVEN_USER_SETTINGS";
    public static final String VAR_MAVEN_LOCAL_REPO     = "MAVEN_LOCAL_REPO";
    public static final String VAR_MAVEN_OPTS           = "MAVEN_OPTS";
    public static final String VAR_MAVEN_OFFLINE        = "MAVEN_OFFLINE";

    // ========== Gradle Variable Keys ==========
    public static final String VAR_GRADLE_BUILD_FILE    = "GRADLE_BUILD_FILE";
    public static final String VAR_GRADLE_PROJECT_PROPS = "GRADLE_PROJECT_PROPS";
    public static final String VAR_GRADLE_NO_DAEMON     = "GRADLE_NO_DAEMON";
    public static final String VAR_GRADLE_PARALLEL      = "GRADLE_PARALLEL";
    public static final String VAR_GRADLE_NO_CACHE      = "GRADLE_NO_CACHE";
    public static final String VAR_GRADLE_STACKTRACE    = "GRADLE_STACKTRACE";
    public static final String VAR_GRADLE_REFRESH_DEPS  = "GRADLE_REFRESH_DEPS";
    public static final String VAR_GRADLE_USER_HOME     = "GRADLE_USER_HOME";

    // ========== K8s Variable Keys ==========
    public static final String VAR_KUBECONFIG_PATH = "KUBECONFIG_PATH";
    public static final String VAR_K8S_NAMESPACE   = "K8S_NAMESPACE";
    public static final String VAR_K8S_CONTEXT     = "K8S_CONTEXT";

    // ========== SVN Variable Keys ==========
    public static final String VAR_SVN_USER       = "SVN_USER";
    public static final String VAR_SVN_PASSWORD   = "SVN_PASSWORD";
    public static final String VAR_SVN_TRUST_CERT = "SVN_TRUST_CERT";

    // ========== Jenkins Variable Keys ==========
    public static final String VAR_JENKINS_URL   = "JENKINS_URL";
    public static final String VAR_JENKINS_USER  = "JENKINS_USER";
    public static final String VAR_JENKINS_TOKEN = "JENKINS_TOKEN";

    // ========== Git Variable Keys ==========
    public static final String VAR_GIT_USER     = "GIT_USER";
    public static final String VAR_GIT_PASSWORD = "GIT_PASSWORD";

    // ========== Action Types ==========
    public static final String ACTION_RUN_COMMAND = "run_command";
    public static final String ACTION_SSH_COMMAND = "ssh_command";
    public static final String ACTION_DOCKER      = "docker";
    public static final String ACTION_MAVEN       = "maven";
    public static final String ACTION_GRADLE      = "gradle";
    public static final String ACTION_K8S         = "k8s";
    public static final String ACTION_JENKINS     = "jenkins";
    public static final String ACTION_SVN         = "svn";
    public static final String ACTION_GIT         = "git";

    // ========== Shell / Process ==========
    /** UTF-8 code page prefix for Windows cmd.exe */
    public static final String WIN_SHELL_PREFIX = "chcp 65001 >nul && ";
    public static final String WIN_SHELL        = "cmd.exe";
    public static final String WIN_SHELL_ARG    = "/c";
    public static final String UNIX_SHELL       = "/bin/sh";
    public static final String UNIX_SHELL_ARG   = "-c";

    // ========== Default Output Limits ==========
    public static final long OUTPUT_LIMIT_DEFAULT = 512 * 1024;       // 512KB
    public static final long OUTPUT_LIMIT_BUILD   = 1024 * 1024;      // 1MB

    // ========== Default Timeouts (minutes) ==========
    public static final long TIMEOUT_DEFAULT  = 5;
    public static final long TIMEOUT_DOCKER   = 10;
    public static final long TIMEOUT_K8S      = 10;
    public static final long TIMEOUT_BUILD    = 30;
    public static final long TIMEOUT_SSH      = 30;
}
