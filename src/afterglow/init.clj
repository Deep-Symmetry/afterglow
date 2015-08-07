(ns afterglow.init
  "This namespace is the context in which any init-files specified on
  the command line will be loaded during startup, in case they forget
  to establish their own namespaces."
  (:require [afterglow.show-context :refer [*show* set-default-show!]]))
