(ns afterglow.web.layout
  (:require [clojure.java.io]
            [environ.core :refer [env]]
            [markdown.core :refer [md-to-html-string]]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [ring.util.anti-forgery :refer [anti-forgery-field]]
            [ring.util.response :refer [content-type response]]
            [selmer.filters :as filters]
            [selmer.parser :as parser]))

(declare ^:dynamic *identity*)
(declare ^:dynamic *servlet-context*)
(parser/set-resource-path!  (clojure.java.io/resource "templates"))
(parser/add-tag! :csrf-field (fn [_ _] (anti-forgery-field)))
(filters/add-filter! :markdown (fn [content] [:safe (md-to-html-string content)]))

(defn render-with-type [template mime-type & [params]]
  (-> template
      (parser/render-file
        (assoc params
          :page template
          :dev (Boolean/valueOf (env :dev))
          :csrf-token *anti-forgery-token*
          :servlet-context *servlet-context*
          :identity *identity*))
      response
      (content-type mime-type)))

(defn render [template & [params]]
  (render-with-type template "text/html; charset=utf-8" params))
