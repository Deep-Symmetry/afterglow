(defproject afterglow "0.1.0-SNAPSHOT"
  :description "A functional lighting controller working with Overtone and the Open Lighting Architecture"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.cache "0.6.3"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [overtone "0.9.1"]
                 [org.flatland/protobuf "0.8.1"]
                 [com.taoensso/timbre "3.3.1"]]
  :main ^:skip-aot afterglow.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :plugins [[lein-ancient "0.6.5"]
            [lein-protobuf "0.4.2"]]
  :min-lein-version "2.0.0")
