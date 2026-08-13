package com.github.obhen233.cli.execute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Formats an {@link ExecuteResult} into a specific output format.
 * <p>
 * Supported formats: text, json, xml, html, md (markdown), bin.
 */
public interface OutputFormatter {

    /** Format the execute result as a byte array. */
    byte[] format(ExecuteResult result);

    /** Get the MIME content type for this formatter. */
    String getContentType();

    /** Get the charset used by this formatter's output. */
    default Charset getCharset() {
        return StandardCharsets.UTF_8;
    }

    // ==================== Factory ====================

    static OutputFormatter forFormat(String formatName, Charset encoding) {
        if (formatName == null || "text".equalsIgnoreCase(formatName)) {
            return new TextFormatter(encoding);
        }
        switch (formatName.toLowerCase()) {
            case "json":     return new JsonFormatter(encoding);
            case "xml":      return new XmlFormatter(encoding);
            case "html":     return new HtmlFormatter(encoding);
            case "bin":      return new BinaryFormatter(encoding);
            case "md":
            case "markdown": return new MarkdownFormatter(encoding);
            default:
                LoggerFactory.getLogger(OutputFormatter.class).warn(
                    "Unknown output format: {}, falling back to text", formatName);
                return new TextFormatter(encoding);
        }
    }

    // ==================== Built-in Implementations ====================

    /** Plain text — returns the response field as-is, with task_id marker. */
    class TextFormatter implements OutputFormatter {
        private final Charset charset;
        TextFormatter(Charset charset) { this.charset = charset; }
        @Override public byte[] format(ExecuteResult r) {
            StringBuilder sb = new StringBuilder();
            if (r.getTaskId() != null) {
                sb.append("{{task_id:").append(r.getTaskId()).append("}}").append('\n');
            }
            String text = r.getResponse();
            if (text == null) text = r.getError() != null ? "Error: " + r.getError() : "";
            sb.append(text);
            return sb.toString().getBytes(charset);
        }
        @Override public String getContentType() { return "text/plain; charset=" + charset.name(); }
        @Override public Charset getCharset() { return charset; }
    }

    /** JSON — serializes the full ExecuteResult (Jackson). */
    class JsonFormatter implements OutputFormatter {
        private static final Logger log = LoggerFactory.getLogger(JsonFormatter.class);
        private final Charset charset;
        JsonFormatter(Charset charset) { this.charset = charset; }
        @Override public byte[] format(ExecuteResult r) {
            try {
                String json = JsonUtils.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(r);
                return json.getBytes(charset);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize ExecuteResult to JSON", e);
                return ("{\"status\":\"ERROR\",\"error\":\"Serialization failed: " +
                    e.getMessage() + "\"}").getBytes(charset);
            }
        }
        @Override public String getContentType() { return "application/json; charset=" + charset.name(); }
        @Override public Charset getCharset() { return charset; }
    }

