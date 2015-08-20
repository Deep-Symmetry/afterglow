(ns afterglow.web.handler
  (:require [afterglow.web.middleware :as middleware]
            [afterglow.web.routes.home :refer [home-routes]]
            [compojure.core :refer [defroutes routes wrap-routes]]
            [compojure.route :as route]))

(defroutes base-routes
           (route/resources "/")
           (route/not-found "Not Found"))

(def app
  (-> (routes
        (wrap-routes home-routes middleware/wrap-csrf)
        base-routes)
      middleware/wrap-base))
