(ns afterglow.coremidi4j
  (:import [uk.co.xfactorylibrarians.coremidi4j CoreMidiDeviceProvider CoreMidiNotification]))

(defn add-environment-change-handler
  "Arranges for the supplied function to be called whenever the MIDI
  environment changes, in other words when a MIDI device is added or
  removed. This namespace can only be loaded if
  the [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J) extension
  is present in the Java extensions directory.

  Returns `true` if the handler was added, or `false` if the
  CoreMIDI4J extension was unable to load its native library, and so
  is inactive."
  [f]
  (when (CoreMidiDeviceProvider/isLibraryLoaded)
    (CoreMidiDeviceProvider/addNotificationListener
     (reify CoreMidiNotification (midiSystemUpdated [this] (f))))
    true))
