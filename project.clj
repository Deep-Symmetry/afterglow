(defproject afterglow "0.2.5-SNAPSHOT"
  :description "A live-coding environment for light shows, built on the Open Lighting Architecture, using bits of Overtone."
  :url "https://github.com/brunchboy/afterglow"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-Dapple.awt.UIElement=true"]  ; Suppress dock icon and focus stealing when compiling on a Mac.
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.cache "0.7.1"]
                 [org.clojure/core.async "0.4.474" :exclusions [org.clojure/tools.reader]]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/data.zip "0.1.2"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.cli "0.3.7"]
                 [org.clojure/tools.nrepl "0.2.13"]
                 [org.clojure/tools.reader "1.3.0"]
                 [org.deepsymmetry/beat-link "0.3.7"]
                 [org.deepsymmetry/wayang "0.1.7"]
                 [java3d/vecmath "1.3.1"]
                 [java3d/j3d-core "1.3.1"]
                 [java3d/j3d-core-utils "1.3.1"]
                 [overtone/at-at "1.2.0"]
                 [overtone/midi-clj "0.5.0"]
                 [overtone/osc-clj "0.9.0"]
                 [uk.co.xfactory-librarians/coremidi4j "1.1"]
                 [amalloy/ring-buffer "1.2.1" :exclusions [org.clojure/tools.reader
                                                           com.google.protobuf/protobuf-java]]
                 [com.climate/claypoole "1.1.4"]
                 [org.clojars.brunchboy/protobuf "0.8.3"]
                 [ola-clojure "0.1.8" :exclusions [org.clojure/tools.reader]]
                 [selmer "1.12.0" :exclusions [cheshire]]
                 [com.evocomputing/colors "1.0.4"]
                 [environ "1.1.0"]
                 [camel-snake-kebab "0.4.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.fzakaria/slf4j-timbre "0.3.12"]
                 [com.taoensso/tower "3.0.2"]
                 [com.taoensso/truss "1.5.0"]
                 [markdown-clj "1.0.2"]
                 [ring/ring-core "1.6.3"]
                 [compojure "1.6.1" :exclusions [org.eclipse.jetty/jetty-server
                                                 clj-time
                                                 ring/ring-core
                                                 ring/ring-codec]]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-session-timeout "0.2.0"]
                 [ring-middleware-format "0.7.2" :exclusions [ring/ring-jetty-adapter
                                                              cheshire
                                                              org.clojure/tools.reader
                                                              org.clojure/java.classpath
                                                              org.clojure/core.memoize
                                                              com.fasterxml.jackson.core/jackson-core]]
                 [metosin/ring-http-response "0.9.0"]
                 [prone "1.6.0"]
                 [buddy "2.0.0"]
                 [instaparse "1.4.9"]
                 [http-kit "2.3.0"]]
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

  :profiles {:dev {:dependencies [[ring-mock "0.1.5" :exclusions [ring/ring-codec]]
                                  [ring/ring-devel "1.6.3"]]
                   :repl-options {:init-ns afterglow.examples
                                  :welcome (println "afterglow loaded.")}
                   :jvm-opts ["-XX:-OmitStackTraceInFastThrow" "-Dapple.awt.UIElement=true"]
                   :env {:dev "true"}}
             :uberjar {:env {:production "true"}
                       :aot :all}}
  :plugins [[lein-codox "0.10.4"]
            [lein-dash "0.2.1"]
            [lein-environ "1.1.0"]]

  :codox {:output-path "api-doc"
          :source-uri "https://github.com/brunchboy/afterglow/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}}
  :min-lein-version "2.0.0")
