# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]
### Changed
- A lot. TODO: Flesh this out!

## [0.1.3] - 2015-08-16
### Added
- Added ability to
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


[unreleased]: https://github.com/brunchboy/afterglow/compare/v0.1.3...HEAD
[0.1.3]: https://github.com/brunchboy/afterglow/compare/v0.1.3...v0.1.2
[0.1.2]: https://github.com/brunchboy/afterglow/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/brunchboy/afterglow/compare/v0.1.0...v0.1.1
