(ns afterglow.web.session
  "Manages the session store for Afterglow's web interface"
  (:require [afterglow.web.routes.web-repl :as web-repl]))

(defonce ^{:doc "The session store"}
  mem (atom {}))

(def half-hour
  "How often expired sessions should be purged."
  1800000)

(defn- current-time
  "Gets the current time, in seconds, which is how Ring expresses
  session timeouts."
  []
  (quot (System/currentTimeMillis) 1000))

(defn- not-yet-expired?
  "Returns true if the session's expiration time still lies in the
  future."
  [[id session]]
  (pos? (- (:ring.middleware.session-timeout/idle-timeout session) (current-time))))

(defn clear-expired-sessions
  "Removes any session entries for sessions whose expiration time has
  arrived."
  []
  (swap! mem #(->> % (filter not-yet-expired?) (into {}))))

(defn start-cleanup-job!
  "Creates a background thread which cleans out expired sessions every
  half hour."
  []
  (future
    (loop []
      (clear-expired-sessions)
      (web-repl/clean-expired-bindings @mem)
      (Thread/sleep half-hour)
      (recur))))
