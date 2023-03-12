# Developer Guide Module

> :mag_right: If you are looking for the online documentation, it has
> [moved](https://afterglow-guide.deepsymmetry.org/) off of
> GitHub to become easier to read and navigate.

Afterglow now uses [Antora](https://antora.org) to build its Developer
Guide. this folder hosts the documentation module and playbooks used
to build it. `embedded.yml` is used to create the self-hosted version
which is served out of Afterglow itself, so it can be used even
without an Internet connection, and `github-actions.yml` is used to build the
[online version](https://afterglow-guide.deepsymmetry.org/) that is
built by GitHub Actions.

The Leiningen project in the root of this repository automatically
invokes Antora to build the embedded version as an early build step.
The online version, which will grow to support multiple released
versions of Afterglow, is built automatically whenever changes are
pushed to the relevant branches on GitHub.
