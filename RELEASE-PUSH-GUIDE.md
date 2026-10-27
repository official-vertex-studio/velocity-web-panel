# Vertex Velocity Web Panel 4.0.0 — build, push and release

> Do not publish a release until `mvn clean verify` succeeds and the shaded JAR starts on a test Velocity proxy. A Git tag does not prove the plugin is bug-free.

## 1. Open the repository

Open the extracted project root in IntelliJ IDEA. The folder must contain `pom.xml`, `src/`, and `.github/`.

## 2. Confirm Java 25 and build

In the IntelliJ terminal:

```bash
java -version
mvn -version
mvn --batch-mode clean verify
```

Both Java and Maven must be available; Java must be version 25. The expected artifact is:

`target/vertex-velocity-web-panel-4.0.0.jar`

If the build fails, do not continue to tagging. Fix the first Maven error and rerun the command.

## 3. Check Git status and commit

Run these commands from the root of your existing Git clone (not a freshly extracted ZIP):

```bash
git status
git branch --show-current
git remote -v
```

Ensure you are on the intended branch and `origin` points to `official-vertex-studio/velocity-web-panel`. Review the diff, then:

```bash
git add .
git commit -m "Release: Vertex Velocity Web Panel 4.0.0"
git push origin main
```

If your default branch is not `main`, replace `main` with the actual branch name. If Git says there is nothing to commit, that is okay only if the intended 4.0.0 changes are already committed.

## 4. Ensure the tag does not already exist

```bash
git fetch --tags
git tag --list v4.0.0
```

If `v4.0.0` is already listed, stop and inspect it; do not force-move a published tag. If no output appears, create and push the annotated tag:

```bash
git tag -a v4.0.0 -m "Vertex Velocity Web Panel 4.0.0"
git push origin v4.0.0
```

## 5. Watch GitHub Actions

Open the repository's **Actions** tab. The release workflow checks that the tag version matches `pom.xml`, runs `mvn clean verify`, checks for the expected JAR, and only then creates the GitHub Release. If the workflow fails, fix the cause and use a new patch version/tag (for example `v4.0.1`) rather than reusing or force-moving `v4.0.0`.

## 6. Test before replacing production

Install the resulting JAR on a test Velocity instance using Java 25. Check startup logs, open the configured HTTP panel address, test login and key pages/API endpoints, then check shutdown/restart. Back up the current plugin JAR and data directory before any production replacement.
