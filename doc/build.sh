#!/usr/bin/env bash

# This script is run by Netlify continuous integration to build the
# Antora site hosting the DJ Link packet analysis and the devicesql
# database analysis.

npm i @antora/cli antora-site-generator-lunr
DOCSEARCH_ENABLED=true DOCSEARCH_ENGINE=lunr $(npm bin)/antora --fetch --generator antora-site-generator-lunr \
                 doc/netlify.yml

lein with-profile netlify do codox, resource
