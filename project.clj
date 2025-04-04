(defproject afterglow :lein-v
  :description "A live-coding environment for light shows, built on the Open Lighting Architecture, using bits of Overtone."
  :url "https://github.com/Deep-Symmetry/afterglow"
  :license {:name "Eclipse Public License 2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :jvm-opts ["-Dapple.awt.UIElement=true"]  ; Suppress dock icon and focus stealing when compiling on a Mac.
  :dependencies [[org.clojure/clojure "1.11.4"]
                 [org.clojure/core.cache "1.1.234"]
                 [org.clojure/core.async "1.6.681" :exclusions [org.clojure/tools.reader]]
                 [org.clojure/data.json "2.5.0"]
                 [org.clojure/data.zip "1.1.0"]
                 [org.clojure/math.numeric-tower "0.1.0"]
                 [org.clojure/tools.cli "1.1.230"]
                 [org.clojure/tools.nrepl "0.2.13"]
                 [org.clojure/tools.reader "1.4.2"]
                 [org.deepsymmetry/beat-link "8.0.0-SNAPSHOT" :exclusions [org.slf4j/slf4j-api]]
                 [org.deepsymmetry/lib-carabiner "1.2.0"]
                 [org.deepsymmetry/wayang "0.1.8"]
                 [java3d/vecmath "1.3.1"]
                 [java3d/j3d-core "1.3.1"]
                 [java3d/j3d-core-utils "1.3.1"]
                 [overtone/at-at "1.3.58"]
                 [overtone/midi-clj "0.5.0"]
                 [overtone/osc-clj "0.9.0"]
                 [uk.co.xfactory-librarians/coremidi4j "1.6"]
                 [amalloy/ring-buffer "1.3.1" :exclusions [org.clojure/tools.reader
                                                           com.google.protobuf/protobuf-java]]
                 [com.climate/claypoole "1.1.4"]
                 [org.clojars.brunchboy/protobuf "0.8.3"]
                 [ola-clojure "0.1.8" :exclusions [org.clojure/tools.reader]]
                 [selmer "1.12.61" :exclusions [cheshire]]
                 [com.evocomputing/colors "1.0.6"]
                 [environ "1.2.0"]
                 [camel-snake-kebab "0.4.3"]
                 [com.taoensso/timbre "5.2.1"]
                 [com.taoensso/tufte "2.2.0"]
                 [com.fzakaria/slf4j-timbre "0.3.21"]
                 [com.taoensso/tower "3.0.2"]
                 [com.taoensso/truss "1.6.0"]
                 [markdown-clj "1.12.1"]
                 [ring/ring-core "1.12.2"]
                 [clj-time "0.15.2"]
                 [compojure "1.7.1" :exclusions [org.eclipse.jetty/jetty-server
                                                 ring/ring-core
                                                 ring/ring-codec]]
                 [ring/ring-defaults "0.5.0"]
                 [ring/ring-session-timeout "0.3.0"]
                 [ring-middleware-format "0.7.5" :exclusions [ring/ring-jetty-adapter
                                                              cheshire
                                                              org.clojure/tools.reader
                                                              org.clojure/java.classpath
                                                              org.clojure/core.memoize
                                                              com.fasterxml.jackson.core/jackson-core]]
                 [metosin/ring-http-response "0.9.4"]
                 [prone "2021-04-23"]
                 [buddy "2.0.0"]
                 [instaparse "1.5.0"]
                 [http-kit "2.8.0"]]
  :repositories {"sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}
  :main afterglow.core
  :uberjar-name "afterglow.jar"

  ;; Add project name and version information to jar file manifest
  :manifest {"Name"                  ~#(str (clojure.string/replace (:group %) "." "/")
                                            "/" (:name %) "/")
             "Package"               ~#(str (:group %) "." (:name %))
             "Specification-Title"   ~#(:name %)
             "Specification-Version" ~#(:version %)}
  :deploy-repositories [["snapshots" :clojars
                         "releases" :clojars]]

  ;; enable to start the nREPL server when the application launches
  ;; :env {:repl-port 16002}

  :profiles {:dev     {:dependencies [[ring-mock "0.1.5" :exclusions [ring/ring-codec]]
                                      [ring/ring-devel "1.12.2"]]
                       :repl-options {:init-ns afterglow.examples
                                      :welcome (println "afterglow loaded.")}
                       :jvm-opts     ["-XX:-OmitStackTraceInFastThrow" "-Dapple.awt.UIElement=true"]
                       :env          {:dev "true"}}
             :uberjar {:env        {:production "true"}
                       :prep-tasks ["javac"
                                    "compile"]
                       :aot        :all}
             :web-docs {:prep-tasks ^:replace []}}
  :plugins [[lein-codox "0.10.8"]
            [lein-resource "17.06.1"]
            [lein-environ "1.2.0"]
            [lein-shell "0.5.0"]
            [com.roomkey/lein-v "7.2.0"]]

  :middleware [lein-v.plugin/middleware]

  :codox {:output-path "target/codox"
          :doc-files   []
          :source-uri  "https://github.com/Deep-Symmetry/afterglow/blob/main/{filepath}#L{line}"
          :metadata    {:doc/format :markdown}}

  :resource {:resource-paths [["target/codox"
                               {:target-path  "target/classes/api_doc" ; For embedded use
                                :extra-values {:guide-url "http:/guide/afterglow/"}}]
                              ["target/codox"
                               {:target-path  "doc/build/site/api" ; For hosting on the web
                                :extra-values {:guide-url "https://afterglow-guide.deepsymmetry.org/afterglow/"}}]]}

  ;; Perform the tasks which embed the developer guide and api docs before compilation,
  ;; so they will be available both in development, and in the distributed archive.
  :prep-tasks [["shell" "npm" "run" "local-docs" ]
               "codox"
               "resource"
               ["v" "cache" "resources/afterglow" "edn"]]

  :min-lein-version "2.0.0")
