# `lib/` — library modules

One sub-directory per library artifact. Library modules:

- Produce a plain jar.
- Have **no** `mainClass` and are not directly runnable.
- Are consumed by other library modules or by `clients/` modules.
- Inherit from the project parent pom (`../../pom.xml`).

## Adding a new library module

1. Create `lib/<artifact-id>/` with a `pom.xml` whose `<parent>` points
   at `../../pom.xml` and whose `<artifactId>` matches the directory
   name.
2. Add the directory to the `<modules>` list of every relevant profile
   in the parent pom (`dev`, `main`, `complete`).
3. Add a `<dependency>` entry for the new module in the parent
   `<dependencyManagement>` so consumers don't need to declare a
   version.
4. If the module should contribute to the aggregate coverage report,
   list it in `coverage/pom.xml`.

See `cumba-oss-cdisc-report-json/` for a small reference module.
