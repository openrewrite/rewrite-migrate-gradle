<p align="center">
  <a href="https://docs.openrewrite.org">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-dark.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg">
      <img alt="OpenRewrite Logo" src="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg" width='600px'>
    </picture>
  </a>
</p>

<div align="center">
  <h1>rewrite-migrate-gradle</h1>
</div>

<div align="center">

<!-- Keep the gap above this line, otherwise they won't render correctly! -->
[![ci](https://github.com/moderneinc/rewrite-migrate-gradle/actions/workflows/ci.yml/badge.svg)](https://github.com/moderneinc/rewrite-migrate-gradle/actions/workflows/ci.yml)
[![Contributing Guide](https://img.shields.io/badge/Contributing-Guide-informational)](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md)
</div>

### What is this?

This is a template repository for creating new OpenRewrite modules. It provides a starting point with the necessary structure and configuration to help you get started quickly.

### To do after generating your new module
- [ ] Change the above description to reflect your module
- [ ] [Add to rewrite-recipe-bom](https://github.com/openrewrite/rewrite-recipe-bom/commit/c13a444e37b6545c7e56c575e8846d063dda3d6f), or [moderne-recipe-bom](https://github.com/moderneinc/moderne-recipe-bom/blob/main/build.gradle.kts) if proprietary; the recipe docs pick up modules from there
- [ ] Add to the Code Genome Project's [members.txt](https://github.com/moderneinc/codegenomeproject/blob/main/ci/src/main/resources/io/moderne/codegenome/ci/members.txt), so that the nightly release train builds and releases it, and ask a Code Genome Project admin to add the same line to production's membership
- [ ] Add to [dependency-vulnerability-reports](https://github.com/moderneinc/dependency-vulnerability-reports/blob/main/.github/workflows/openrewrite-vulnerability-analysis.yml)
- [ ] Deploy to the Moderne Platform as recipe module, [here](https://app.moderne.io/recipes/deploy)
- [ ] [Add the Moderne Release Notes Generator](https://github.com/organizations/moderneinc/settings/installations/88151565) to the project, such that release notes get included in moderne-docs
- [ ] Remove this to do list

## Contributing

We appreciate all types of contributions. See the [contributing guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md) for detailed instructions on how to get started.

### Licensing

For more information about licensing, please visit our [licensing page](https://docs.openrewrite.org/licensing/openrewrite-licensing).
