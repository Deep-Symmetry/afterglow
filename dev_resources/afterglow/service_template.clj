(ns afterglow.{{classname|lower}}-service
    (:require [flatland.protobuf.core :refer :all]
              [afterglow.{{classname|lower}}-messages :refer :all]
              [afterglow.ola-client :refer [send-request wrap-message-if-needed]]))

{% for item in rpcs %}
(defn {{item.method}}
  ([handler]
   ({{item.method}} {} handler))
  ([message handler]
   (let [wrapped (wrap-message-if-needed message {{item.takes}})]
     (send-request "{{item.method}}" wrapped {{item.returns}} handler))))
{% endfor %}
