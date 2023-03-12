#!/bin/bash


# This script is run by GitHub Actions to build the cross-platform uberjar.

# If this is a full release, tweak the project to generate the correct API doc source links.
if [[ $release_snapshot != "false" ]]
then
    # Update codox source link
    prefix="\/github.com\/Deep-Symmetry\/afterglow\/blob\/"
    sourceswap="s/${prefix}main/${prefix}${git_version}/g"
    mv project.clj project.clj.old
    sed "${sourceswap}" project.clj.old > project.clj
    rm -f project.clj.old
fi

# Make sure Antora is installed
npm install

# Now that the project has been tweaked if needed, do the actual build
lein uberjar

# Rename the output jar to where we want it.
mv target/afterglow.jar "./$uberjar_name"
