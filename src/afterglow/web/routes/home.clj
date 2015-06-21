(ns afterglow.web.routes.home
  (:require [afterglow.web.layout :as layout]
            [afterglow.web.routes.show-control :as show-control]
            [afterglow.web.routes.visualizer :as visualizer]
            [afterglow.show :as show]
            [compojure.core :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]
            [clojure.java.io :as io]))

(defn home-page []
  (let [shows (map (fn [[show description]]
                     {:show show :description description})
                   (vals @show/shows))]
    (layout/render
     "home.html" {:shows shows
                  :docs (-> "docs/docs.md" io/resource slurp)})))

(defn about-page []
  (layout/render "about.html"))

(defn visualizer-page []
  (layout/render "visualizer.html"))

(defroutes home-routes
  (GET "/" [] (home-page))
  (GET "/show/:id" [id] (show-control/show-page id))
  (GET "/ui-updates/:id" [id :as req] (show-control/ui-updates id req))
  (GET "/about" [] (about-page))
  (GET "/visualizer" [] (visualizer/page))
  (GET "/visualizer-update" [] (visualizer/update-preview))
  (GET "/shaders/fragment.glsl" [] (visualizer/shader)))




