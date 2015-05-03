(ns afterglow.util
  "Utility functions that are likely to be widely useful"
  {:author "James Elliott"})

(defn ubyte
  "Convert small integer to its signed byte equivalent. Necessary for convenient handling of DMX values
  in the range 0-255, since Java does not have unsigned numbers."
  [val]
   (if (>= val 128)
     (byte (- val 256))
     (byte val)))

(defn unsign
  "Convert a signed byte to its unsigned int equivalent, in the range 0-255."
  [val]
  (bit-and val 0xff))
