(ns afterglow.fixtures
  "Utility functions common to fixture definitions."
  {:author "James Elliott"})

(defn- build-function-map
  "Gathers all the functions defined on the channels a fixture or head
  into a map indexed by the function name which references the channel
  and function specs."
  [fixture-or-head]
  (into {} (apply concat (for [c (:channels fixture-or-head)]
                                  (for [f (:functions c)]
                                    [(:type f) [c f]])))))

(defn index-functions
  [fixture]
  "Associates function maps with the fixture and all its heads, for
  easy lookup by function-oriented effects."
  (update-in (assoc fixture :function-map (build-function-map fixture))
             [:heads]
             #(map (fn [head] (assoc head :function-map (build-function-map head))) %)))

(defn printable
  "Strips a mapped fixture (or fixture list) of keys which make it a pain to print,
  such as the back links from the heads to the entire fixture, and the function
  maps."
  [fixture-or-fixture-list]
  (if (sequential? fixture-or-fixture-list)
    (map (fn [fixture] (update-in (dissoc fixture :function-map) [:heads]
                                  #(map (fn [head] (dissoc head :fixture :function-map)) %)))
         fixture-or-fixture-list)
    (printable [fixture-or-fixture-list])))
