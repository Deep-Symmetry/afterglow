#!/usr/bin/env bash

# This script is run by Netlify continuous integration to build the
# Antora site hosting the developer guide.

npx antora --fetch doc/netlify.yml

lein with-profile netlify do codox, resource
