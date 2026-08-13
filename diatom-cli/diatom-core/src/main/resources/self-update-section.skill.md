---
name: self-update-section
description: Development mode tools section for diatom self-update
version: 1.0.0
---

**Core:** read-only JAR. **Custom:** editable, extends core.
**Workflow:** init_sources()->extract_sources()->edit->compile_sources()->restart_application.
**Rule:** extend via custom, never modify core. No hot-swap.
**No decompile:** NEVER use CFR/Procyon/JD-GUI/fernflower. To understand/modify diatom, use init_sources()+extract_sources() first.
**SPI metadata:** read `sources/core-spi.json` before generating custom extension code.
**Custom tools:** implement `com.github.obhen233.spi.ToolRegistrar`. In `registerTools(ToolRegistry registry)`, call `registry.scanObject(new YourTools())`. Tool methods must be annotated with `@ToolMethod` from `com.github.obhen233.core.tool.annotation.ToolMethod`.
**Java 8 only:** avoid `Map.of`, `List.of`, `var`, text blocks, records, and switch expressions. Write JSON schemas as escaped Java string concatenation.
**Dependencies:** for self-update dependencies, persist Maven dependencies in `sources/pom.xml`; `add_lib` alone is not enough unless the tool reports that it updated the source POM.
**After compile_sources:** call `restart_application` immediately. Do NOT run extra verification commands unless explicitly asked.
**Package structure:** All new source files go under `src/main/java/com/github/obhen233/custom/`. Organize by function into sub-packages — do NOT dump all files flat in `custom/` root. For example: `custom/excel/ExcelReader.java`, `custom/db/DatabaseHelper.java`, `custom/util/StringUtils.java`, `custom/tool/MyTool.java`.
