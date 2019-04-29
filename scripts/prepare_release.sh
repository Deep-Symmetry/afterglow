#!/bin/bash

# See if we were given a version tag.
if [[ $# -eq 0 ]] ; then
   echo "No version supplied, switching back to development on master branch"
   version="\/master\/"
   replace="\/a?v[0-9]*\.[0-9]*\.[0-9]*\/"
elif [[ $# -eq 1 ]] ; then
    if [[ $1 =~ ^a?v[0-9]+\.[0-9]+\.[0-9]+$ ]] ; then
        version="\/$1\/"
        replace="\/master\/"
        echo "Preparing files to cut release with tag $1"
    else
        echo "Invalid version: $1 (must be of the form v0.0.0 or av0.0.0)"
        exit 1
    fi
else
    echo "Usage: $0 [version]"
    echo "       When supplied, version must be of the form v0.0.0 or av0.0.0"
    exit 1
fi

# Update codox source link
sourceswap="s/\/github.com\/Deep-Symmetry\/afterglow\/blob${replace}/\/github.com\/Deep-Symmetry\/afterglow\/blob${version}/g"
mv project.clj project.clj.old
sed "${sourceswap}" project.clj.old > project.clj
rm -f project.clj.old

# No longer needed, because this is now served from inside the jar!
# Update web interface documentation link
#webswap="s/\/github.com\/Deep-Symmetry\/afterglow\/tree${replace}/\/github.com\/Deep-Symmetry\/afterglow\/tree${version}/g"
#mv resources/templates/home.html resources/templates/home.html.old
#sed "${webswap}" resources/templates/home.html.old > resources/templates/home.html
#rm -f resources/templates/home.html.old