    /** XML — simple XML wrapper (not a full schema, just structured tags). */
    class XmlFormatter implements OutputFormatter {
        private final Charset charset;
        XmlFormatter(Charset charset) { this.charset = charset; }
        @Override public byte[] format(ExecuteResult r) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("<?xml version=\"1.0\" encoding=\"").append(charset.name()).append("\"?>\n");
            sb.append("<result>\n");
            sb.append("  <status>").append(escapeXml(r.getStatus())).append("</status>\n");
            if (r.getTaskId() != null) {
                sb.append("  <task_id>").append(escapeXml(r.getTaskId())).append("</task_id>\n");
            }
            if (r.getResponse() != null) {
                sb.append("  <response><![CDATA[").append(r.getResponse()).append("]]></response>\n");
            }
            if (r.getError() != null) {
                sb.append("  <error>").append(escapeXml(r.getError())).append("</error>\n");
            }
            if (r.getTokenUsage() != null) {
                ExecuteResult.TokenUsage tu = r.getTokenUsage();
                sb.append("  <token_usage>\n");
                sb.append("    <prompt_tokens>").append(tu.getPromptTokens()).append("</prompt_tokens>\n");
                sb.append("    <completion_tokens>").append(tu.getCompletionTokens()).append("</completion_tokens>\n");
                sb.append("    <total_tokens>").append(tu.getTotalTokens()).append("</total_tokens>\n");
                sb.append("  </token_usage>\n");
            }
            if (r.getFileChanges() != null && !r.getFileChanges().isEmpty()) {
                sb.append("  <file_changes>\n");
                appendXmlList(sb, "files_created", "file", r.getFileChanges().getFilesCreated());
                appendXmlList(sb, "files_modified", "file", r.getFileChanges().getFilesModified());
                appendXmlList(sb, "files_deleted", "file", r.getFileChanges().getFilesDeleted());
                sb.append("  </file_changes>\n");
            }
            sb.append("</result>\n");
            return sb.toString().getBytes(charset);
        }
        private void appendXmlList(StringBuilder sb, String tag, String itemTag, java.util.List<String> items) {
            if (items != null && !items.isEmpty()) {
                sb.append("    <").append(tag).append(">\n");
                for (String item : items) {
                    sb.append("      <").append(itemTag).append(">")
                      .append(escapeXml(item)).append("</").append(itemTag).append(">\n");
                }
                sb.append("    </").append(tag).append(">\n");
            }
        }
        private String escapeXml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&apos;");
        }
        @Override public String getContentType() { return "application/xml; charset=" + charset.name(); }
        @Override public Charset getCharset() { return charset; }
    }

    /** HTML — minimal HTML document wrapping the response. */
    class HtmlFormatter implements OutputFormatter {
        private final Charset charset;
        HtmlFormatter(Charset charset) { this.charset = charset; }
        @Override public byte[] format(ExecuteResult r) {
            String title = r.getStatus() != null ? r.getStatus() : "Result";
            String body = r.getResponse() != null ? escapeHtml(r.getResponse()) : "";
            String error = r.getError() != null ? escapeHtml(r.getError()) : "";
            String html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n"
                + "<meta charset=\"" + charset.name() + "\">\n"
                + "<title>" + title + "</title>\n"
                + "<style>body{font-family:sans-serif;margin:2em;}"
                + ".error{color:red;}.token{color:gray;font-size:small;}"
                + ".taskid{color:blue;font-size:small;}</style>\n"
                + "</head>\n<body>\n"
                + "<h1>" + title + "</h1>\n"
                + (r.getError() != null ? "<p class=\"error\">" + error + "</p>\n" : "")
                + (r.getTaskId() != null ? "<p class=\"taskid\">Task ID: " + escapeHtml(r.getTaskId()) + "</p>\n" : "")
                + "<pre>" + body + "</pre>\n"
                + (r.getTokenUsage() != null ? "<p class=\"token\">Tokens: "
                    + r.getTokenUsage().getTotalTokens() + "</p>\n" : "")
                + "</body>\n</html>\n";
            return html.getBytes(charset);
        }
        private String escapeHtml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
        @Override public String getContentType() { return "text/html; charset=" + charset.name(); }
        @Override public Charset getCharset() { return charset; }
    }

    /** Markdown — renders ExecuteResult as a Markdown document. */
    class MarkdownFormatter implements OutputFormatter {
        private final Charset charset;
        MarkdownFormatter(Charset charset) { this.charset = charset; }
        @Override public byte[] format(ExecuteResult r) {
            StringBuilder sb = new StringBuilder(256);
            // Status heading
            sb.append("# ").append(escapeMd(r.getStatus())).append("\n\n");

            // Task ID
            if (r.getTaskId() != null) {
                sb.append("**Task ID:** `").append(escapeMd(r.getTaskId())).append("`\n\n");
            }

            // Error
            if (r.getError() != null) {
                sb.append("> **Error:** ").append(escapeMd(r.getError())).append("\n\n");
            }

            // Response body
            if (r.getResponse() != null && !r.getResponse().isEmpty()) {
                sb.append(r.getResponse()).append("\n\n");
            }

            // Token usage
            if (r.getTokenUsage() != null) {
                ExecuteResult.TokenUsage tu = r.getTokenUsage();
                sb.append("---\n\n");
                sb.append("### Token Usage\n\n");
                sb.append("| Metric | Count |\n");
                sb.append("|--------|------:|\n");
                sb.append("| Prompt Tokens | ").append(tu.getPromptTokens()).append(" |\n");
                sb.append("| Completion Tokens | ").append(tu.getCompletionTokens()).append(" |\n");
                sb.append("| Total Tokens | ").append(tu.getTotalTokens()).append(" |\n");
                sb.append("\n");
            }

            // File changes
            if (r.getFileChanges() != null && !r.getFileChanges().isEmpty()) {
                ExecuteResult.FileChanges fc = r.getFileChanges();
                StringBuilder filesSb = new StringBuilder();
                appendMdFileList(filesSb, "Created", fc.getFilesCreated());
                appendMdFileList(filesSb, "Modified", fc.getFilesModified());
                appendMdFileList(filesSb, "Deleted", fc.getFilesDeleted());
                if (filesSb.length() > 0) {
                    sb.append("### File Changes\n\n");
                    sb.append(filesSb);
                }
            }

            return sb.toString().getBytes(charset);
        }
        private void appendMdFileList(StringBuilder sb, String label, java.util.List<String> files) {
            if (files != null && !files.isEmpty()) {
                sb.append("**").append(label).append(":**\n\n");
                for (String f : files) {
                    sb.append("- `").append(escapeMd(f)).append("`\n");
                }
                sb.append("\n");
            }
        }
        private String escapeMd(String s) {
            if (s == null) return "";
            // Escape pipe and backtick in inline code context; for safety, escape < too
            return s.replace("\\", "\\\\").replace("`", "\\`");
        }
        @Override public String getContentType() { return "text/markdown; charset=" + charset.name(); }
        @Override public Charset getCharset() { return charset; }
    }

    /** Binary — raw bytes of the response field. */
    class BinaryFormatter implements OutputFormatter {
        private final Charset charset;
        BinaryFormatter(Charset charset) { this.charset = charset; }
        @Override public byte[] format(ExecuteResult r) {
            String text = r.getResponse();
            if (text == null) text = r.getError() != null ? r.getError() : "";
            return text.getBytes(charset);
        }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public Charset getCharset() { return charset; }
    }
}
