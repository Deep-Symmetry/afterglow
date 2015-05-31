(ns afterglow.web.routes.home
  (:require [afterglow.web.layout :as layout]
            [afterglow.web.routes.visualizer :as visualizer]
            [compojure.core :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]
            [clojure.java.io :as io]))

(defn home-page []
  (layout/render
    "home.html" {:docs (-> "docs/docs.md" io/resource slurp)}))

(defn about-page []
  (layout/render "about.html"))

(defn visualizer-page []
  (layout/render "visualizer.html"))

(defroutes home-routes
  (GET "/" [] (home-page))
  (GET "/about" [] (about-page))
  (GET "/visualizer" [] (visualizer/page))
  (GET "/visualizer-update" [] (visualizer/update))
  (GET "/shaders/fragment.glsl" [] (visualizer/shader)))




