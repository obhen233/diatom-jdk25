---
name: git-commit-style
description: Git commit message conventions
version: 1.0.0
triggers:
  - "git commit"
  - "提交代码"
---

# Git Commit Message Style

## Format
```
<type>(<scope>): <subject>

<body>

<footer>
```

## Types
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Formatting, no code change
- `refactor`: Code change, no feature/fix
- `test`: Adding tests
- `chore`: Maintenance tasks

## Rules
- Subject line: max 50 characters
- Body: max 72 characters per line
- Use imperative mood: "add feature" not "added feature"
- Reference issues: "Fixes #123"

## Examples
```
feat(auth): add OAuth2 login support

- Implement OAuth2 flow
- Add token refresh mechanism
- Update user model

Closes #456
```
