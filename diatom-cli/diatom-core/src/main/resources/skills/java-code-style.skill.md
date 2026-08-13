---
name: java-code-style
description: Java code style and best practices for all Java files
version: 1.0.0
allowed-tools: Read, Write, Edit, Bash(mvn:*), Bash(git:status,diff)
triggers:
  - "*.java"
  - "修改Java"
  - "新增Java类"
---

# Java Code Style

## Naming Conventions
- **Classes**: PascalCase, e.g., `UserService`, `OrderController`
- **Methods**: camelCase, e.g., `getUserById`, `calculateTotal`
- **Constants**: UPPER_SNAKE_CASE, e.g., `MAX_RETRY_COUNT`
- **Packages**: lowercase, e.g., `com.example.project`

## Code Structure
- One public class per file
- Order: fields -> constructors -> public methods -> private methods
- Use 4 spaces for indentation

## Comments
- Public methods MUST have Javadoc explaining purpose, params, return values
- Complex logic SHOULD have inline comments explaining intent
- TODO comments must include issue reference

## Exception Handling
- Never swallow exceptions without logging
- Use specific exception types, not generic `Exception`
- Business exceptions should use custom `BusinessException`

## Dependencies
- Prefer constructor injection over field injection
- Avoid `*` wildcard imports
