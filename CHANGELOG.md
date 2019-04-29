# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Fixed

- The built-in converter for QLC+ fixture definitions (`.qxf` files)
  now supports the new XML schema used by QLC+.
- There were some off-by-one errors in the metronome, which canceled
  each other out in the places they were used, but would have caused
  problems in new situations. They were discovered while porting the
  logic to Java for the
  [electro](https://github.com/Deep-Symmetry/electro) library.
- Abrupt tempo changes could cause the metronome to jump to the wrong
  place, even the wrong beat. This is now prevented, leaving you at
  the exact same place in the beat grid before and after any tempo
  change.
- The metronome better handles negative time, and has some improved
  documentation.

### Added

- The detailed documentation has been reorganized as a Developer Guide
  and is now built by [Antora](http://antora.org) to provide easier
  navigation and a more readable presentation.
- The Developer Guide is now available through the built-in web server
  even if you do not have a connection to the internet.

## [0.2.4] - 2017-04-18

### Fixed

- The pixels of the Blizzard Pixellicious were flipped over the X
  axis.
- The web interface needed to have the Beat Link `DeviceFinder`
  started before trying to render the UI, or it would only partially
  render because of an exception when trying to use it.
- Calling `show/patch-fixture!` returned a huge recursive data
  structure which would cause the REPL to choke trying to print it,
  especially in an editor. Now it just returns the key identifying the
  fixture that was patched.

### Added

- A new command-line argument, `-n`, tells afterglow not to try to
  launch a web browser when it is invoked from the command-line. This
  is to enable headless operation on small servers which lack a
  windowing environment.

## [0.2.3] - 2016-06-09

### Fixed

- Rich controller bindings (Ableton Push and Novation Launchpad
  families) were not being successfully auto-bound under Windows
  because of significant differences in the way their port names were
  assigned on that platform. Fixing that was surprisingly tricky, but
  it has been done. I look forward to someone being able to help test
  this under Linux now.
- The graphical display on the Ableton Push 2 could not be connected
  under Windows due to an issue in the Wayang library. That library
  has been fixed, and the new version is embedded in this release.

## [0.2.2] - 2016-05-30

### Added

- The CoreMIDI4J library is now embedded within Afterglow, so it does
  not need to be separately installed by the user for MIDI to work
  properly on Mac OS X. If you have a separate installation of
  CoreMIDI4J in `/Library/Java/Extensions`, you should remove it to
  avoid version conflicts with newer versions shipped with Afterglow.
- Afterglow now uses the new beat-link library to synchronize with
  Pioneer DJ Link equipment, which enables several new features such
  as tracking the master player, synching to bars as well as beats,
  and having the metronome reflect the current track position.
- Holding down Shift while pressing a scroll arrow on the Launchpad
  family of controllers now moves you as far as possible in that
  direction, as it did on the Push 2.
- Head information is now available to channel effects (which include
  dimmer effects), so they can use dynamic parameters with spatial
  components, just like all the other kinds of effects can.

### Fixed

- When updating large sections of the cue grid colors, we no longer
  send all of them at once, because this was overflowing buffers on
  the Push 2 and losing some updates. Instead we send them in batches,
  ending each batch with a query, and wait for the response so we know
  the controller has caught up.
- Updated to the latest release of the Wayang library for drawing on
  the Push 2 display, which solves an issue that prevented the display
  from working under Window.

### Changed

- The hue and saturation gauges on the Push 2 are now drawn using a
  masking image so they can be anti-aliased to the same outline shape
  as the other gauges, and look much cleaner.
- Boolean gauges on the Push 2 are drawn in red or green for No and
  Yes values, and the transition between values is animated, to make
  it easier to see what is happening, and to relate it to the encoder
  rotation.
- Assigner target IDs can now have arbitrary structure appropriate to
  the needs of the assigner implementation, rather than being forced
  into keywords as they used to be. For the most part, they are
  integers (head IDs), some are tuples (universe ID and channel pairs
  for channel assigners, head ID and function keywords for head
  function assigners, other things for extensions like Beyond laser
  show assigners).


## [0.2.1] - 2016-04-03

### Added

- The web and Push interfaces now offer a way to save adjusted cue
  variable values, so the next time the cue is launched the saved
  values are used.
- Show operators can also create "Macros" by selecting a group of
  running cues and choosing an unused cue grid cell. This will create
  a new compound cue in the cell which will re-run all of the cues
  they specified, with the same parameters they had when the macro was
  created, whenever it is run. The compound cue will end all of its
  component cues when you end it, and will end itself if they end
  independently.
- You can now right-click on cues in the web interface to bring up a
  menu of actions. The menu so far offers just the ability to delete
  the cue if there is one there (this can clean up macros you no
  longer want, for example).
- The Ableton Push 2 is now supported as a rich grid control
  interface, taking full advantage of its color graphic display.
- Support for other members of the Novation Launchpad family of grid
  controllers has been implemented:
  - Launchpad Mini
  - Launchpad S (untested, but the Mini, which works, is based on the S)
  - Launchpad Mk2
- The identity of grid controllers is verified before binding to them,
  by sending a MIDI Device Inquiry message and inspecting the
  response.
- The auto-bind mechanism has been improved so much that the sample
  show can now simply turn it on to fully automate the process of
  detecting and binding to any compatible grid controllers that appear
  in the MIDI environment, with no user or configuration effort.
- Direction and aim parameters can now be transformed by a dynamic
  Java3D `Transform3D` parameter to create kaleidoscopic looks with
  groups of lights.
- A new `confetti` effect which assigns random colors (and optionally
  aim points) to groups of lights at intervals.
- A new `pinstripes` effect which can alternate stripes of color
  across fixtures.
- Incoming MIDI System Exclusive messages can now be received and
  delivered to handlers.
- Cue variables can now be Booleans, to support cues which want to be
  able to adjust the direction of a sawtooth oscillator while running.
- The dimmer oscillator cues created in the sample show now include
  Min and Max variables so the range over which the dimmer oscillates
  can be adjusted.
- The Ableton Push mapping now lets you scroll through all variables
  assigned to a cue so you can see and adjust more than the first two.
- You can now use the touch strip on the Ableton Push to immediately
  jump to any part of the legal value range when adjusting a numeric,
  boolean, or color cue variable, BPM, or the Dimmer Grand Master. The
  LEDs on the touch strip also reflect the current value of the
  variable being adjusted.
- You can use the keyboard arrow keys to navigate around the cue grid
  when using the web UI, as long as no input element is focused.
- You can use the space bar to tap tempo when using the web UI, as
  long as no input element is focused.
- Cues can have visualizer creation functions assigned to them, so
  they can provide animated visualizer displays on the Push 2.

### Fixed

- Fixtures which had no channels assigned to the fixture itself, but
  only to heads (like Blizzard's Pixellicious pixel grids) could not
  be patched properly to shows, because the code checking for address
  conflicts was not able to figure out the universe assigned to them.
- Chases containing only scenes would end instantly rather than
  running, because of some assumptions they were making about how the
  effect protocol was implemented, which scenes violated. Chases are
  now more robust.
- The low-level tempo tap handler was already more useful than I was
  giving it credit for, suitable for both aligning to the current beat
  as well as adjusting the tempo if you hit it three or more times, so
  the shift key can be used to adjust the down beat even on
  unsynchronized shows. This makes it much easier to keep the lights
  in sync manually!
- The color wheel is only applied when a color has sufficient
  saturation for it to make sense. The threshold can be adjusted by
  setting the show variable `:color-wheel-min-saturation`.
- Floating-point cue variables now stay rounded to the specified
  resolution when adjusting them on the Push.
- Effects with Unicode characters in them were crashing the Ableton
  Push display code, since it only handles single byte ASCII plus a
  handful of special symbols used for drawing interface elements. Now
  unprintable characters are substituted with an ellipsis symbol
  rather than crashing.
- The entire Push display was being redrawn on each frame of
  user-interface updates, and all text-labeled button states were
  being set, even if they had not changed from the previous frame.
  These redundant messages are no longer sent, and MIDI messages are
  sent to the Push only when text and button states actually need to
  change.
- The slider tooltips for cue variables in the web UI were getting in
  the way of adjusting the sliders because they would appear when the
  mouse was over the tooltip, not just the slider track. They could
  also not be seen on mobile devices. So they have been turned off
  entirely in favor of always-visible value labels.
- The documentation link in the web interface now takes you to the
  proper version-specific tag of the documentation if it is a release
  build. Snapshot builds take you to `master`.
- The nav bar in the show control web page is now compressed to better
  fit mobile devices, since it can be used on the iPad Pro.
- Extraneous errors were being logged in the browser console because
  we were sometimes returning spurious error responses for cue
  variable updates, saves, and clears.
- The end effect buttons and cue variable scroll buttons under
  the text area on the Ableton Push were not affecting the proper
  effects when the effect view was scrolled back from the most recent.
- The effect overflow indicators in the Push text area were not
  properly disappearing when enough effects ended to render them no
  longer needed until the user pressed Shift to try to scroll.
- Under some circumstances the Push mapping could crash when there was
  no cue associated with an effect.
- All MIDI event handler functions are now called in a context which
  properly recovers from exceptions at the level of that individual
  handler, so other handlers will not be affected.
- Everywhere that Afterglow was checking whether an argument was
  callable as a function has been fixed to use the `ifn?` predicate
  rather than `fn?` since the latter is too restrictive, and only
  returns `true` for functions explicitly created using `(fn ...)`.
  That precluded, for example, the idiomatic Clojure approach of using
  `:x` as a function to extract the _x_ coordinate of a head when
  defining a spatial parameter.

### Changed

- You no longer need to specify what kind of grid controller you are
  trying to bind to in advance; the controller manager in
  `afterglow.controllers` can recognize the supported controllers from
  their responses to the MIDI Device Inquiry message, and instantiate
  the appropriate binding. New controller implementations can register
  themselves when their namespaces are loaded so the controller
  manager will dispatch to them as needed.
- The code to gracefully shut down active controller bindings, which
  was becoming duplicated with every new controller mapping created,
  has been pulled up into the shared controllers namespace.
- The code to watch for and automatically bind to a controller when it
  appears in the MIDI environment has similarly been generalized and
  pulled into the shared controllers namespace.
- The ability to register an interest in all events from a specific
  MIDI device was added, and the controller mapping implementations
  were updated to take advantage of this, so they no longer need to
  receive and filter out all the events from other devices.
- The sample show is becoming a much more practical example of how to
  layer flexible color and dimmer cues, with good cue variables to add
  even more dimensions.
- A lot of repetitive code in the examples namespace was consolidated
  using helper functions.
- The `controllers/IOverlay` protocol was expanded to include the
  ability for an overlay to handle and absorb pitch-bend messages, in
  preparation for supporting the touch strip on the Ableton Push.
- The floating-point format for cue variables was changed from `float`
  to `double` since that is what Clojure actually natively uses.
- All other `float` values which were created throughout Afterglow
  were changed to `double` values, since that is what Clojure actually
  natively uses, and they were getting promoted when used anyway.

## [0.2.0] - 2016-02-02

### Added

- Running effects are now listed on the Show Control page of the Web
  UI, in descending priority order, with buttons to end them, and
  controls to adjust cue parameters, including colors.
- The show's dimmer grand master is now visible and controllable
  below the cue grid in the web UI.
- A rich grid controller mapping for the
  [Novation Launchpad Pro](https://us.novationmusic.com/launch/launchpad-pro#).
- A rich color picker interface on the Ableton Push for cues with
  color variables.
- You can now map sliders and encoders on any MIDI controller to
  adjust components of a color stored in a show variable (that is,
  adjust its red, green, and blue values, or its hue, saturation, and
  lightness).
- The example global color and strobe cues have been upgraded to take
  advantage of the new color cue parameters, so their colors can be
  adjusted on the fly using the web or Push interfaces.
- The Shift button can be used with Tap Tempo buttons on controllers
  and the web UI to set the start of a beat, bar, or phrase, depending
  on the synchronization level of the metronome.
- Buttons or pads on any MIDI controller can now easily be mapped to
  act like the smart Tap Tempo and Shift buttons on the Ableton Push
  and Novation Launchpad.
- Cues can have animated colors on grid controllers including the web
  interface, to help remind operators about the effects they launch,
  or reflect the current value of a color variable on which they are
  based.
- When binding a generic MIDI controller to launch a cue, if that
  controller does not have velocity-sensitive pads, you can assign an
  explicit velocity to use in the binding, for the purpose of setting
  any velocity-sensitive cue variables.
- An explanation of how to bind a MIDI controller to a dimmer master
  has been added to the mapping documentation.
- Animated GIFs in the documentation illustrate how the cue user
  interface works.

### Fixed

- Using dynamic color variables to as inputs for other dynamic color
  parameters could cause crashes when trying to adjust the incoming
  color values due to a longstanding subtle bug, which has been fixed.
- Ordinary MIDI control surfaces were sometimes being mistakenly
  identified as candidate sources of Tratktor beat phase information,
  leading to exceptions in the log file. Now only devices sending
  clock pulses are considered beat phase candidates.
- Cue colors were improved in the web interface and on the Push to
  make it easier to see which cues are active, as well as to make the
  colors more faithful to the cue's intent.
- The text labels on cues in the web interface are now more legible
  because they use a contrasting color based on the cell's perceived
  brightness.
- Incompatible cues are now identified not just from matching effect
  keys, but also from their `:end-keys` lists, so the web and
  controller interfaces provide even more guidance.
- Launching cues from the web interface was not setting any value for
  cue variables configured to be velocity sensitive, which was
  sometimes causing issues. Now the assignment of velocity-adjustable
  variables happens in the process of launching any cue from the grid,
  and a default velocity of 127 is assumed if none is specified.
- The entire frame of a user interface being rendered on a grid
  controller or the web interface now uses the same metronome
  snapshot, to represent a consistent point in time.
- Some testing in a Windows virtual machine revealed issues when
  working with standard Java Midi implementations (as opposed to
  CoreMidi4J on the Mac). These were addressed.

### Changed

- Updated to newly-released Clojure 1.8 for improved performance.
- Deprecated functions were removed:
  - `afterglow.effects.color/find-rgb-heads` (instead use `afterglow.channels/find-rgb-heads`)
  - `afterglow.effects.color/has-rgb-heads?` (instead use `afterglow.channels/has-rgb-heads`)
  - `afterglow.effects.oscillators/sawtooth-beat` (instead use `sawtooth`)
  - `afterglow.effects.oscillators/sawtooth-bar` (instead use `sawtooth` with `:interval :bar`)
  - `afterglow.effects.oscillators/sawtooth-phrase` (instead use `sawtooth` with `:interval :phrase`)
  - `afterglow.effects.oscillators/triangle-beat` (instead use `triangle`)
  - `afterglow.effects.oscillators/triangle-bar` (instead use `triangle` with `:interval :bar`)
  - `afterglow.effects.oscillators/triangle-phrase` (instead use `triangle` with `:interval :phrase`)
  - `afterglow.effects.oscillators/square-beat` (instead use `square`)
  - `afterglow.effects.oscillators/square-bar` (instead use `square` with `:interval :bar`)
  - `afterglow.effects.oscillators/square-phrase` (instead use `square` with `:interval :phrase`)
  - `afterglow.effects.oscillators/sine-beat` (instead use `sine`)
  - `afterglow.effects.oscillators/sine-bar` (instead use `sine` with `:interval :bar`)
  - `afterglow.effects.oscillators/sine-phrase` (instead use `sine` with `:interval :phrase`)
  - `afterglow.effects.params/build-oscillated-param` (instead use
  `afterglow.effects.oscillators/build-oscillated-param`)
  - `afterglow.show/add-midi-control-to-cue-mapping` (instead use
    `afterglow.effects.cues/add-midi-to-cue-mapping`)
  - `afterglow.show/remove-midi-control-to-cue-mapping` (instead use
    `afterglow.effects.cues/remove-midi-to-cue-mapping`)
- The detailed documentation was updated to use attributes to link to the API
  documentation so it could be linked to its release-specific version.
- The API documentation was moved into a github-pages branch so
  versioned snapshots can be kept around.
- A few functions were newly deprecated to improve the consistency of the API:
  - `afterglow.effects.cues/add-midi-control-to-cue-mapping` (instead
    use the more accurately named `add-midi-to-cue-mapping`)
  - `afterglow.effects.cues/remove-midi-control-to-cue-mapping`
    (instead use the more accurately named `remove-midi-to-cue-mapping`)
  - `afterglow.show/remove-midi-control-to-var-mapping`,
    `afterglow.show/remove-midi-control-to-master-mapping`, and
    `afterglow.show/remove-midi-control-metronome-mapping` (they are
    no longer needed, the general-purpose function
    `afterglow.midi/remove-control-mapping` works instead of each)

## [0.1.6] - 2016-01-11

### Added

- Support for [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J),
  to preferentially use MIDI devices returned by this new lightweight
  open-source Java MIDI service provider implementation for Mac OS X.
  CoreMIDI4J is compatible with current Java and OS versions, and
  addresses long-standing defects in the standard Java MIDI
  implementation, such as support for System Exclusive messages, and
  reconfiguration of the MIDI environment as devices are connected and
  disconnected. Afterglow's MIDI implementation now gracefully handles
  changes in the MIDI environment, cleaning up bindings, synced
  metronomes, grid controllers, and cue feedback functions associated
  with devices which no longer exist, and making new devices available
  for use.
- MIDI device watchers, which can set up bindings whenever a specified
  device is connected. These also allow effortless recovery from a
  temporary disconnection from a device during a show.
- Code cues, making it easy to trigger arbitrary activity from a cue
  grid, [issue 34](https://github.com/Deep-Symmetry/afterglow/issues/34).
- Links to graphs and expanded discussion in the oscillator API docs.
- Dimmer effects can now work with dimmer function ranges on
  multipurpose channels as well as full dedicated dimmer channels.
- Dimmer effects can now also create virtual dimmers for RGB-mixing
  fixtures that don't have any actual dimmer channels, allowing them
  to participate as if they did, by modifying the color effects being
  sent to them.
- Step parameters can now have interval ratios, like oscillators.
- When building step parameters, you can now use dynamic parameters as
  inputs.
- When mapping a MIDI control to a show variable, you can now supply a
  custom function to transform the incoming value into whatever you
  need it to be,
  [issue 32](https://github.com/Deep-Symmetry/afterglow/issues/32).
- When mapping a midi control to launch a cue, if your controller
  supports velocity (and perhaps also aftertouch, or polyphonic key
  pressure), you can have those values affect cue variables which have
  been defined as velocity sensitive, in the same way that Ableton
  Push pads do.
- A variation of the sparkle effect which uses dimmer channels,
  [issue 35](https://github.com/Deep-Symmetry/afterglow/issues/35).
- Some more examples of how to get started working with Afterglow.
- A variety of other documentation improvements.

### Changed

- Oscillators have been completely redesigned in order to be more
  flexible and easy to create and work with, and to support dynamic
  parameters so their configuration can vary over time or location,
  [issue 9](https://github.com/Deep-Symmetry/afterglow/issues/9). The old
  oscillator and oscillated parameter functions have been deprecated,
  and are now stubs wich delegate to the new implementation. They will
  be removed in an upcoming release.
- The functions `add-midi-control-to-cue-mapping` and
  `remove-midi-control-to-cue-mapping` have been moved from the
  `afterglow.show` namespace to `afterglow.effects.cues`, to solve a
  circular dependency conflict which arose in implementing velocity
  and aftertouch support. There are stubs in the old location which
  delegate to the new ones, but they are less efficient than calling
  them in the new location directly, and are deprecated. The stubs
  will be removed in an upcoming release.
- The former `IHeadParam` interface has been eliminated, folding its
  semantics into the `IParam` interface, and simplifying the
  implementation of dynamic parameters,
  [issue 20](https://github.com/Deep-Symmetry/afterglow/issues/20).
- The `:adjust-fn` parameter to `build-variable-param` has been
  renamed `:transform-fn` to be consistent with the equivalent
  mechanism added for MIDI control mappings in
  [issue 32](https://github.com/Deep-Symmetry/afterglow/issues/32). The
  documentation has been improved a bit as well.
- The maps which track MIDI bindings now use the underlying Java
  `MidiDevice` object for their keys, which allows for more efficent
  lookup than the `overtone.midi` `:midi-device` map which was
  previously used.
- The functions which add and remove bindings to MIDI control, note,
  and aftertouch messages have been simplified so they no longer
  require you to come up with a unique keyword to use when later
  removing the binding. Instead, you simply pass the same function
  that was used when establishing the binding to remove it.
- All functions which allow you to select a MIDI device have been made
  consistent, and now allow you to filter devices by a variety of
  criteria, not just the name and description.
- Various maps used to manage Afterglow state, such as shows, cue
  grids, Push controllers and auto-binding watchers, are now tagged
  with type metadata to make it easier to recognize them.

### Fixed

- Clicking on the BPM slider in the web interface now updates the BPM
  (previously you had to actually drag it),
  [issue 18](https://github.com/Deep-Symmetry/afterglow/issues/18).
- Launching `:held` cues from generic MIDI controllers, the Ableton
  Push, and the web interface, would not succeed if the previous
  effect created by the cue was still in the process of ending,
  [issue 33](https://github.com/Deep-Symmetry/afterglow/issues/33).
- Make sure MIDI inputs are connected when `sync-to-midi-clock` is
  called,
  [issue 10](https://github.com/Deep-Symmetry/afterglow/issues/10).
- Also make sure the MIDI inputs are opened when rendering the web UI,
  so that the sync button will be able to list available sources of
  MIDI clock messages.
- Clarified that syncing to Traktor beat phase still requires Traktor
  to be configured to send MIDI clock,
  [issue 37](https://github.com/Deep-Symmetry/afterglow/issues/37).
- Added more detail about how to safely import and configure the
  Afterglow Traktor device mapping.
- A variety of issues ranging from questionable style through misplaced
  documentation, unused or inaccessible code, preconditions that would
  not take effect, and actual problems, were identified by Kibit and
  Eastwood (after discovering how to work around a crash in Eastwood
  caused by the protocol definitions in `rhythm.clj`), were cleaned
  up.

## [0.1.5] - 2015-11-25

### Added

- Chases, which support sequences of effects with a variety of timing,
  fade, and looping options.
- Step parameters, which provide flexible control of chases.
- Pan/Tilt effects, which work at a lower level than direction
  effects, but since they are closer to the physical capabilities of
  the lights, can be helpful in creating natural and intuitive
  movements. They also help avoid issues with geometric singularities
  when fading between different directions.
- Graphs to visually illustrate the available oscillators and the
  parameters that tune their behavior.

### Changed

- Fades now delegate their notion of ending to the underlying effects
  which are being faded between, and pass end requests along to them.
- Stopped embedding `cider-nrepl` because it added too much bloat and
  complexity for an unlikely use case. If you want to work with CIDER
  for live-coding with Afterglow, launch it from a project, rather
  than as an überjar.

### Fixed

- Some MIDI controllers (perhaps those which sent messages on channels
  other than 0?) were causing Overtone's
  [midi-clj](https://github.com/overtone/midi-clj) library to create
  message maps with `nil` values for the `:status` key when sending
  control-change or note messages, which was preventing them from
  being detected or processed correctly. Afterglow now always looks
  for command-like messages via the `:command` key instead,
  [issue 8](https://github.com/Deep-Symmetry/afterglow/issues/8).
- Fading colors in and out from nothing, as represented by a `nil`
  assignment value, was fading to a desaturated version of black,
  which does not lead to the kind of results people generally expect
  and want. In this situation, the color is now faded to or from a
  darkened version of itself.
- The phases of the square-bar and square-phrase oscillators were
  flipped from what they should be according to the documentation, and
  compared with square-beat. This was discovered and corrected when
  graphing them.
- Calculation of white LED channel for colors with lightness less than
  50 was wrong, leading to slight unintentional desaturation of
  colors.
- It was a little too hard to see the difference between the "ready"
  and "active" states for some colors on the Ableton Push after
  introducing full RGB button color support; they are once again more
  visually distinct.
- Preconditions in channel-creation functions for fixture definitions
  were mal-formed, and so were not actually validating the function
  arguments.
- Parts of the introductory walk-through in `README.md` had become
  stale and needed to be updated.
- The API documentation for `patch-fixture!` was fleshed out.

## [0.1.4] - 2015-09-27
### Added
- Support for inverted dimmers (where lower DMX values are brighter).
- Scenes, which allow multiple effects to be grouped into one.
- A framework for fading between effect elements, with sensible
  semantics for colors, aim, directions, and functions, and defaults
  when fading in or out of nothing.
- Fading between entire effects, including complex effects and scenes,
  which do not necessarily affect all the same fixtures and channels.
- A new mechanism for extending the rendering loop to support effects
  which do not result in DMX values to send to the show universes.
- Support for (and examples of) integration with laser shows being run
  by Pangolin Beyond software, using the extension mechanism.
- New conditional effects and variable-setting effects, using the
  extension mechanism.
- A composable effect which can transform the colors being created by
  other effects to build layered looks. The default transformation
  causes the colors to range from fully saturated at the start of each
  beat to pure gray by the end of the beat, but it is very easy to
  swap in other transformations using oscillated parameters.
- Holding down the Shift key while turning the encoder allows the BPM
  to be changed more rapidly (in whole beat increments, rather than
  tenths) on the Ableton Push.
- Fixture definitions for Chauvet LED Techno Strobe, LED Techno Strobe
  RGB, ColorStrip, Spot LED 150, Kinta X, Scorpion Storm FX RGB,
  Scorpion Storm RGX, Q-Spot 160, Intimidator Scan LED 300, Geyser RGB
  fogger, and Hurricane 1800 Flex fogger.
- Example effect which desaturates a rainbow over the course of a
  beat.

### Changed
- Improved readability and parallelism of core rendering loop.
- The default frame rate was raised from 30Hz to 40Hz.
- Ableton Push now uses SysEx message to specify the exact RGB color
  to light up a pad, rather than choosing from the limited set
  available through MIDI velocity.
- Ableton Push now makes sure the pads are put in poly-pressure mode,
  and sets the sensitivity level to reduce the chance of stuck pads.
- The stability of MIDI clock sync was greatly improved, in order to
  facilitate the Beyond integration.
- The refresh rates of the Push and web interfaces were reduced to put
  less load on the CPU.
- The tempo buttons on the Push and web interfaces are now always
  flashed at least once per beat, even if the reduced refresh rate
  causes the normal "on" window to be missed.
- Improved content and format of command-line usage help.

### Fixed
- The Ableton Push binding now ends cues when it receives an afertouch
  value of 0, since the hardware is not reliably sending a note-end
  message, especially when multiple pads are being pressed at once.
- Fail gracefully when trying to bind to an Ableton Push when none can
  be found.
- Some small errors in the documentation were corrected.

## [0.1.3] - 2015-08-16

### Added
- Ability to
  [translate](https://github.com/Deep-Symmetry/afterglow/blob/master/doc/fixture_definitions.adoc#translating-qlc-fixture-definitions)
  fixture definitions from the format used by
  [QLC+](http://www.qlcplus.org/) to help people get started on
  defining fixtures.

### Changed
- Separated OLA communication into its own project,
  [ola-clojure](https://github.com/Deep-Symmetry/ola-clojure#ola-clojure).

## [0.1.2] - 2015-08-09
### Added
- Allow configuration of an alternate host for the OLA daemon
  (primarily for Windows users, since there is not yet a Windows port
  of OLA).
- Flesh out the command-line arguments when running as an executable
  jar.
- Allow a list of files to be loaded at startup when running as an
  executable jar, in order to configure fixtures, shows, effects, and
  cues.
- Support syncing to Traktor’s beat grid with the help of a new custom
  controller mapping.

### Changed
- MIDI sync sources are now always watched for, and can be offered to
  the user without pausing first.

## [0.1.1] - 2015-08-02
### Added
- Ability to register for notification about changes in status of cues
  and values of show variables.
- Creating a show can optionally register it with the web interface by
  passing a description with `:description`.
- Now cleans up thread-local bindings stored by the web REPL when
  sessions time out.

### Changed
- Forked Protobuf related libraries to make them build Java 6
  compatible clases, so
  [afterglow-max](https://github.com/Deep-Symmetry/afterglow-max) can run
  inside the Java environment provided by
  [Cycling ‘74’s Max](https://cycling74.com/).
- Made the meaning of the `:start` attribute of cue variables simpler
  and more consistent.
- Cue variables which respond to aftertouch now also respond to
  initial velocity, and the related configuration attributes have been
  renamed to `:velocity` to reflect this increased generality.
- Improved the detection of project name and version number so they
  work for afterglow-max builds too.

### Fixed
- Eliminated crashes in the Ableton Push interface when trying to
  adjust the value of a cue variable which had not yet been set to
  anything.

## 0.1.0 - 2015-07-19
### Added
- Initial Public Release


[unreleased]: https://github.com/Deep-Symmetry/afterglow/compare/v0.2.4...HEAD
[0.2.4]: https://github.com/Deep-Symmetry/afterglow/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/Deep-Symmetry/afterglow/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/Deep-Symmetry/afterglow/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/Deep-Symmetry/afterglow/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/Deep-Symmetry/afterglow/compare/v0.1.6...v0.2.0
[0.1.6]: https://github.com/Deep-Symmetry/afterglow/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/Deep-Symmetry/afterglow/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/Deep-Symmetry/afterglow/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/Deep-Symmetry/afterglow/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/Deep-Symmetry/afterglow/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/Deep-Symmetry/afterglow/compare/v0.1.0...v0.1.1
