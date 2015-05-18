(ns afterglow.fixtures
  "Utility functions common to fixture definitions."
  {:author "James Elliott"})

(defn printable
  "Strips a mapped fixture list of keys which make it impossible to
  print, such as the back links from the heads to the entire fixture,
  and from the fixture to show in which it is patched."
  [fixtures]
  (map (fn [fixture] (update-in (dissoc fixture :show) [:heads]
                                #(map (fn [head] (dissoc head :fixture :show)) %)))
       fixtures))
