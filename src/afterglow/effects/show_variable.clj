(ns afterglow.effects.show-variable
  "Virtual effects which set a value in a show variable while they
  are running. Pair well with [[conditional-effect]] to modify the
  behavior of scenes based on the activation of other cues."
  {:author "James Elliott"}
  (:require [afterglow.controllers :as controllers]
            [afterglow.effects :as fx]
            [afterglow.effects.params :as params]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [taoensso.timbre :as timbre])
  (:import [afterglow.effects Effect Assigner]))

(defn- empty-buffer
  "Empty the frame buffer of the specified show binding in
  preparation for generating a new frame of the light show."
  [frame-buffer last-frame]
  (dosync
   (ref-set last-frame @frame-buffer)
   (ref-set frame-buffer {})))

(defn- send-buffer
  "Update the associated show variable bindings based on differences
  between what has been generated during this frame of the Afterglow
  light show and the previous one, if any."
  [show frame-buffer last-frame]
  (with-show show
    (let [previous-vars (set (keys @last-frame))
          current-vars (set (keys @frame-buffer))]
      ;; Restore the values of any variables which are no longer being affected.
      (doseq [v (clojure.set/difference previous-vars current-vars)]
        (let [[orig-val _] (get @last-frame v)]
          (show/set-variable! v orig-val)))
      ;; Set any variables that are newly assigned, or whose assignment value has changed.
      (doseq [v current-vars]
        (let [[_ new-val] (get @frame-buffer v)]
          (when (or (not (previous-vars v))
                    (not= new-val (get-in @last-frame [v 1])))
            (show/set-variable! v new-val)))))))

(defn bind
  "Establishes the association with the show, so that effects created
  based on this structure will be able to set variables in the show."
  [binding]
  (with-show (:show binding)
    (show/add-empty-buffer-fn! (:empty-fn binding))
    (show/add-send-buffer-fn! (:send-fn binding))))

(defn create-for-show
  "Creates the structures needed for adjusting variables in a show,
  and establishes the binding to the show."
  [show]
  (let [frame-buffer (ref nil)
        last-frame (ref nil)
        empty-fn #(empty-buffer frame-buffer last-frame)
        send-fn #(send-buffer show frame-buffer last-frame)
        binding {:show show
                 :frame-buffer frame-buffer
                 :last-frame last-frame
                 :empty-fn empty-fn
                 :send-fn send-fn}]
    (bind binding)
    binding))

(defn unbind
  "Removes the association with the show. Once this has been called,
  effects created based on this structure will no longer have any
  effect on the variables of the show."
  [binding]
  (with-show (:show binding)
    (show/clear-empty-buffer-fn! (:empty-fn binding))
    (show/clear-send-buffer-fn! (:send-fn binding))))

;; TODO: Could add effect which explictly expects numbers, so they can be faded, for example.
(defn variable-effect
  "An effect which sets the show variable with the specified key to
  match the parameter passed in, and restores its original value when
  ended. Often combined with [[conditional-effect]] to enable
  cross-effect relationships."
  [binding k v]
  (Effect. (str "Set " (name k))
           fx/always-active
           (fn [show snapshot]
             (let [resolved (params/resolve-unless-frame-dynamic v show snapshot)]
               [(Assigner. :show-variable (keyword k) binding
                            (fn [show snapshot target previous-assignment] resolved))]))
           fx/end-immediately))

;; Tell Afterglow about our assigners and the order in which they should be run.
(show/set-extension-resolution-order! :afterglow.show-variable [:show-variable])

;; Set up the resolution handler for the show variable assigner.
(defmethod fx/resolve-assignment :show-variable [assignment show snapshot _]
  (let [target (:target assignment)  ; Find the binding associated with this assignment.
        target-id (:target-id assignment)
        ;; Resolve the assignment value in case it is still frame dynamic.
        resolved (params/resolve-param (:value assignment) show snapshot target)]
    ;; Store it in our frame buffer so it can be set when the lights are being updated.
    (dosync
     (let [original-value (if-let [earlier-assignment (target-id @(:last-frame target))]
                            (get earlier-assignment 0)
                            (with-show (:show target)
                              (show/get-variable target-id)))]
       (alter (:frame-buffer target) assoc target-id [original-value resolved])))))

