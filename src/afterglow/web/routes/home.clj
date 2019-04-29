(ns afterglow.web.routes.home
  (:require [afterglow.show :as show]
            [afterglow.version :as version]
            [afterglow.web.layout :as layout]
            [afterglow.web.routes.show-control :as show-control]
            [afterglow.web.routes.visualizer :as visualizer]
            [afterglow.web.routes.web-repl :as web-repl]
            [clojure.java.io :as io]
            [compojure.core :as compojure]
            [compojure.route :as route]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [ring.util.response :as response]))

(defn home-page []
  (let [shows (map (fn [[show description]]
                     {:show show :description description})
                   (vals @show/shows))]
    (layout/render
     "home.html" {:shows shows
                  :docs (-> "docs/docs.md" io/resource slurp)
                  :version (str (version/title) " " (version/tag))
                  :csrf-token *anti-forgery-token*})))

(defn about-page []
  (layout/render "about.html" {:csrf-token *anti-forgery-token*}))

(defn visualizer-page []
  (layout/render "visualizer.html" {:csrf-token *anti-forgery-token*}))

(compojure/defroutes home-routes
  (compojure/GET "/" [] (home-page))
  (compojure/GET "/show/:id" [id] (show-control/show-page id))
  (compojure/GET "/ui-updates/:id" [id] (show-control/get-ui-updates id))
  (compojure/POST "/ui-event/:id/:kind" [id kind :as req] (show-control/post-ui-event id kind req))
  (compojure/GET "/console" [] (web-repl/page))
  (compojure/POST "/console" [:as req] (web-repl/handle-command req))
  (compojure/GET "/guide" [] (response/redirect "/guide/index.html"))
  (route/resources "/guide/" {:root "developer_guide"})
  (compojure/GET "/api-doc" [] (response/redirect "/api-doc/index.html"))
  (route/resources "/api-doc/" {:root "api_doc"})
  (compojure/GET "/about" [] (about-page))
  (compojure/GET "/visualizer/:id" [id] (visualizer/page id))
  (compojure/GET "/visualizer-update/:id" [id] (visualizer/update-preview id))
  (compojure/GET "/shaders/:id/fragment.glsl" [id] (visualizer/shader id))
  (route/not-found "<p>Page not found.</p>"))
