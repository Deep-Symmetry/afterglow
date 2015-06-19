(ns afterglow.web.routes.show-control
  (:require [afterglow.web.layout :as layout]
            [afterglow.show :as show]
            [compojure.core :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defn show-page [id]
  (let [[show description] (get @show/shows (Integer/valueOf id))]
    (layout/render "show.html" {:show show :description description})))
