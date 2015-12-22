# Afterglow

[![Join the chat at https://gitter.im/brunchboy/afterglow](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/brunchboy/afterglow?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) <image align="right" width="275"
src="doc/assets/Afterglow-logo-padded-left.png">
<br/><br/>
An environment supporting
[live coding](https://en.wikipedia.org/wiki/Live_coding) for the
creation of algorithmic light shows in [Clojure](http://clojure.org),
leveraging the
[Open Lighting Architecture](https://www.openlighting.org/ola/) with
the help of
[ola-clojure](https://github.com/brunchboy/ola-clojure#ola-clojure),
and pieces of the [Overtone](http://overtone.github.io) toolkit.
Beyond building on pieces of Overtone, the entire Afterglow project
was [inspired](https://vimeo.com/22798433) by it.

[![License](https://img.shields.io/github/license/brunchboy/afterglow.svg)](#license)

### Documentation Overview

This page provides an introduction in how to install and use
Afterglow. The main
[documentation](doc/README.adoc#afterglow-documentation) goes much
deeper, and there is also
[API documentation](http://deepsymmetry.org/afterglow/doc/index.html).
Finally, the project
[wiki](https://github.com/brunchboy/afterglow/wiki) offers a place for
fluid discussion of ideas and sharing of code and discoveries among
the entire user community, and the
[gitter chat](https://gitter.im/brunchboy/afterglow) supports even
faster and less formal discussion.

## Why Explore Afterglow?

As suggested by the live-coding orientation mentioned above, which is
designed to let you inject your own code right into the frame
rendering process, Afterglow takes a very different approach to
controlling light shows than other software. It won't be right for
everyone, but will be extremely compelling to a particular niche. The
early stages of its
[rendering loop](https://github.com/brunchboy/afterglow/blob/master/doc/rendering_loop.adoc#the-rendering-loop)
can offer higher levels of abstraction than the usual DMX
[channel value](https://github.com/brunchboy/afterglow/blob/master/doc/effects.adoc#channel-effects)
or
[fixture function](https://github.com/brunchboy/afterglow/blob/master/doc/effects.adoc#function-effects)
(although those are fully supported too):

* You can express your desired results in terms of an abstract
  [color](https://github.com/brunchboy/afterglow/blob/master/doc/effects.adoc#color-effects),
  including support for the hue-saturation-lightness model, which is
  great for algorithmic looks, and have it translated to whatever
  color channels (or color wheel) your fixture supports.

* Groups of moving heads can be told to face particular
  [directions](https://github.com/brunchboy/afterglow/blob/master/doc/effects.adoc#direction-effects)
  by specifying parameterized vectors, or to
  [aim](https://github.com/brunchboy/afterglow/blob/master/doc/effects.adoc#aim-effects)
  at a particular point in space, and Afterglow figures out how to
  translate that into DMX control values given its understanding of
  the
  [fixture](https://github.com/brunchboy/afterglow/blob/master/doc/fixture_definitions.adoc#fixture-definitions)
  and
  [where](https://github.com/brunchboy/afterglow/blob/master/doc/show_space.adoc#show-space),
  and at what angle, you hung it.

* There are a variety of
  [oscillators](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#oscillators)
  which can efficiently drive effect parameters.

* You can also create
  [complex effects](https://github.com/brunchboy/afterglow/blob/master/doc/effects.adoc#complex-effects),
  with
  [adjustable parameters](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#dynamic-parameters)
  that can be controlled through a rich binding to an
  [Ableton Push](https://github.com/brunchboy/afterglow/blob/master/doc/mapping_sync.adoc#using-ableton-push)
  controller.

* The timing of effects is pervasively influenced by a deep notion of
  [musical time](https://github.com/brunchboy/afterglow/blob/master/doc/metronomes.adoc#metronomes),
  with support for synchronization via
  [MIDI clock](https://github.com/brunchboy/afterglow/blob/master/doc/mapping_sync.adoc#syncing-to-midi-clock)
  or Pioneer
  [Pro DJ Link](https://github.com/brunchboy/afterglow/blob/master/doc/mapping_sync.adoc#syncing-to-pro-dj-link)
  beat grids.

* You can even host Afterglow within
  [Cycling ‘74’s Max](https://cycling74.com/) visual interactive
  environment.

If any of this sounds interesting to you, here is how to get started:

## Installation

1. [Install OLA](https://www.openlighting.org/ola/getting-started/downloads/).
   (On the Mac I recommend using [Homebrew](http://brew.sh) which lets you simply
   `brew install ola`). Once you launch the `olad` server you can
   interact with its embedded
   [web server](http://localhost:9090/ola.html), which is very helpful
   in seeing whether anything is working; you can even watch live DMX
   values changing.

   > :wrench: If you are installing Afterglow on Windows, see the
   > [Wiki discussion](https://github.com/brunchboy/afterglow/wiki/Questions#ola-and-windows)
   > about OLA options.
   
2. For now set up a Clojure project using [Leiningen](http://leiningen.org).

3. Add this project as a dependency:
   [![Clojars Project](https://img.shields.io/clojars/v/afterglow.svg)](http://clojars.org/afterglow)

> :wrench: If you want to use an Ableton Push controller, and are
> using a Mac, you will need to work around some bugs in Java MIDI
> support on the Mac. The
> [Wiki](https://github.com/brunchboy/afterglow/wiki/Questions#midi-from-java-on-the-mac)
> explains how to do that.

If you want to run Afterglow as a standalone executable, you can
download the executable &uuml;berjar from the
[releases](https://github.com/brunchboy/afterglow/releases) page.
[![&uuml;berjar](https://img.shields.io/github/downloads/brunchboy/afterglow/latest/afterglow.jar.svg)](https://github.com/brunchboy/afterglow/releases)

For an example of a project which uses Afterglow as a dependency, as
described above, see
[afterglow-max](https://github.com/brunchboy/afterglow-max#afterglow-max),
which hosts Afterglow inside [Cycling ‘74’s Max](https://cycling74.com/).

## Status

Although Afterglow is far from finished, it's ready for the world to
start exploring, and helping decide directions in which to grow next
(as well as identifying areas where the documentation needs
clarification or reinforcement).

Most of the crazy ideas have panned out and been implemented, and I am
fleshing out the basic details needed for everyday use. The examples
are starting to be intriguing and informative, and the
[documentation](doc/README.adoc#afterglow-documentation) is getting
substantial. The modeling of fixtures, channels, etc. is coming
together nicely, though there may be a few more changes.

There is now an embedded web application, which is growing into a show
control interface for people who are not Clojure hackers, and a useful
adjunct to the Ableton Push control surface interface. Both are
explained in the documentation link above. Afterglow also includes the
beginnings of a show visualizer for designing and working on effects
without having to physically hook up lights (a proof of concept,
really, at this point). This is implemented in WebGL using a
volumetric ray tracer and looks quite promising, at least for a small
number of fixtures; it will probably overwhelm the graphics processor
on most systems once you add too many lights. However, the framework
can be used by someone who actually knows OpenGL programming to build
a more scalable preview (albeit one that probably doesn’t look quite
so photo-realistic with beams impacting drifting fog). This is an area
where I would love some help if it sounds interesting!

## Usage

> The rest of this document primarily provides an introduction to the
> configuration of Afterglow from the command line and text files. The
> show control interface is explained in the
> [web](https://github.com/brunchboy/afterglow/blob/master/doc/README.adoc#the-embedded-web-interface)
> and
> [Push](https://github.com/brunchboy/afterglow/blob/master/doc/mapping_sync.adoc#using-ableton-push)
> sections.

Although you will often want to use Afterglow from a Clojure repl, you
can also bring it up as an executable jar, and run it using `java
-jar` with command-line arguments:

```
> java -jar afterglow.jar --help

afterglow 0.1.4, a live-coding environment for light shows.
Usage: afterglow [options] [init-file ...]
  Any init-files specified as arguments will be loaded at startup,
  in the order they are given, before creating any embedded servers.

Options:
  -w, --web-port PORT     16000               Port number for web UI
  -o, --osc-port PORT     16001               Port number for OSC server
  -r, --repl-port PORT                        Port number for REPL, if desired
  -l, --log-file PATH     logs/afterglow.log  File into which log is written
  -H, --olad-host HOST    localhost           Host name or address of OLA daemon
  -P, --olad-port PORT    9010                Port number OLA daemon listens on
  -q, --convert-qxf PATH                      Convert QLC+ fixture file and exit
  -h, --help                                  Display help information and exit

If you translate a QLC+ fixture definition file, Afterglow will try to write
its version in the same directory, but won't overwrite an existing file.

If you do not explicitly specify a log file, and Afterglow cannot write to
the default log file path, logging will be silently suppressed.

Please see https://github.com/brunchboy/afterglow for more information.
```

As noted, you can pass a list of init-files when you run Afterglow
this way, which gives you the opportunity to set up the actual
universes, fixtures, effects, and cues that you want to use in your
show. As a starting point, you could put something like the following
in a file `my-show.clj` and then invoke Afterglow as `java -jar afterglow.jar my-show.clj`:

```clojure
(ns my-show
  "Set up the fixtures, effects, and cues I actually want to use."
  ;; TODO: Your list of required namespaces will differ from this, depending on
  ;;       what fixtures you actually use, and what effects and cues you create.
  (:require [afterglow.core :as core]
            [afterglow.transform :as tf]
            [afterglow.effects.color :refer [color-effect]]
            [afterglow.effects.cues :as cues]
            [afterglow.effects.dimmer :refer [dimmer-effect]]
            [afterglow.effects.fun :as fun]
            [afterglow.effects.movement :as move]
            [afterglow.effects.oscillators :as oscillators]
            [afterglow.effects.params :as params]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer :all]
            [com.evocomputing.colors :refer [create-color hue adjust-hue]]
            [taoensso.timbre :as timbre]))

(defonce ^{:doc "Holds my show if it has been created,
  so it can be unregistered if it is being re-created."}
  my-show
  (atom nil))

(defn use-my-show
  "Set up the show on the OLA universes it actually needs."
  []

  ;; Create, or re-create the show. Make it the default show so we don't
  ;; need to wrap everything below in a (with-show sample-show ...) binding.
  (set-default-show!
   (swap! my-show (fn [s]
                    (when s
                      (show/unregister-show s)
                      (with-show s (show/stop!)))
                    ;; TODO: Edit this to list the actual OLA universe(s) that
                    ;;       your show needs to use if they are different than
                    ;;       just universe 1, as below, and change the description
                    ;;       to something descriptive and in your own style:
                    (show/show :universes [1] :description "My Show"))))

  ;; TODO: Replace this to patch in an actual fixture in your show, at its actual
  ;;       universe, DMX address, physical location and orientation, then add all
  ;;       your other fixtures one by one.
  (show/patch-fixture! :torrent-1 (blizzard/torrent-f3) 1 1
                       :x (tf/inches 44) :y (tf/inches 51.75) :z (tf/inches -4.75)
                       :y-rotation (tf/degrees 0))
  
  ;; Return the show's symbol, rather than the actual map, which gets huge with
  ;; all the expanded, patched fixtures in it.
  '*show*)

(core/init-logging)  ; Log at :info level to rotating files in logs/ subdirectory.
(use-my-show)  ; Set up my show as the default show, using the function above.

;; TODO: Add your custom effects, then assign them to cues with sensible colors
;;       See afterglow.examples for examples.
```

As noted, you will want to look at the
[afterglow.examples](https://github.com/brunchboy/afterglow/blob/master/src/afterglow/examples.clj)
namespace for some examples of how to populate this file; the rest of
this section gives an overview and walk-through of how pieces of that
namespace work. The `:require` section at the top of `my-show.clj` is
set up to make it easy to cut and paste from these examples, although
it is not complete, and you will eventually need to learn how to
adjust and optimize it yourself.

> The example code above configures Afterglow to log to a set of
> rotating log files in a `logs/` subdirectory of your project. If you
> want to see any logging information, which can be quite useful when
> troubleshooting, you will need to create that directory, otherwise
> the logging mechanism will silently do nothing. They will stay out
> of your way until you are interested in them, and take up a limited
> amount of space, but whenever you do want to watch what Afterglow is
> doing, you can look at them, or `tail -f logs/afterglow.log` to
> watch it live.

As your show gets more complex, you may want to split this into
multiple files, which you can either load by listing them all on the
command line, or by using Clojure's `load-file` function from within
the first file. Or, once you are comfortable with idomatic Clojure
development, by organizing them into a hierarchy of namespaces, and
using the normal `:require` mechanism that is used to pull in
Afterglow's own namespaces.

> :heavy_exclamation_mark: At this early stage of development, using
> Afterglow as an executable jar is less-tested territory, and you may
> find surprising bugs... though this is becoming less of an issue
> since the advent of
> [afterglow-max](https://github.com/brunchboy/afterglow-max#afterglow-max),
> which is putting Afterglow through its paces as an embedded jar. In
> any case, although the project will gradually evolve into a system
> that non-Clojure hackers can use, for now you are probably best off
> playing with it inside a Clojure development environment, or within
> Max, likely with a Clojure environment connected via nREPL.

Assuming you are using it from within a REPL, there is a namespace
`afterglow.examples` which is intended to help you get started quickly
in exploring the environment, as well as serving as an example of how
to configure your own shows, fixtures, effects, and cues.

> The next two lines are not needed if you are using a checkout of the
> Afterglow source code rather than the library version described
> above, since the project is configured to start you in this
> namespace for convenience.

```clojure
(require 'afterglow.examples)
(in-ns 'afterglow.examples)
```

When you run Afterglow as an executable jar, it will automatically
open a web browser window on its embedded web interface. If you are
using it in another way, you can bring up the web interface, and open
a browser window on it, with a one-liner like this (the first argument
specifies the port on which to run the web interface, and the second
controls whether a browser window should be automatically opened):

```clojure
(core/start-web-server 16000 true)
```

![Web interface](https://raw.githubusercontent.com/brunchboy/afterglow/master/doc/assets/WebHome.png)
    
As noted at the bottom, the web interface provides a minimal console
as well, so if you are running Afterglow from a jar and just want to
tweak something quickly, you can use that:

![Web console](https://raw.githubusercontent.com/brunchboy/afterglow/master/doc/assets/Console.png)

> However, this does not offer the valuable support you would have
> from a dedicated REPL like
> [Cider](https://github.com/clojure-emacs/cider) (in Emacs) or
> [Cursive](https://cursiveclojure.com) (in IntelliJ): things like
> symbol completion, popup documentation, and command-line recall,
> which make for a vastly more productive exploration session. So even
> when you are running from a jar rather than launching from a REPL,
> you will often want to access a real REPL. You can accomplish that
> with command-line arguments or by using the web console to invoke
> [core/start-nrepl](http://deepsymmetry.org/afterglow/doc/afterglow.core.html#var-start-nrepl)
> and then connecting your favorite REPL environment to the network
> REPL port you created.

The web interface does provide a nice show control page, though, with
access to a scrollable grid of cues, and the ability to track the cues
displayed on a physical cue grid control surface like the Ableton
Push, so you can control them from either place, and see the names
that go with the colored buttons on the control surface:

![Show control](https://raw.githubusercontent.com/brunchboy/afterglow/master/doc/assets/ShowGrid.png)

Here is the Ableton Push interface tied to that same section of the
cue grid. This physical control surface lets you trigger more than one
cue at the same time, and also gives you niceties unavailable with a
mouse, like pressure sensitivity so your effect intensity, speed,
color, or other parameters can be varied as you alter the pressure
which you are applying to the pads:

![Push interface](https://raw.githubusercontent.com/brunchboy/afterglow/master/doc/assets/AbletonInterface.jpg)

You can adjust running effects, scroll around the cue grid, and adjust
or sync the show metronome from either interface. Other MIDI
controllers can be mapped to provide similar functionality, and
hopefully such mappings will make their way into Afterglow soon, but
it will be hard to find many as nicely designed as the Push. (The
Afterglow mapping is done entirely on the User layer as well, so it
coexists gracefully with Ableton Live, and you can switch back and
forth by pressing the User button if you want to perform with both.)

But, getting back to our REPL-based example: We next start the sample
show, which runs on DMX universe 1. You will want to have OLA
configured to at least have an ArtNet universe with that ID so you can
watch the DMX values using its web interface. It would be even better
if you had an actual DMX interface hooked up, and changed the show to
include some real lights you have connected. Either way, here is how
you start the show sending control signals to lights:

```clojure
(use-sample-show) ; Create the sample show that uses universe 1.
(show/start!)     ; Start sending its DMX frames.
```

The `afterglow.examples` namespace includes a helper function,
`fiat-lux`, to assign a nice cool blue color to all lights in the
sample show, set their dimmers to full, and open the shutters of the
Torrent moving-head spots, which can be called like this:

```clojure
(fiat-lux)
```

So if you happened to have the same fixtures hooked up, assigned the
same DMX addresses as I did when I wrote this, you would see a bunch
of blue light. More realistically, you can navigate to the `olad`
embedded [web server](http://localhost:9090/new/) and see the
non-zero DMX values in the blue and dimmer channels, assuming you have
set up a Universe with ID 1.

> In an environment where you are running multiple shows, the more
> general way of working with one would look like:

```clojure
(def another-show (some-function-that-creates-a-show))
(with-show another-show
  (show/start!)
  (fiat-lux))
```

> However, the `examples` namespace assumes you are just using one,
> and has set it up as the default show, like this:

```clojure
(set-default-show! sample-show)
```

> That saves us the trouble of wrapping all our show manipulation
> functions inside of `(with-show ...)` to establish a context. You
> will likely want to do something similar in setting up your own
> shows, since a single show is the most common scenario. See the
> [afterglow.show-context](http://deepsymmetry.org/afterglow/doc/afterglow.show-context.html)
> API documentation for more details. The `show-context` namespace
> also defines the dynamic variable `*show*` which you can use to
> refer to the current default show when you need to mention it
> explicitly, as you will see in some of the examples below.

The actual content of `fiat-lux` is quite simple, creating
three effects to achieve the goals mentioned above:

```clojure
(defn fiat-lux
  "Start simple with a cool blue color from all the lights."
  []
  (show/add-effect! :color (global-color-effect "slateblue"
                    :include-color-wheels? true))
  (show/add-effect! :dimmers (global-dimmer-effect 255))
  (show/add-effect! :torrent-shutter
                    (afterglow.effects.channel/function-effect
                    "Torrents Open" :shutter-open 50
                    (show/fixtures-named "torrent"))))
```

We can make the lights a little dimmer...

```clojure
(show/add-effect! :dimmers (global-dimmer-effect 200))
```

> Adding a function with the same keyword as an existing function
> replaces the old one. The dimmer channels drop from 255 to 200.

But for dimmer channels, there is an even better way of doing that:

```clojure
(master-set-level (:grand-master *show*) 80)
```

All cues which set dimmer levels are tied to a dimmer master chain.
If none is specified when creating the cue, they are tied directly
to the show’s dimmer grand master. Setting this to a value less than
100 scales the dimmer values sent to the lights down by that amount.
So the above command dims the lights to 80% of their possible
brightness, no matter what else the cues are trying to do. See the
[dimmer effects API documentation](http://deepsymmetry.org/afterglow/doc/afterglow.effects.dimmer.html)
for more details. Here is an example of what I call right away when
testing effects in my office with the little Korg nanoKONTROL 2
plugged in:

```clojure
(show/add-midi-control-to-master-mapping "slider" 0 7)
```
And then the last fader acts as my grand master dimmer, and I can
quickly get relief from overly bright lights.

> If you have access to an Ableton Push, it is even easier to have
> [intutive control](https://github.com/brunchboy/afterglow/blob/master/doc/mapping_sync.adoc#using-ableton-push)
> over your show’s grand master dimmer. As soon as you bind the Push
> to your show, the Push Master encoder is automatically tied to the
> show master dimmer, with nice graphical feedback in the text area.
> Plus you get deep control over the show metronome as well, as shown
> in the photo above. Here is how the binding is established:

```clojure
(require '[afterglow.controllers.ableton-push :as push])
(def controller (push/bind-to-show *show*))
```
Moving on, though... we can change the global color to orange:

```clojure
(show/add-effect! :color (global-color-effect :orange))
```

> The color channel values change.
    
Let’s get a little fancy and ramp the dimmers up on a sawtooth curve each beat:

```clojure
(show/add-effect! :dimmers
                  (global-dimmer-effect (oscillators/build-oscillated-param
                                        (oscillators/sawtooth))))
```

Slow that down a little:

```clojure
(afterglow.rhythm/metro-bpm (:metronome *show*) 70)
```

> If you have a web browser open on
> [your OLA daemon](http://localhost:9090/ola.html)’s DMX monitor for
> Universe 1, you will see the values for channels changing, then
> ramping up quickly, then a little more slowly after you change the
> BPM. OLA 0.9.5 introduced a new, beta web UI based on AngularJS
> which you can access through a small
> [New UI (Beta)](http://localhost:9090/new/) link at the bottom of
> the page. In my experience, it has been completely stable, looks a
> lot better, and is *far* more dynamic and responsive at monitoring
> changing DMX values, and presenting them in an intuitive at-a-glance
> way.

If you can, alter the example to use a universe and channels that you
will actually be able to see with a connected fixture, and watch
Clojure seize control of your lights!

### Further Experiments

If you have DJ software or a mixer sending you MIDI clock data, you
can sync the show’s BPM to it (see the
[docs](https://github.com/brunchboy/afterglow/blob/master/doc/mapping_sync.adoc#syncing-to-midi-clock)
for details, and for a Traktor controller mapping file that lets you
sync to its beat phase information as well):

```clojure
(show/sync-to-external-clock (afterglow.midi/sync-to-midi-clock "traktor"))
```

How about a nice cycling rainbow color fade?

```clojure
(def hue-param (oscillators/build-oscillated-param (oscillators/sawtooth :interval :bar)
                                                   :max 360))
(show/add-effect! :color (global-color-effect
  (params/build-color-param :s 100 :l 50 :h hue-param)))
```

Or, if you need to be woken up a bit,

```clojure
(show/add-effect! :strobe (afterglow.effects.channel/function-cue
  "Fast blast!" :strobe 100 (show/all-fixtures)))
```

> The [project documentation](https://github.com/brunchboy/afterglow/blob/master/doc/README.adoc#afterglow-documentation)
> has more examples of
> [building effects](https://github.com/brunchboy/afterglow/blob/master/doc/effects.adoc#effect-examples),
> and
> [mapping parameters](https://github.com/brunchboy/afterglow/blob/master/doc/mapping_sync.adoc#midi-mapping-and-beat-sync)
> to MIDI controllers. There is also low-level
> [API documentation](http://deepsymmetry.org/afterglow/doc), but the
> project documentation is the best starting point for a conceptual overview and
> introduction.

When you are all done, you can terminate the effect handler thread...

```clojure
(show/stop!)
```
    
And darken the universe you were playing with.

```clojure
(show/blackout-show)
```

> An alternate way of accomplishing those last two steps would have
> been to call `(show/clear-effects!)` before `(show/stop!)` because
> once there were were no active effects, all the DMX values would
> settle back at zero and stay there until you stopped the show.

## Troubleshooting

When afterglow has important events to report, or encounters problems,
it writes log entries. In its default configuration, it tries to write
to a `logs` directory located in the current working directory from
which it was run. If that directory does not exist, and you have not
explicitly configured a path to a log file, it assumes you are not
interested in the logs, and silently suppresses them. So if things are
not going right, the first step is to enable logging. You can either
do this by creating a `logs` folder for Afterglow to use, or by
running it with the `-l` command-line argument to set an explicit log
file path, as described in the [Usage](#usage) section above. If you
do that, afterglow will create any missing directories in the log file
path, and fail with a clear error message if it is unable to log to
the place you asked it to.

The Open Lighting Architecture's
[web interface](http://localhost:9090/new/#/), which you can find on
port 9090 of the machine running afterglow-max if you installed it in
the normal way, can be useful in troubleshooting as well. You can see
if the universes that afterglow is expecting to interact with actually
exist, are configured to talk to the lighting interfaces you expect,
and are sending DMX channel values that seem reasonable.

## Bugs

Although there are none known as of the time of this release, I am
sure some will be found, especially if you are tracking the master
branch to keep up with the current rapid pace of development. Please
feel free to log
[issues](https://github.com/brunchboy/afterglow/issues) as you
encounter them!

## Tasks

- [x] Sync metronomes to MIDI
- [x] Add metronome chase for clear sync testing
- [x] Allow parameterized effects functions
- [x] Start wiki
- [x] Allow metronomes to be show variables
- [x] Improve Oscillators
  - [x] Use keyword parameters
  - [x] Add phrase oscillators
  - [x] Finish wiki page
- [x] Migrate wiki documentation into project documentation.
- [x] Have metronome cue take metronome parameter and support dynamic
  parameters.
- [x] Consider having patched fixture hold a reference to the show.
  That way we could stop having to pass it so many places, though it
  would make printing fixtures less useful. (Not needed; dynamic
  binding works better.)
- [x] Add support for named fixture functions which exist as a value
  range subset of a channel, and effects which set them to particular
  values.
- [x] Allow scaling of named fixture functions, for example to allow a
  strobe effect to be set to a rough Hz value despite differences in
  fixture implementation.
- [x] Add color wheel support.
- [x] Review existing fixture definitions for consistency of function
  names, start a style guide in the docs for others creating fixture
  definitions.
- [x] Add configuration support for running OLA on a different machine.
- [ ] Make pass over all source, flesh out API doc and preconditions.
- [ ] Sparkle effect, essentially a particle generator with
  configurable maximum brightness, fade time, distribution.
  - [x] Get basic effect working until spatial features are available.
  - [ ] Work both with arbitrary head list, and with spatially mapped origin/density.
  - [ ] Work as single intensity, or spatially mapped hue/saturation patterns.
  - [x] Implement a grand master dimmer in the show which imposes a
    ceiling on all dimmer cues.
  - [x] Also allow custom sub-master dimmer variables, chained off
    each other and ultimately the grand master, assigned to cues. Each
    step can scale the output.
  - [x] All dimmer cues are assigned a master chain, defaulting to the
    grand master if none supplied.
- [x] Get geometry engine and head-movement cues working.
- [ ] Named cues: Define cues with a unique name so they can have
  parameters saved for them, to be reloaded on future runs, once we
  have a database. Also useful for compound cues, see below.
  - [ ] This requires cues to have a mechanism for reporting their
    current variable values for saving, and to look them up when
    saved.
- [ ] Compound cues:
  - [x] Unflattened compound cues trigger multiple cues' effects:
    - [x] Each effect gets its own priority, parameters.
    - [x] The compound finishes when all triggered effects do.
    - [x] Implement by having the outer cue's effect call
      `show/add-effect-from-cue-grid!` to launch all the nested
      effects, recording their IDs. The effect will never return any
      assigners, but will report that it has ended when all of the
      nested effects have ended. Telling this effect to end will, in
      turn, call `show/end-effect!` on all nested cues (passing their
      recorded id values as `:when-id`, to avoid inadvertently killing
      later effects run under the same key).
    - [x] Add a `:variable-overrides` parameter to
      `show/add-effect-from-cue-grid!` so compound cues can use it to
      customize the values of parameters introduced by nested cues.
  - [ ] Flattened compound cues flatten their nested cues' effects
    into a single new effect:
    - [ ] This effect gets assigned a new priority.
    - [ ] The compound cue aggregates nested cue variables into one
      big list, and passes them down to the nested effects. (Renaming
      with numeric suffixes as needed to avoid name clashes? No, they
      might be shared.)
  - [ ] Compound cues created from solely named cues can be saved and
    restored, so they can be built using the web (and rich controller)
    interface out of existing cues, and current parameter values for
    running cues.
    - [ ] When creating a compound cue this way, will need to check
      for and prevent circular definitions, as well as reporting
      sensible errors when constituent cues can no longer be found.
- [ ] Compound effects:
  - [x] Simplest compound effect just delegates to nested effects,
    returning concatenated assigners. But implement as a fade with
    time zero?
  - [x] Fade compound effect: fade in at start, out at end.
  - [x] Cue list compound effect: Move through list of embedded
    effects, with optional fades. Loops, driven by metronome, or a
    variable parameter (knob controls where in the list we are). Maybe
    different implementations?
  - [ ] Have effects pass a context map to children with show,
    snapshot, own stuff? For example, so the children can be aware of
    build, duration, a shared palette, other things? Or better, since
    snapshot is already passed, just add a usually-nil section which
    contains information about context, with at least information
    about when overall effect started; current fade level of this
    effect, fading in or out, when effect will end. Even better: This
    can be assoc-ed on to the snapshot, without changing the
    definition in rhythm.clj, since Clojure records are also maps!
  - [ ] Effects which can do their own blending implement an
    additional interface. Otherwise the fade and chase effects (if
    they are different), handle it. To fade between direction effects,
    convert them to pan/tilt numbers, scale between those, then
    convert back to a direction.
- [ ] Provide a mechanism for creating and controlling/monitoring
  effects via OSC messages. Probably essentially a special-purpose OSC
  REPL.
- [ ] Add web page for viewing/adjusting show variables; associate
  metadata with the variables so the page can provide appropriate
  editing tools and validation. Values live-update when controllers
  change them.
- [ ] When it comes time to save data and settings, the luminus
  approach looks good, including
  [yesql](https://yobriefca.se/blog/2014/11/25/yesql-sql-in-sql-in-clojure/)
  and h2 as the database. Need to figure out where it would be stored,
  though.
- [x] See if I can get Traktor to just send beat notes for master
  track; if so, add mode for MIDI sync to ride them like DJ link.
  - [x] See example on page 166 of Traktor Bible; it is close, but I
    want to add a condition that makes sure these pulses are sent only
    for the deck which is currently the tempo master. Write to the
    author for advice? Alternately, send separate messages when each
    deck is set as the tempo master, and use those to keep track of
    which beat pulses to pay attention to?
- [ ] See if I can detect which Pro DJ Link device is the current
  master, and if so, add an option for down beat tracking using that.
- [x] Add tap tempo support for really low-end sync.
- [x] Add machine-readable metronome sync status flag, so Push can
  color code it; detect stalled clocks even without stop signals.
- [x] See how Afterglow works as a hosted Max package, with an Inlet
  to send Clojure code to be evaluated, and an outlet for the results.
  This could be a quick way to add beat detection, sound spectrum
  analysis, etc.
  https://pcm.peabody.jhu.edu/~gwright/stdmp/docs/writingmaxexternalsinjava.pdf
  https://docs.cycling74.com/max7/tutorials/jitterchapter51
  https://docs.cycling74.com/max7/vignettes/packages
- [x] Provide a way to import QLC+ fixture definitions to help people
  get started.
- [ ] Consider importing [Avolites](http://personalities.avolites.com)
  personalities/fixture definitions (D4 files); they seem fairly
  straightforward too.
- [x] Separate, or at least document clearly, how to use the low-level
  OLA communication tools, for the benefit of people interested in
  their own implementations.
  
### Ideas

- [x] Model moving head location and position, so they can be panned and aimed in a coordinated way.
  - [x] [Wikipedia](http://en.wikipedia.org/wiki/Rotation_formalisms_in_three_dimensions)
    has the most promising overview of what I need to do.
  - [ ] Use iOS device to help determine orientation of fixture: Hold
    phone upright facing stage from audience perspective to set
    reference attitude; move to match a landmark on the fixture
    (documented in the fixture definition), and have phone use
    [CoreMotion](https://developer.apple.com/library/ios/documentation/CoreMotion/Reference/CMAttitude_Class/index.html#//apple_ref/occ/instm/CMAttitude/multiplyByInverseOfAttitude:)
    `CMAttitude` `multiplyByInverseOfAttitude` to determine the
    difference.
  - [x] The more I investigate, the more it looks like
    [Java3D’s](http://docs.oracle.com/cd/E17802_01/j2se/javase/technologies/desktop/java3d/forDevelopers/J3D_1_3_API/j3dapi/)
    [Transform3D](http://docs.oracle.com/cd/E17802_01/j2se/javase/technologies/desktop/java3d/forDevelopers/J3D_1_3_API/j3dapi/javax/media/j3d/Transform3D.html)
    object is going to handle it for me, which is very convenient, as
    it is already available in Clojure. To combine transformations,
    just multiply them together (with the `mul` method).
  - [x] Use `setEuler` to set a `Transform3D` to a specific set of
    rotation angles.
  - [x] If this leads to accuracy issues or loss of a degree of
    freedom, consider Quaternions, as recommended in
  this [article](http://java.sys-con.com/node/99792).
  - [x] Wow, this may be exactly what I need: Java code for converting
    Quaternions to Euler Angles:
  http://www.euclideanspace.com/maths/geometry/rotations/conversions/quaternionToEuler/
  The site is in general an amazing reference for the kind of geometry I need to learn.
  - [x] This seems to be the formula I need to figure out the angles
    to send a light to make it face a particular direction (the
    selected, top answer):
    http://stackoverflow.com/questions/1251828/calculate-rotations-to-look-at-a-3d-point
    and transform.clj has an implementation in invert-direction. Now I
    just need to test it with an actual light!
  - [x] Remember that Vector3d has nice methods like angle (calculate
    angle to another Vector3d), length, cross, dot...
- [ ] Render preview animations of a light show using WebGL.
  - [x] This [shader](https://www.shadertoy.com/view/Mlj3W1) looks
    nearly perfect, if I can figure out how to adopt it.
  - [x] Looks like a nice intro to 3D, linear algebra, shaders:
    [Making WebGL Dance](http://acko.net/files/fullfrontal/fullfrontal/webglmath/online.html).
    Has interesting references too, such as
    [Interactive 3D Graphics Course](https://www.udacity.com/course/interactive-3d-graphics--cs291),
    Eric Haines, Udacity.com. And the
    [Aerotwist tutorials](https://aerotwist.com/tutorials/), in
    particular Three.js and shaders.
  - [ ] Fix the transform of lights into the WebGL shader space;
    currently inconsistent.
  - [ ] See if someone can come up with a more bare bones but scalable preview, probably building a geometry of the light cones instead of ray marching through them.
- [x] Use [claypoole](https://clojars.org/com.climate/claypoole) for
  parallelism.
- [ ] Change to core.async for all parallelism, since we are already using it anyway.
- [ ] Add OSC support (probably using
  [Overtone&rsquo;s implementation](https://github.com/rosejn/osc-clj))
  for controller support, and MIDI as well.
- [x] Serious references for color manipulation, but in [Julia](https://github.com/timholy/Color.jl).
- [ ] Absolutely amazing reference on
  [color vision](http://handprint.com/LS/CVS/color.html)! Send him a
  note asking if he knows where I can find an algorithm for using
  arbitrary LEDs to make an HSL color!
  - [ ] Consider an alternate HSI color implementation. It could yield more
  pure/accurate results, but perhaps with less intuitive semantics,
  and definitely lower peak output. Most likely a configurable option?
  See the discussion and code on the
  [SaikoLED blog](http://blog.saikoled.com/post/44677718712/how-to-convert-from-hsi-to-rgb-white).
  And [related discussion](http://blog.saikoled.com/post/43693602826/why-every-led-light-should-be-using-hsi),
  with links to color correction.
- [ ] When it is time to optimize performance, study the
  [type hints](http://clojure.org/java_interop#Java%20Interop-Type%20Hints)
  interop information looks very informative and helpful.
  - [ ] satisfies? seems to take twice as long as instance? so change the preconditions to use instance? where possible
  - [ ] Do a pass through all files with *warn-on-reflection* set to
    true, see what hinting can help. `(set! *warn-on-reflection*
    true)` at top of file.
- [ ] Eventually create a leiningen task that can build a standalone
  jar with Afterglow and a custom show definition file and its
  supporting resources, so developers can easily deploy and share
  shows with non-Clojurists.
- [ ] Consider adding support for metronome synchronization with
  [EspGrid](https://github.com/d0kt0r0/EspGrid).
- [ ] Investigate whether
  [Vamp](https://code.soundsoftware.ac.uk/projects/vamp) (and jVamp)
  would be worthwhile for audio analysis. But adding native components
  is likely to be a hassle.
- [ ] See if [thi-ng/color](https://github.com/thi-ng/color) is a
  better fit.
- [x] Once I release the first version, answer this StackOverflow
  [question](http://stackoverflow.com/questions/9582192/dmx-software-to-control-lights-with-programmable-interface).
  - [x] Also submit a link to
    [TOPLAP](http://toplap.org/contact-page/).
  - [ ] Also post a followup to this
    [article](http://radar.oreilly.com/2015/05/creative-computing-with-clojure.html)
    and reach out to some of the artists themselves. (Sadly too late
    to post a comment on the thread, but I should try contacting the
    artists!)

### References

* Clojure implementation of Protocol Buffers via
  [lein-protobuf](https://github.com/flatland/lein-protobuf) and
  [clojure-protobuf](https://github.com/flatland/clojure-protobuf).
* The incomplete
  [Java OLA client](https://github.com/OpenLightingProject/ola/tree/master/java).
* If I decide to use JNA to work around problems in the Mac Java MIDI
  implementation someday, see
  [clj-native](https://github.com/bagucode/clj-native). But most
  likely I need to stick to lower level, lighter JNI, to avoid
  polluting the extensions class path.

### Related Work

- [x] Rich controller support for
  [Ableton Push](https://forum.ableton.com/viewtopic.php?f=55&t=193744)!
  - [x] [Color chart](https://forum.ableton.com/viewtopic.php?f=55&t=192920),
    post a followup if my hue theory pans out.
  - [x] Nice
    [breakdown](http://tai-studio.org/index.php/projects/sound-programming/accessing-abletons-push-device/)
    of button sections.
  - [x] It looks like I can actually specify an RGB color for the buttons using another SysEx:
    [PushPix](https://cycling74.com/wiki/index.php?title=Push_Programming_Oct13_03)
- [x] Add a user interface using [Luminus](http://www.luminusweb.net/docs).
- [x] Separate [ola-clojure](https://github.com/brunchboy/ola-clojure#ola-clojure) into its own project.

## License

<img align="right" alt="Deep Symmetry" src="doc/assets/DS-logo-bw-200-padded-left.png">
Copyright © 2015 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the
[Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php),
the same as Clojure. By using this software in any fashion, you are
agreeing to be bound by the terms of this license. You must not remove
this notice, or any other, from this software. A copy of the license
can be found in
[resources/public/epl-v10.html](https://cdn.rawgit.com/brunchboy/afterglow/master/resources/public/epl-v10.html)
within this project.
