# Afterglow

An environment supporting
[live coding](https://en.wikipedia.org/wiki/Live_coding) for the
creation of dynamic light shows in [Clojure](http://clojure.org),
leveraging the
[Open Lighting Architecture](https://www.openlighting.org/ola/), and
pieces of the [Overtone](http://overtone.github.io) toolkit. For
efficiency, Afterglow uses
[Protocol Buffers](https://developers.google.com/protocol-buffers/docs/overview)
to communicate with the `olad` process running on the local machine
via its
[RPC Service](https://docs.openlighting.org/doc/latest/rpc_system.html).
Beyond building on pieces of Overtone, the entire Afterglow project
was [inspired](https://vimeo.com/22798433) by it.

## Documentation

This page provides an introduction in how to install and use
Afterglow. The main
[documentation](doc/README.adoc#afterglow-documentation) goes much
deeper, and there is also
[API documentation](http://deepsymmetry.org/afterglow/doc/index.html).
Finally, the project
[wiki](https://github.com/brunchboy/afterglow/wiki) offers a place for
more-rapid discussion of ideas and sharing of code and discoveries
among the entire user community.

## Installation

1. [Install OLA](https://www.openlighting.org/ola/getting-started/downloads/).
   (On the Mac I recommend using [Homebrew](http://brew.sh) which lets you simply
   `brew install ola`). Once you launch the `olad` server you can
   interact with its embedded
   [web server](http://localhost:9090/ola.html), which is very helpful
   in seeing whether anything is working; you can even watch live DMX
   values changing.
2. For now set up a Clojure project using [Leiningen](http://leiningen.org).
3. Add this project as a dependency:
   [![Clojars Project](http://clojars.org/afterglow/latest-version.svg)](http://clojars.org/afterglow)
   [![Dependencies Status](http://jarkeeper.com/brunchboy/afterglow/status.png)](http://jarkeeper.com/brunchboy/afterglow)

Eventually you may be able to download binary distributions from somewhere.

## Status

We’re getting close to an initial release, perhaps in June, although
bugs in the Mac Java MIDI implementation have caused some
time-consuming detours. Most of the crazy ideas have panned out and
been implemented, and I am just circling back and fleshing out the
basic details needed for everyday use. The examples are starting to be
intriguing and informative, and the
[documentation](doc/README.adoc#afterglow-documentation) is getting
substantial. The modeling of fixtures, channels, etc. is coming
together nicely, though there have been drastic changes as I gained
experience with how I want to use them.

There is now an embedded web application, which will grow into a user
interface for people who are not Clojure hackers. It is mostly empty
so far, but does include the beginnings of a show visualizer for
designing and working on effects without having to physically hook up
lights. This is implemented in WebGL using a volumetric ray tracer and
looks quite promising, at least for a small number of fixtures; it
will probably overwhelm the graphics processor on most systems once
you add too many lights. However, the framework can be used by someone
who actually knows OpenGL programming to build a more scalable preview
(albeit one that probably doesn’t look quite so photo-realistic with
beams impacting drifting fog).

## Usage

Given its current development phase, you will want to use Afterglow in a Clojure repl.

> The next two lines are not needed if you are using a checkout of the
> Afterglow source code rather than the library version described
> above, since the project is configured to start you in this
> namespace for convenience.

```clojure
(require 'afterglow.examples)
(in-ns 'afterglow.examples)
```

Start the sample show which runs on DMX universe 1. You will want to
have OLA configured to at least have an ArtNet universe with that ID
so you can watch the DMX values using its web interface. It would be
even better if you had an actual DMX interface hooked up, and changed
the definition of `sample-rig` to include some real lights you have
connected. Either way, here is how you start the show sending control
signals to lights:

```clojure
(show/start!)
```

The `afterglow.examples` namespace has already assigned a nice cool
blue color to all lights in the sample show and set their dimmers to
full, using these two lines:

```clojure
(show/add-effect! :color blue-cue)
(show/add-effect! :dimmers (global-dimmer-cue 255))
```

So if you happened to have the same fixtures hooked up, assigned the
same DMX addresses as I did when I wrote this, you would see a bunch
of blue light. More realistically, you can navigate to the `olad`
embedded [web server](http://localhost:9090/ola.html) and see the
non-zero DMX values in the blue and dimmer channels, assuming you have
set up a Universe with ID 1.
    
> In an environment where you are running multiple shows, the more
> general way of working with one would look like:

```clojure
(with-show sample-show
  (show/start!)
  (show/add-effect! :color blue-cue)
  (show/add-effect! :dimmers (global-dimmer-cue 255)))
```

> However, the examples namespace assumes you are just using one,
> and has set it up as the default show, like this:

```clojure
(set-default-show! sample-show)
```

> That saves us the trouble of wrapping all our show manipulation
> functions inside of `(with-show ...)` to establish a context. You
> will likely want to do something similar in setting up your own
> shows, since a single show is the most common scenario. See the
> [afterglow.show-context](http://deepsymmetry.org/afterglow/doc/afterglow.show-context.html)
> API documentation for more details.

We can make the lights a little dimmer...

```clojure
(show/add-effect! :dimmers (global-dimmer-cue 200))
```

> Adding a function with the same keyword as an existing function
> replaces the old one. The dimmer channels drop from 255 to 200.

But for dimmer channels, there is an even better way of doing that:

```clojure
(master-set-level (:grand-master sample-show) 80)
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
> intutive control over your show’s grand master dimmer. As soon as
> you bind the Push to your show, the Push Master encoder is
> automatically tied to the show master dimmer, with nice graphical
> feedback in the text area. Plus you get deep control over the show
> metronome as well:

```clojure
(require '[afterglow.controllers.ableton-push :as push])
(def controller (push/bind-to-show *show*))
```
 ![Push
  interface](https://raw.githubusercontent.com/brunchboy/afterglow/master/doc/assets/PushIntro.jpg)
    
Moving on, though... we can change the global color to orange:

```clojure
(show/add-effect! :color (global-color-effect :orange))
```

> The color channel values change.
    
Let’s get a little fancy and ramp the dimmers up on a sawtooth curve each beat:

```clojure
(show/add-effect! :dimmers
                  (global-dimmer-cue (params/build-oscillated-param
                                     (oscillators/sawtooth-beat))))
```

Slow that down a little:

```clojure
(afterglow.rhythm/metro-bpm (:metronome sample-show) 70)
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
for details):

```clojure
(show/sync-to-external-clock (afterglow.midi/sync-to-midi-clock "traktor"))
```

How about a nice cycling rainbow color fade?

```clojure
(def hue-param (params/build-oscillated-param (oscillators/sawtooth-bar) :max 360))
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

## Options

TODO: listing of options this app accepts once it can run as a standalone app.

### Bugs

...

### Tasks

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
- [ ] Review existing fixture definitions for consistency of function
  names, start a style guide in the docs for others creating fixture
  definitions.
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
- [ ] Add button color method to IEffect so mapped RGB controllers can
  have animated feedback, e.g. dim version of current effect color if
  off, bright when on; flash it while ending. Update MIDI feedback
  around ten times per second?
- [ ] Named cues: Define cues with a unique name so they can have
  parameters saved for them, to be reloaded on future runs, once we
  have a database. Also useful for compound cues, see below.
  - [ ] This requires cues to have a mechanism for reporting their
    current variable values for saving, and to look them up when
    saved.
- [ ] Compound cues:
  - [ ] Unflattened compound cues trigger multiple cues' effects:
    - [ ] Each effect gets its own priority, parameters.
    - [ ] The compound finishes when all triggered effects do.
  - [ ] Flattened compound cues flatten their nested cues' effects
    into a single new effect:
    - [ ] This effect gets assigned a new priority.
    - [ ] The compound cue aggregates nested cue variables into one
      big list, and passes them down to the nested effects, renaming
      with numeric suffixes as needed to avoid name clashes.
  - [ ] Compound cues created from solely named cues can be saved and
    restored, so they can be built using the web (and rich controller)
    interface out of existing cues, and current parameter values for
    running cues.
    - [ ] When creating a compound cue this way, will need to check
      for and prevent circular definitions, as well as reporting
      sensible errors when constituent cues can no longer be found.
- [ ] Compound effects:
  - [ ] Simplest compound effect just delegates to nested effects,
    returning concatenated assigners.
  - [ ] Fade compound effect: fade in at start, out at end.
  - [ ] Cue list compound effect: Move through list of embedded
    effects, with optional fades. Loops, driven by metronome, or a
    variable parameter (knob controls where in the list we are). Maybe
    different implementations?
  - [ ] Have effects pass a context map to children with show,
    snapshot, own stuff? For example, so the children can be aware of
    build, duration, a shared palette, other things?
- [ ] Provide a mechanism for creating and controlling/monitoring
  effects via OSC messages. Probably essentially a special-purpose OSC
  REPL.
- [ ] Add web page for viewing/adjusting show variables; associate
  metadata with the variables so the page can provide appropriate
  editing tools and validation. Values live-update when controllers
  change them.
- [ ] Add show status query, reports statistics as well as list of
  current effects and priorities, perhaps when they were added, the
  list of targets each is currently assigning, other stuff? Could turn
  into the start of a web interface.
- [ ] When it comes time to save data and settings, the luminus
  approach looks good, including
  [yesql](https://yobriefca.se/blog/2014/11/25/yesql-sql-in-sql-in-clojure/)
  and h2 as the database. Need to figure out where it would be stored,
  though.
- [ ] See if I can get Traktor to just send beat notes for master
  track; if so, add mode for MIDI sync to ride them like DJ link.
  - [ ] See example on page 166 of Traktor Bible; it is close, but I
    want to add a condition that makes sure these pulses are sent only
    for the deck which is currently the tempo master. Write to the
    author for advice? Alternately, send separate messages when each
    deck is set as the tempo master, and use those to keep track of
    which beat pulses to pay attention to?
- [ ] See if I can detect which Pro DJ Link device is the current
  master, and if so, add an option for down beat tracking using that.
- [x] Add tap tempo support for really low-end sync.
- [ ] Add machine-readable metronome sync status flag, so Push can
  color code it; detect stalled clocks even without stop signals.
  
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
- [ ] Add OSC support (probably using
  [Overtone&rsquo;s implementation](https://github.com/rosejn/osc-clj))
  for controller support, and MIDI as well.
- [x] Serious references for color manipulation, but in [Julia](https://github.com/timholy/Color.jl).
- [ ] Absolutely amazing reference on
  [color vision](http://handprint.com/LS/CVS/color.html)! Send him a
  note asking if he knows where I can find an algorithm for using
  arbitrary LEDs to make an HSL color!
- [ ] When it is time to optimize performance, study the
  [type hints](http://clojure.org/java_interop#Java%20Interop-Type%20Hints)
  interop information looks very informative and helpful.
  - [ ] Do a pass through all files with *warn-on-reflection* set to
    true, see what hinting can help. `(set! *warn-on-reflection*
    true)` at top of file.
- [ ] Eventually create a leiningen task that can build a standalone
  jar with Afterglow and a custom show definition file and its
  supporting resources, so developers can easily deploy and share
  shows with non-Clojurists.
- [ ] Once I release the first version, answer this StackOverflow
  [question](http://stackoverflow.com/questions/9582192/dmx-software-to-control-lights-with-programmable-interface).
  - [ ] Also submit a link to
    [TOPLAP](http://toplap.org/contact-page/).
  - [ ] Also post a followup to this
    [article](http://radar.oreilly.com/2015/05/creative-computing-with-clojure.html)
    and reach out to some of the artists themselves.

### References

* Clojure implementation of Protocol Buffers via
  [lein-protobuf](https://github.com/flatland/lein-protobuf) and
  [clojure-protobuf](https://github.com/flatland/clojure-protobuf).
* The incomplete
  [Java OLA client](https://github.com/OpenLightingProject/ola/tree/master/java).
* If I decide to use JNA to work around problems in tne Mac Java MIDI
  implementation someday, see
  [clj-native](https://github.com/bagucode/clj-native).

### Related Work

- [ ] Rich controller support for
  [Ableton Push](https://forum.ableton.com/viewtopic.php?f=55&t=193744)!
  - [x] [Color chart](https://forum.ableton.com/viewtopic.php?f=55&t=192920),
    post a followup if my hue theory pans out.
  - [ ] Nice
    [breakdown](http://tai-studio.org/index.php/projects/sound-programming/accessing-abletons-push-device/)
    of button sections.
- [ ] Add a user interface using
  [Luminus](http://www.luminusweb.net/docs).

## License

Copyright © 2015 [Deep Symmetry, LLC](http://deepsymmetry.org)

Distributed under the
[Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php),
the same as Clojure. By using this software in any fashion, you are
agreeing to be bound by the terms of this license. You must not remove
this notice, or any other, from this software. A copy of the license
can be found in
[resources/public/epl-v10.html](https://cdn.rawgit.com/brunchboy/afterglow/master/resources/public/epl-v10.html)
within this project.
