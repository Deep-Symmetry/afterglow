(ns afterglow.fixtures
  "Utility functions common to fixture definitions."
  {:author "James Elliott"})

(defn printable
  "Strips a mapped fixture list of keys which make it a pain to print,
  such as the back links from the heads to the entire fixture."
  [fixtures]
  (map (fn [fixture] (update-in fixture [:heads]
                                #(map (fn [head] (dissoc head :fixture)) %)))
       fixtures))
