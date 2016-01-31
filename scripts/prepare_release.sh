#!/bin/bash

# See if we were given a version tag.
if [[ $# -eq 0 ]] ; then
   echo "No version supplied, switching back to development on master branch"
   version="\/master\/"
   replace="\/v[0-9]*\.[0-9]*\.[0-9]*\/"
   docHost="\/rawgit.com\/"
   replaceHost="\/cdn.rawgit.com\/"
elif [[ $# -eq 1 ]] ; then
    if [[ $1 =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ; then
        version="\/$1\/"
        replace="\/master\/"
        docHost="\/cdn.rawgit.com\/"
        replaceHost="\/rawgit.com\/"
        echo "Preparing files to cut release with tag $1"
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
sourceswap="s/\/github.com\/brunchboy\/afterglow\/blob${replace}/\/github.com\/brunchboy\/afterglow\/blob${version}/g"
mv project.clj project.clj.old
sed "${sourceswap}" project.clj.old > project.clj
rm -f project.clj.old


# Update API documentation links
mv README.md README.md.old
docswap="s/${replaceHost}brunchboy\/afterglow${replace}api-doc/${docHost}brunchboy\/afterglow${version}api-doc/g"
sed "${docswap}" README.md.old > README.md
rm -f README.md.old

for fl in doc/*.adoc; do
    mv $fl $fl.old
    sed "${docswap}" $fl.old > $fl.middle
    sed "${sourceswap}" $fl.middle > $fl
    rm -f $fl.old $fl.middle
done
