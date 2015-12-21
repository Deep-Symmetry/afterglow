(defproject afterglow "0.1.6-SNAPSHOT"
  :description "A live-coding environment for light shows, built on the Open Lighting Architecture, using bits of Overtone."
  :url "https://github.com/brunchboy/afterglow"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-Dapple.awt.UIElement=true"]  ; Suppress dock icon and focus stealing when compiling on a Mac.
  :dependencies [[org.clojure/clojure "1.8.0-RC3"]
                 [org.clojure/core.cache "0.6.4"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojure/tools.reader "0.10.0"]
                 [java3d/vecmath "1.3.1"]
                 [java3d/j3d-core "1.3.1"]
                 [java3d/j3d-core-utils "1.3.1"]
                 [overtone/at-at "1.2.0"]
                 [overtone/midi-clj "0.5.0"]
                 [overtone/osc-clj "0.9.0"]
                 [amalloy/ring-buffer "1.2" :exclusions [org.clojure/tools.reader
                                                         com.google.protobuf/protobuf-java]]
                 [com.climate/claypoole "1.1.0"]
                 [org.clojars.brunchboy/protobuf "0.8.3"]
                 [ola-clojure "0.1.3-SNAPSHOT"]
                 [selmer "0.9.5"]
                 [com.evocomputing/colors "1.0.3"]
                 [environ "1.0.1"]
                 [camel-snake-kebab "0.3.2"]
                 [com.taoensso/timbre "4.1.4"]
                 [com.taoensso/tower "3.0.2"]
                 [markdown-clj "0.9.82"]
                 [compojure "1.4.0" :exclusions [org.eclipse.jetty/jetty-server
                                                 clj-time]]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-session-timeout "0.1.0"]
                 [metosin/ring-middleware-format "0.6.0" :exclusions [ring/ring-jetty-adapter
                                                                      org.clojure/tools.reader
                                                                      org.clojure/java.classpath]]
                 [metosin/ring-http-response "0.6.5"]
                 [buddy "0.8.2"]
                 [prone "0.8.3"]
                 [instaparse "1.4.1"]
                 [http-kit "2.1.19"]]
  :main afterglow.core
  :uberjar-name "afterglow.jar"
  :manifest {"Name" ~#(str (clojure.string/replace (:group %) "." "/")
                            "/" (:name %) "/")
             "Package" ~#(str (:group %) "." (:name %))
             "Specification-Title" ~#(:name %)
             "Specification-Version" ~#(:version %)}
  :deploy-repositories [["snapshots" :clojars
                         "releases" :clojars]]

  ;; enable to start the nREPL server when the application launches
  ;; :env {:repl-port 16002}

  :profiles {:dev {:dependencies [[ring-mock "0.1.5"]
                                  [ring/ring-devel "1.4.0"]]
                   :repl-options {:init-ns afterglow.examples
                                  :welcome (println "afterglow loaded.")}
                   :jvm-opts ["-XX:-OmitStackTraceInFastThrow" "-Dapple.awt.UIElement=true"]
                   :env {:dev true}}
             :uberjar {:env {:production true}
                       :aot :all}}
  :plugins [[lein-codox "0.9.0"]
            [lein-environ "1.0.1"]]

  :codox {:source-uri "http://github.com/brunchboy/afterglow/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}}
  :min-lein-version "2.0.0")
