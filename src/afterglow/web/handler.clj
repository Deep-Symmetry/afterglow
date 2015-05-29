(ns afterglow.web.handler
  (:require [compojure.core :refer [defroutes routes wrap-routes]]
            [afterglow.web.routes.home :refer [home-routes]]
            [afterglow.web.middleware :as middleware]
            [compojure.route :as route]))

(defroutes base-routes
           (route/resources "/")
           (route/not-found "Not Found"))

(def app
  (-> (routes
        (wrap-routes home-routes middleware/wrap-csrf)
        base-routes)
      middleware/wrap-base))
