package com.github.obhen233.core.pipeline;

import java.util.List;

/**
 * Utility for joining multiple pipeline commands into a single shell command string.
 * <p>
 * Commands are normally joined with {@code &&} so the pipeline aborts on failure.
 * If a command already ends with a shell control operator such as {@code &} or {@code ;},
 * using {@code &&} would produce invalid shell syntax (e.g. {@code cmd & && echo}).
 * In that case the joiner falls back to {@code ;} to preserve sequential semantics while
 * keeping the resulting command valid.
 * </p>
 */
public final class CommandJoiner {

    private CommandJoiner() {}

    /**
     * Join a list of commands. Uses {@code &&} unless any command already ends with
     * a control operator ({@code &}, {@code ;}, {@code |}), in which case {@code ;} is used
     * (but not after a command that already ends with a control operator, since the operator
     * itself already terminates the command — e.g. {@code cmd & ; next} is a bash syntax error).
     *
     * @param commands the commands to join
     * @return the joined command string, or {@code null} if the list is null or empty
     */
    public static String join(List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return null;
        }
        String delimiter = hasControlOperator(commands) ? " ; " : " && ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commands.size(); i++) {
            String cmd = commands.get(i);
            if (cmd == null) continue;
            if (i > 0) {
                // If the previous command ends with a control operator (&, ;, |),
                // the delimiter is not needed — the operator already terminates it.
                String prev = commands.get(i - 1);
                if (prev != null && !endsWithControlOperator(prev.trim())) {
                    sb.append(delimiter);
                } else {
                    sb.append(' ');
                }
            }
            sb.append(cmd);
        }
        return sb.toString();
    }

    private static boolean endsWithControlOperator(String s) {
        return s.endsWith("&") || s.endsWith(";") || s.endsWith("|");
    }

    /**
     * Check whether any command already contains a shell control operator that makes
     * {@code &&} joining invalid.
     */
    private static boolean hasControlOperator(List<String> commands) {
        for (String cmd : commands) {
            if (cmd == null) continue;
            if (endsWithControlOperator(cmd.trim())) {
                return true;
            }
        }
        return false;
    }
}
