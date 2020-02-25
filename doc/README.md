# Developer Guide Module

> :mag_right: If you are looking for the online documentation, it has
> [moved](https://deepsymmetry.org/afterglow/guide/) off of
> GitHub to become easier to read and navigate.

Afterglow now uses [Antora](https://antora.org) to build its Developer
Guide. this folder hosts the documentation module and playbooks used
to build it. `embedded.yml` is used to create the self-hosted version
which is served out of Afterglow itself, so it can be used even
without an Internet connection, and `netlify.yml` is used to build the
[online version](https://afterglow-guide.deepsymmetry.org/) that is
managed by [netlify](https://www.netlify.com).

The Leiningen project in the root of this repository automatically
invokes Antora to build the embedded version as an early build step.
The online version, which will grow to support multiple released
versions of Afterglow, is built automatically by netlify whenever
changes are pushed to the relevant branches on GitHub. The netlify
build command is:

    npm i @antora/cli antora-site-generator-lunr && \
    DOCSEARCH_ENABLED=true DOCSEARCH_ENGINE=lunr $(npm bin)/antora \
    --fetch doc/netlify.yml --generator antora-site-generator-lunr && \
    lein with-profile netlify do codox, resource

And the publish directory is `doc/build/site`.

An older workflow to build the documentation site manually for hosting
on the Deep Symmetry web sie by running the following commands from
the project root, which did not include lunr search:

    antora --fetch doc/ds.yml
    rsync -avz doc/build/site/ slice:/var/www/ds/afterglow/guide
