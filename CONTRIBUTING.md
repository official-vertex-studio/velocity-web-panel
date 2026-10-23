# Contributing to Vertex Velocity Web Panel

Thank you for your interest in contributing to Vertex Velocity Web Panel.

## Before you start

- Read the project `README.md` and `LICENSE`.
- Check existing issues and pull requests before opening a duplicate.
- For security vulnerabilities, **do not** open a public issue. Follow `SECURITY.md`.
- Keep changes focused and explain the problem they solve.

## Development requirements

Use the Java and Maven versions specified by the project README and `pom.xml`.

Before submitting a pull request, run the checks available in the project, for example:

```bash
mvn clean verify
```

If the build or tests cannot be run in your environment, say so in the pull request. Do not claim that code was tested when it was not.

## Pull request guidelines

Include:

1. A clear description of the change and its motivation.
2. The relevant issue number, if one exists.
3. Build/test commands run and their actual results.
4. Configuration or documentation updates, where applicable.
5. Screenshots or logs for UI changes, with secrets and personal data removed.

Keep formatting consistent with the surrounding code. Avoid unrelated refactors in bug-fix pull requests.

## Licensing and attribution

By submitting a contribution, you agree that your contribution is distributed under the project's `LICENSE` (GPL-3.0-only, unless the project explicitly states otherwise for a specific file). You must have the right to submit the code and must identify third-party code or assets and their licenses.

Do not submit copied code, assets, or documentation unless their license permits the intended use and the required notices are included.

## Communication

Be respectful and constructive. Maintainers may request changes or close contributions that are out of scope, unsafe, unmaintainable, or insufficiently documented.
