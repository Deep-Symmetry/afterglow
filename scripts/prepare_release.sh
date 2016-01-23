#!/bin/bash

# See if we were given a version tag.
if [[ $# -eq 0 ]] ; then
   echo "No version supplied, switching back to development on master branch"
   version="master"
   replace="v[0-9]*\.[0-9]*\.[0-9]*"
elif [[ $# -eq 1 ]] ; then
    if [[ $1 =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ; then
        version="$1"
        replace="master"
        echo "Preparing files to cut release with tag $version"
    else
        echo "Invalid version: $1 (must be of the form v0.0.0)"
        exit 1
    fi
else
    echo "Usage: $0 [version]"
    echo "       When supplied, version must be of the form v0.0.0"
    exit 1
fi

# Update codox source link
mv project.clj project.clj.old
sed "s/https:\/\/github.com\/brunchboy\/afterglow\/blob\/${replace}/https:\/\/github.com\/brunchboy\/afterglow\/blob\/${version}/g" project.clj.old > project.clj
rm -f project.clj.old


# Update API documentation links
mv README.md README.md.old
sed "s/brunchboy.github.io\/afterglow\/api-doc\/${replace}/brunchboy.github.io\/afterglow\/api-doc\/${version}/g" README.md.old > README.md
rm -f README.md.old

for fl in doc/*.adoc; do
    mv $fl $fl.old
    sed "s/brunchboy.github.io\/afterglow\/api-doc\/${replace}/brunchboy.github.io\/afterglow\/api-doc\/${version}/g" $fl.old > $fl.middle
    sed "s/github.com\/brunchboy\/afterglow\/blob\/${replace}/github.com\/brunchboy\/afterglow\/blob\/${version}/g" $fl.middle > $fl
    rm -f $fl.old $fl.middle
done
