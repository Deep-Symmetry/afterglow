(ns afterglow.show-context
  "Establishes a notion of the _current show_ using the dynamic var
  `*show*`, to save having to pass it as a parameter to dozens of
  functions in the Afterglow API. This needs to be bound to a
  value for many Afterglow functions to work.

  The current show can be set locally using [[with-show]], which is
  what you should do when you have multiple light shows active.
  However, in the extremely common case of defining and running only a
  single lighr show, you can also establish a default show,
  using [[set-default-show!]], and omit even the `with-show` wrappers."
  {:author "James Elliott"
   :doc/format :markdown})

(defonce
  ^{:dynamic true
    :doc "The light show on which Afterglow functions should operate,
  defined as a dynamic variable so it does not need to be passed as an
  argument to dozens of functions. The current show can be established
  locally using [[with-show]], or, if you are only creating and using a
  single light show, which is a very common situation, you can
  globally establish a default value by
  calling [[set-default-show!]]."
    :doc/format :markdown}
  *show* nil)

(defmacro with-show
  "Execute the body in the context of the specified light show, so
  Afterglow knows what show should be affected by its statements."
  [show & body]
  `(binding [*show* ~show]
     ~@body))

(defn ^{:doc/format :markdown} set-default-show!
  "Establish the specified show as the default, so that functions that
  have not been wrapped inside [[with-show]] contexts will act on it.
  This makes sense when you are only working with one light show and
  do not want to have to use `with-show` all the time."
  [show]
  (alter-var-root #'*show* (constantly show)))
