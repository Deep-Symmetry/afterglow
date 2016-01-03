(ns afterglow.coremidi4j
  (:import [uk.co.xfactorylibrarians.coremidi4j CoreMidiDeviceProvider CoreMidiNotification]))

(defn add-environment-change-handler
  "Arranges for the supplied function to be called whenever the MIDI
  environment changes, in other words when a MIDI device is added or
  removed. This namespace can only be loaded if
  the [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J) extension
  is present in the Java extensions directory."
  [f]
  (CoreMidiDeviceProvider/addNotificationListener
   (reify CoreMidiNotification
     (midiSystemUpdated [this] (f)))))
