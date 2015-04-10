# Afterglow

A Clojure take on a DMX lighting control system leveraging pieces of the [Overtone](https://github.com/overtone/overtone) toolkit and the [Open Lighting Architecture](https://www.openlighting.org/ola/). For efficiency, Afterglow uses [Protocol Buffers](https://developers.google.com/protocol-buffers/docs/overview) to communicate with the `olad` process running on the local machine via its [RPC Service](https://docs.openlighting.org/doc/latest/rpc_system.html).

## Installation

1. [Install OLA](https://www.openlighting.org/ola/getting-started/downloads/); I recommend using [Homebrew](http://brew.sh) which lets you simply `brew install ola`. Once you launch the `olad` server you can interact with its embedded [web server](http://localhost:9090/ola.html), which is very helpful in seeing whether anything is working; you can even watch live DMX values changing.
2. For now set up a Clojure project using [Leiningen](http://leiningen.org).
3. Add this project as a dependency: [![Clojars Project](http://clojars.org/afterglow/latest-version.svg)](http://clojars.org/afterglow)

Eventually you may be able to download binary distributions from somewhere.

## Usage

Given its current development phase, you will want to use Afterglow in a Clojure repl.

    (use 'afterglow.examples)
    (ramp-one-channel)
    (afterglow.rhythm/metro-bpm metro 80)
    (stop!)

If you have a web browser open on [your OLA daemon](http://localhost:9090/ola.html)'s DMX monitor for Universe 1, you will see the channel values ramping up quickly, then slowing down a little when you change the BPM. Edit the example to use a different universe or channel, or do more fun stuff.

## Options

FIXME: listing of options this app accepts once it can run as a standalone app.

## Examples

...

### Bugs

...

### Ideas

* Tons of oscillators and combinators for them, with convenient initializers.
* Model moving head location and position, so they can be panned and aimed in a coordinated way.
* Model colors, support setting via HSB, eventually maybe even model individual LED colors, especially for fixtures with more than three colors.
* Add OSC support (probably using Overtone&rsquo;s implementation) for controller support, and MIDI as well.

### References

* Clojure implementation of Protocol Buffers via [lein-protobuf](https://github.com/flatland/lein-protobuf) and [clojure-protobuf](https://github.com/flatland/clojure-protobuf).
* The incomplete [Java OLA client](https://github.com/OpenLightingProject/ola/tree/master/java).

### Related Work

* Am fixing the [broken Max external](https://wiki.openlighting.org/index.php/OlaOutput_Max_External). It fails because it tries to load an outdated version of the `libproto` DLL in a hardcoded bad library path. I have now been able to check out the source into `old/svn/olaoutput-read-only` and succeeded at building and fixing it. I separately downloaded the [Max 6.1.4 SDK](https://cycling74.com/downloads/sdk/). The maxhelp file has some seeming errors in it: a "state" message which seems unimplemented, and a second inlet which appears to really just be an argument. I have offered to share my changes and explore fixing the help unless the authors want to, on the open lighting Google group. They have, at my prompting, migrated to github, and I am committing my changes to a fork, in `git/olaoutput`.

## License

Copyright © 2015 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
