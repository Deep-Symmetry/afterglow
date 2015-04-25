(ns
    ^{:doc "Encapsulates a synchronized light show, executing a vaying collection of effects with
output to a number of DMX universes. Assumes control of the assigned universes, so only
one show at a time should be assigned a given universe. Of course, you can stack as many
effects as you'd like in that show. The effects are maintained in a priority queue, with
higher-priority effects running after lower-priority ones, so they win for channels with
latest-takes-priority semantics. The default priority when adding an effect is zero, but
you can assign it any integer, and it will be inserted into the queue after any existing
effects with the same priority. All effects are assigned a key when they are added, and
adding a new effect with the same key as an existing effect will replace the former one."
      :author "James Elliott"}
  afterglow.show
  (:require [afterglow.ola-service :as ola]
            [afterglow.ola-messages :refer [DmxData]]
            [afterglow.rhythm :refer :all]
            [afterglow.util :refer [ubyte]]
            [overtone.at-at :as at-at]
            [taoensso.timbre :as timbre :refer [error info debug spy]]
            [taoensso.timbre.profiling :as profiling :refer [pspy profile p]])
  (:import [com.google.protobuf ByteString]))


(def default-refresh-interval
  "How often should frames of DMX data be sent out; this should be a supported the rate for your interface.
  The default here is 30 Hz, thirty frames per second."
  (/ 1000 30))

(defonce ^{:doc "Provides thread scheduling for all shows' DMX data genration."
           :private true}
  scheduler
  (at-at/mk-pool))

(defn- send-dmx
  "Calculate and send the next frame of DMX values for the universes and effects run by this show."
  [show buffers]
  (try
    (p :clear-buffers (doseq [levels (vals buffers)] (java.util.Arrays/fill levels (byte 0))))
    (p :eval-functions (doseq [f (:functions @(:active-functions show))]
                         (doseq [channel (f show)]
                           (when-let [levels (get buffers (:universe channel))]
                             ;; This is always LTP, need to support HTP too
                             (aset levels (dec (:address channel)) (ubyte (:value channel)))))))
    (p :send-dmx-data (doseq [universe (keys buffers)]
                        (let [levels (get buffers universe)]
                          (ola/UpdateDmxData {:universe universe :data (ByteString/copyFrom levels)} nil))))
    (catch Exception e
      (error e "Problem trying to run cues"))))

(defn stop!
  "Shuts down the scheduled task which is sending DMX values for the show."
  [show]
  (swap! (:task show) #(when % (at-at/stop %)))
  show)

(defn- create-buffers
  "Create the map of universe IDs to byte arrays used to calculate DMX values
  for universes managed by this show."
  [show]
  (into {} (for [universe (:universes show)] [universe (byte-array 512)])))

(defn start!
  "Starts (or restarts) a scheduled task to calculate and send DMX values to the universes controlled
  by this show at the appropriate refresh rate. Returns the show."
  [show]
  (stop! show)
  (let [buffers (create-buffers show)]
    (swap! (:task show) #(do (when % (at-at/stop %))
                             (at-at/every (:refresh-interval show)
                                          (fn [] (send-dmx show buffers))
                                          scheduler))))
  show)

(defn show
  "Create a show coordinator to calculate and send DMX values to the specified universe(s),
  with a shared metronome to coordinate timing. Values are computed and sent at the specified
  refresh interval."
  ([universe]
   (show (metronome 120) default-refresh-interval universe))
  ([metro refresh-interval & universes]
   {:metronome metro
    :refresh-interval refresh-interval
    :universes universes
    :active-functions (atom {})
    :task (atom nil)}))

(defn stop-all!
  "Kills all scheduled tasks which shows may have created to output their DMX values."
  []
  (at-at/stop-and-reset-pool! scheduler))

(defn- vec-remove
  "Remove the element at the specified index from the collection."
  [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(defn- remove-key
  "Helper function which removes a key from the map of keys to indices, and decrements any
  indices whose value was equal to or greater than that of the key being removed."
  [m key index]
  (into {} (for [[k v] (dissoc m key)] [k (if (>= v index) (dec v) v)])))

(defn- remove-function-internal
  "Helper function which removes the function with the specified key from the priority list
  structure maintained for the show."
  [fns key]
  (if-let [index (get (:keys fns) key)]
    {:keys (remove-key (:keys fns) key index)
     :functions (vec-remove (:functions fns) index)
     :priorities (vec-remove (:priorities fns) index)}
    fns))

(defn- find-insertion-index
  "Determines where in the priority array the current priority should be inserted: Starting at
  the end, look backwards for a priority that is equal to or less than the value being inserted,
  since later functions take priority over earlier ones."
  [coll priority]
  (loop [pos (count coll)]
    (cond (zero? pos)
          pos
          (<= (get coll (dec pos)) priority)
          pos
          :else
          (recur (dec pos)))))

(defn- vec-insert
  "Helper function which inserts an item at a specified index in a vector."
  [coll pos item]
  (let [pieces (split-at pos coll)] 
    (apply conj (vec (first pieces)) item (vec (fnext pieces)))))

(defn- insert-key
  "Helper function which adds a new key to the map of keys to indices, and increments any
  indices whose value was equal to or greater than that of the key being inserted."
  [m key index]
  (assoc (into {} (for [[k v] m] [k (if (>= v index) (inc v) v)]))
         key index))

(defn- add-function-internal
  "Helper function which adds a function with a specified key and priority to the priority
  list structure maintained for the show, replacing any existing function with the same key."
  [fns key f priority]
  (let [base (remove-function-internal fns key)
        index (find-insertion-index (:priorities base) priority)]
    {:keys (insert-key (:keys base) key index)
     :functions (vec-insert (:functions base) index f)
     :priorities (vec-insert (:priorities base) index priority)}))

(defn add-function!
  "Add an effect function or cue to the active set which are affecting DMX outputs for the show.
  If no priority is specified, zero is used. This function is added after all existing functions
  with equal or lower priority, and replaces any existing function with the same key. Since the
  functions are executed in order, ones which come later will win when setting DMX values for the
  same channel if that channel uses latest-takes-priority mode; for channels using highest-takes
  priority, the order does not matter."
  ([show key f]
   (add-function! show key f 0))
  ([show key f priority]
   (swap! (:active-functions show) #(add-function-internal % key f priority))))

(defn- vec-remove
  "Remove the element at the specified index from the collection."
  [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(defn remove-function!
  "Remove an effect function or cue from the active set which are affecting DMX outputs for the show."
  [show key]
  (swap! (:active-functions show) #(remove-function-internal % key)))

(defn clear-functions!
  "Remove all effect functions from the active set, leading to a blackout state in all controlled
  universes (if the show is running) until new functions are added."
  [show]
  (reset! (:active-functions show) {:keys {},
                                    :functions [],
                                    :priorities []}))

(defn profile-show
  "Gather statistics about the performance of generating and sending a frame of DMX data to the
  universes in a show."
  ([show]
   (profile-show show 100))
  ([show iterations]
   (let [buffers (create-buffers show)]
     (profile :info :Frame (dotimes [i iterations] (send-dmx show buffers))))))

(defn blackout-universe
  "Sends zero to every channel of the specified universe. Will be quickly overwritten if there are
any active shows transmitting to that universe."
  [universe]
  (let [levels (byte-array 512)]
    (ola/UpdateDmxData {:universe universe :data (ByteString/copyFrom levels)} nil)))

(defn blackout-show
  "Sends zero to every channel of every universe associated with a show. Will quickly be overwritten if
  the show is started and there are any active functions."
  [show]
  (doseq [universe (:universes show)]
    (blackout-universe universe)))
