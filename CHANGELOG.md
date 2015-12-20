# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Added

- Code cues, making it easy to trigger arbitrary activity from a cue
  grid, [issue 34](https://github.com/brunchboy/afterglow/issues/34).
- Links to graphs and expanded discussion in the oscillator API docs.
- Dimmer effects can now work with dimmer function ranges on
  multipurpose channels as well as full dedicated dimmer channels.
- When mapping a MIDI control to a show variable, you can now supply a
  custom function to transform the incoming value into whatever you
  need it to be,
  [issue 32](https://github.com/brunchboy/afterglow/issues/32).
- A variation of the sparkle effect which uses dimmer channels,
  [issue 35](https://github.com/brunchboy/afterglow/issues/35).
- Some more examples of how to get started working with Afterglow.

### Changed

- Oscillators have been completely redesigned in order to be more
  flexible and easy to create and work with, and to support dynamic
  parameters so their configuration can vary over time or location,
  [issue 9](https://github.com/brunchboy/afterglow/issues/9). The old
  oscillator and oscillated parameter functions have been deprecated,
  and are now stubs wich delegate to the new implementation. They will
  be removed in an upcoming release.
- The former IHeadParam interface has been eliminated, folding its
  semantics into the IParam interface, and simplifying the
  implementation of dynamic parameters,
  [issue 20](https://github.com/brunchboy/afterglow/issues/20).
- The `:adjust-fn` parameter to `build-variable-param` has been
  renamed `:transform-fn` to be consistent with the equivalent
  mechanism added for MIDI control mappings in
  [issue 32](https://github.com/brunchboy/afterglow/issues/32). The
  documentation has been improved a bit as well.

### Fixed

- Clicking on the BPM slider in the web interface now updates the BPM
  (previously you had to actually drag it),
  [issue 18](https://github.com/brunchboy/afterglow/issues/18).
- Make sure MIDI inputs are connected when `sync-to-midi-clock` is
  called,
  [issue 10](https://github.com/brunchboy/afterglow/issues/10).
- Also make sure the MIDI inputs are opened when rendering the web UI,
  so that the sync button will be able to list available sources of
  MIDI clock messages.
- Clarified that syncing to Traktor beat phase still requires Traktor
  to be configured to send MIDI clock,
  [issue 37](https://github.com/brunchboy/afterglow/issues/37).
- Added more detail about how to safely import and configure the
  Afterglow Traktor device mapping.
- A variety of issues ranging from questionable style through misplaced
  documentation, unused or inaccessible code, preconditions that would
  not take effect, and actual problems, were identified by Kibit and
  Eastwood (after discovering how to work around a crash in Eastwood
  caused by the protocol definitions in `rhythm.clj`), were cleaned
  up.

## [0.1.5] - 2015-01-25

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
  [issue 8](https://github.com/brunchboy/afterglow/issues/8).
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
  [translate](https://github.com/brunchboy/afterglow/blob/master/doc/fixture_definitions.adoc#translating-qlc-fixture-definitions)
  fixture definitions from the format used by
  [QLC+](http://www.qlcplus.org/) to help people get started on
  defining fixtures.

### Changed
- Separated OLA communication into its own project,
  [ola-clojure](https://github.com/brunchboy/ola-clojure#ola-clojure).

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
  [afterglow-max](https://github.com/brunchboy/afterglow-max) can run
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


[unreleased]: https://github.com/brunchboy/afterglow/compare/v0.1.5...HEAD
[0.1.5]: https://github.com/brunchboy/afterglow/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/brunchboy/afterglow/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/brunchboy/afterglow/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/brunchboy/afterglow/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/brunchboy/afterglow/compare/v0.1.0...v0.1.1
