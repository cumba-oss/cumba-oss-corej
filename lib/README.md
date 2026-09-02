# `lib/` — library modules

One sub-directory per library artifact. Library modules:

- Produce a plain jar.
- Have **no** `mainClass` and are not directly runnable.
- Are consumed by other library modules, or by downstream repositories.
- Inherit from the project parent pom (`../../pom.xml`).

## Adding a new library module

1. Create `lib/<artifact-id>/` with a `pom.xml` whose `<parent>` points
   at `../../pom.xml` and whose `<artifactId>` matches the directory
   name.
2. Add the directory to the `<modules>` list in the parent pom.
3. Add a `<dependency>` entry for the new module in the parent
   `<dependencyManagement>` so consumers don't need to declare a
   version.
4. If the module should contribute to the aggregate coverage report,
   list it in `coverage/pom.xml`.
5. **Give the pom `<name>`, `<description>`, `<url>`, `<packaging>` and
   `<inceptionYear>`.** These are published to Maven Central. A missing
   `<name>` is *not* inherited from the parent and Central rejects the
   artifact — and it fails at publish time, after the tag is spent.
6. **Write the pom, README and javadoc against the published
   coordinates** (`net.cumba` / `cumba-oss-*`), not the names this code
   carries in its upstream monorepo. A `<description>` reaches Central
   and is immutable; a README's `<dependency>` block is the first thing
   a consumer copies. Both have shipped wrong before.

See `cumba-oss-cdisc-report-json/` for a small reference module.
