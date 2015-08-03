(defproject afterglow "0.1.2-SNAPSHOT"
  :description "A live-coding environment for light shows, built on the Open Lighting Architecture, using bits of Overtone."
  :url "https://github.com/brunchboy/afterglow"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.cache "0.6.4"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [org.clojure/tools.cli "0.3.2"]
                 [cider/cider-nrepl "0.9.1"]
                 [java3d/vecmath "1.3.1"]
                 [java3d/j3d-core "1.3.1"]
                 [java3d/j3d-core-utils "1.3.1"]
                 [overtone/at-at "1.2.0"]
                 [overtone/midi-clj "0.5.0"]
                 [overtone/osc-clj "0.9.0"]
                 [amalloy/ring-buffer "1.1"]
                 [com.climate/claypoole "1.0.0"]
                 [org.clojars.brunchboy/protobuf "0.8.3"]
                 [selmer "0.8.7"]
                 [com.evocomputing/colors "1.0.2"]
                 [environ "1.0.0"]
                 [com.taoensso/timbre "4.0.2"]
                 [com.taoensso/tower "3.0.2"]
                 [markdown-clj "0.9.67"]
                 [environ "1.0.0"]
                 [compojure "1.4.0" :exclusions [org.eclipse.jetty/jetty-server]]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-session-timeout "0.1.0"]
                 [metosin/ring-middleware-format "0.6.0" :exclusions [ring/ring-jetty-adapter
                                                                      org.clojure/tools.reader
                                                                      org.clojure/java.classpath]]
                 [metosin/ring-http-response "0.6.3"]
                 [prone "0.8.2"]
                 [buddy "0.6.0"]
                 [instaparse "1.4.1"]
                 [http-kit "2.1.19"]]
  :source-paths ["src" "generated"]
  :prep-tasks [["with-profile" "+gen,+dev" "run" "-m" "afterglow.src-generator"] "protobuf" "javac" "compile"]

  :main afterglow.core

  :target-path "target/%s"
  :uberjar-name "afterglow.jar"
  :manifest {"Name" ~#(str (clojure.string/replace (:group %) "." "/")
                            "/" (:name %) "/")
             "Package" ~#(str (:group %) "." (:name %))
             "Specification-Title" ~#(:name %)
             "Specification-Version" ~#(:version %)}

  ;; enable to start the nREPL server when the application launches
  ;; :env {:repl-port 16002}

  :profiles {:dev {:dependencies [[ring-mock "0.1.5"]
                                  [ring/ring-devel "1.4.0"]]
                   :source-paths ["dev_src"]
                   :resource-paths ["dev_resources"]
                   :repl-options {:init-ns afterglow.examples
                                  :welcome (println "afterglow loaded.")}
                   :env {:dev true}}

             :gen {:prep-tasks ^:replace ["protobuf" "javac" "compile"]}

             :uberjar {:env {:production true}
                       :aot :all}}
  :plugins [[org.clojars.brunchboy/lein-protobuf "0.4.3" :exclusions [leinjacker]]
            [codox "0.8.12"]
            [lein-environ "1.0.0"]
            [lein-ancient "0.6.7"]]

  :aliases {"gen" ["with-profile" "+gen,+dev" "run" "-m" "afterglow.src-generator"]}

  :codox {:src-dir-uri "http://github.com/brunchboy/afterglow/blob/master/"
          :src-linenum-anchor-prefix "L"
          :output-dir "target/doc"}
  :min-lein-version "2.0.0")
