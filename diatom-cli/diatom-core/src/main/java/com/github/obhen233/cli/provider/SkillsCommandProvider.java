package com.github.obhen233.cli.provider;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.skill.Skill;
import com.github.obhen233.core.skill.SkillManager;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;

import java.nio.file.Path;
import java.util.List;

/**
 * Skills command provider.
 * Lists and manages skills directly without calling the LLM model.
 */
public class SkillsCommandProvider implements CoreCommandProvider {

    private ReActAgent agent;
    private static final String NEWLINE = System.lineSeparator();

    @Override
    public String getCommandName() {
        return "skills";
    }

    @Override
    public String getDescription() {
        return "{{cli.skills.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.skills.help}}";
    }

    @Override
    public void init(ReActAgent agent) {
        this.agent = agent;
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (agent == null) {
            return "ERROR {{skills.agent_not_initialized}}";
        }

        SkillManager skillManager = agent.getSkillManager();
        if (skillManager == null) {
            return "ERROR {{skills.manager_not_available}}";
        }

        String trimmed = args.trim();
        String lower = trimmed.toLowerCase();

        // skills reload — reload skills from disk
        if ("reload".equals(lower)) {
            skillManager.reload();
            agent.invalidateProjectContext();
            return "SUCCESS {{skills_reloaded}}";
        }

        // skills directories — show where skills are loaded from (debug)
        if ("directories".equals(lower)) {
            return buildDirectoriesOutput(skillManager);
        }

        // skills, skills list, or empty — list all skills
        if (lower.isEmpty() || "list".equals(lower)) {
            return buildSkillsList(skillManager);
        }

        // skills help, skills --help, skills -h
        if ("help".equals(lower) || "--help".equals(lower) || "-h".equals(lower)) {
            return getHelp();
        }

        return "ERROR {{skills.unknown_command:" . concat(trimmed).concat("}}");
    }

    private String buildSkillsList(SkillManager skillManager) {
        List<Skill> allSkills = skillManager.getSkills();
        StringBuilder sb = new StringBuilder();

        if (allSkills.isEmpty()) {
            sb.append("No skills loaded.");
        } else {
            sb.append("Available Skills (").append(allSkills.size()).append("):").append(NEWLINE);
            for (Skill skill : allSkills) {
                sb.append("  \u2022 ").append(skill.getName());
                sb.append(" (v").append(skill.getVersion() != null ? skill.getVersion() : "1").append(")");
                if (!skill.isEnabled()) {
                    sb.append(" [disabled]");
                }
                String desc = skill.getDescription();
                if (desc != null && !desc.isEmpty()) {
                    sb.append(": ").append(desc);
                }
                sb.append(NEWLINE);
            }
        }

        // Show skill directories info
        List<Path> dirs = skillManager.getSkillsDirectories();
        sb.append(NEWLINE).append("Directories:").append(NEWLINE);
        for (Path dir : dirs) {
            sb.append("  ").append(dir.toString()).append(NEWLINE);
        }

        return sb.toString();
    }

    private String buildDirectoriesOutput(SkillManager skillManager) {
        StringBuilder sb = new StringBuilder();
        sb.append("Skills Directories:").append(NEWLINE);

        Path global = skillManager.getGlobalSkillsDir();
        if (global != null) {
            sb.append("  Global: ").append(global.toString()).append(NEWLINE);
        }
        Path jar = skillManager.getJarSkillsDir();
        if (jar != null) {
            sb.append("  JAR:    ").append(jar.toString()).append(NEWLINE);
        }
        Path project = skillManager.getProjectSkillsDir();
        if (project != null) {
            sb.append("  Project:").append(project.toString()).append(NEWLINE);
        }

        sb.append(NEWLINE).append("Loaded: ").append(skillManager.getSkills().size()).append(" skills");
        return sb.toString();
    }
}
