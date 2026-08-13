package com.github.obhen233.core.pipeline;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PipelineRunner for triggering Jenkins jobs via HTTP API.
 * Supports the "jenkins" action type.
 *
 * Step config (in deploy.yaml):
 *   action: "jenkins"
 *   command: "build JobName"
 *   command: "build JobName/param1=value1/param2=value2"
 *   command: "build JobName -p PARAM1=val1 -p PARAM2=val2"
 *   command: "info JobName"
 *
 * Auth: Uses {{JENKINS_URL}}, {{JENKINS_USER}}, {{JENKINS_TOKEN}} from variables.
 */
public class JenkinsRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(JenkinsRunner.class);

    private final OkHttpClient httpClient;

    public JenkinsRunner() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getActionType() {
        return "jenkins";
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String command = step.getCommand();
        if (command == null || command.trim().isEmpty()) {
            callback.onError("Jenkins step '" + step.getName() + "' has no command");
            return false;
        }

        command = command.trim();
        callback.onOutput("$ jenkins " + command + "\n");

        String jenkinsUrl = variables.get("JENKINS_URL");
        String jenkinsUser = variables.get("JENKINS_USER");
        String jenkinsToken = variables.get("JENKINS_TOKEN");

        if (jenkinsUrl == null || jenkinsUrl.isEmpty()) {
            callback.onError("JENKINS_URL not configured in variables");
            return false;
        }
        if (jenkinsUser == null || jenkinsToken == null || jenkinsUser.isEmpty() || jenkinsToken.isEmpty()) {
            callback.onError("JENKINS_USER and JENKINS_TOKEN must be configured in variables");
            return false;
        }

        // Normalize URL
        if (jenkinsUrl.endsWith("/")) {
            jenkinsUrl = jenkinsUrl.substring(0, jenkinsUrl.length() - 1);
        }

        // Parse command
        int spaceIdx = command.indexOf(' ');
        String op = spaceIdx < 0 ? command.toLowerCase() : command.substring(0, spaceIdx).toLowerCase();
        String args = spaceIdx < 0 ? "" : command.substring(spaceIdx + 1).trim();

        // Build auth header (Basic Auth with API token)
        String auth = jenkinsUser + ":" + jenkinsToken;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes("UTF-8"));

        switch (op) {
            case "build":
                return handleBuild(jenkinsUrl, args, encodedAuth, callback);
            case "info":
                return handleInfo(jenkinsUrl, args, encodedAuth, callback);
            default:
                callback.onError("Unknown jenkins operation: " + op + ". Supported: build, info");
                return false;
        }
    }

    private boolean handleBuild(String jenkinsUrl, String args, String encodedAuth,
                                 PipelineCallback callback) {
        try {
            // Parse job name and parameters
            String jobName;
            String paramPart = "";

            int paramIdx = args.indexOf(" -p ");
            if (paramIdx >= 0) {
                jobName = args.substring(0, paramIdx).trim();
                paramPart = args.substring(paramIdx + 4).trim();
            } else {
                jobName = args.trim();
            }

            // Build URL
            String url = jenkinsUrl + "/job/" + encodeJobName(jobName);

            if (paramPart.isEmpty()) {
                // Build without parameters
                url += "/build";
                callback.onOutput("  Triggering build: " + jobName + "\n");
            } else {
                // Build with parameters
                url += "/buildWithParameters?" + paramPart.replace(" -p ", "&");
                callback.onOutput("  Triggering parameterized build: " + jobName + "\n");
                callback.onOutput("  Parameters: " + paramPart + "\n");
            }

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(null, ""))
                    .header("Authorization", "Basic " + encodedAuth)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                int statusCode = response.code();
                if (statusCode == 201 || statusCode == 200) {
                    String location = response.header("Location");
                    if (location != null) {
                        callback.onOutput("  Build queued: " + location + "\n");
                    } else {
                        callback.onOutput("  Build triggered successfully\n");
                    }
                    return true;
                } else {
                    callback.onOutput("\u2717 Jenkins returned HTTP " + statusCode + "\n");
                    return false;
                }
            }
        } catch (Exception e) {
            callback.onOutput("\u2717 Jenkins build failed: " + e.getMessage() + "\n");
            logger.error("Jenkins build failed", e);
            return false;
        }
    }

    private boolean handleInfo(String jenkinsUrl, String args, String encodedAuth,
                                PipelineCallback callback) {
        try {
            String url = jenkinsUrl + "/job/" + encodeJobName(args.trim()) + "/api/json";

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Basic " + encodedAuth)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.body() == null) {
                    callback.onOutput("  Empty response from Jenkins\n");
                    return false;
                }
                String body = response.body().string();

                // Simple JSON parsing without Jackson (to keep dependency light)
                // Parse displayName, url, color, lastBuild
                callback.onOutput("  Job info retrieved (HTTP " + response.code() + ")\n");
                callback.onOutput("  Response: " + body.substring(0, Math.min(body.length(), 500)) + "\n");
                return true;
            }
        } catch (Exception e) {
            callback.onOutput("\u2717 Jenkins info failed: " + e.getMessage() + "\n");
            logger.error("Jenkins info failed", e);
            return false;
        }
    }

    /**
     * Encode job name for URL path.
     */
    private String encodeJobName(String jobName) {
        if (jobName.contains("/")) {
            String[] parts = jobName.split("/");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                sb.append("/job/").append(part);
            }
            return sb.substring(1);
        }
        return jobName;
    }
}
