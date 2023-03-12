#!/usr/bin/env bash

# This script is run by GitHub Actions to build the
# Antora site hosting the developer guide.

set -e  # Exit if any command fails.

# There is no point in doing this if we lack the SSH key to publish the guide.
if [ "$GUIDE_SSH_KEY" != "" ]; then

    # Set up node dependencies; probably redundant thanks to main workflow, but just in case...
    npm install

    # Build the cloud version of the documentation site. Note that the API docs are already
    # built in, from the stage of building the uberjar.
    npm run hosted-docs

    # Make sure there are no broken links in the versions we care about.
    curl https://htmltest.wjdp.uk | bash
    bin/htmltest

    # Publish the user guide to the right place on the Deep Symmetry web server.
    rsync -avz doc/build/site/ guides@deepsymmetry.org:/var/www/guides/afterglow/

else
    echo "No SSH key present, not building user guide."
fi
