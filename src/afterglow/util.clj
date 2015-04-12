(ns
    ^{:doc "Utility functions that are likely to be widely useful"
      :author "James Elliott"}
  afterglow.util)

(defn ubyte
  "Convert an integer to its unsigned byte equivalent. Necessary for convenient handling of DMX values
  in the range 0-255, since Java does not have unsigned numbers."
  [val]
   (if (>= val 128)
     (byte (- val 256))
     (byte val)))
