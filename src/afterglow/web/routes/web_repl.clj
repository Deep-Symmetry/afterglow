(ns afterglow.web.routes.web-repl
  "Provides a web interface for interacting with the Clojure environment."
  (:require [afterglow.web.layout :as layout]
            [clojure.main :as main]
            [clojure.stacktrace :refer [root-cause]]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [ring.util.response :refer [response]]
            [taoensso.timbre :refer [info warn spy]]))

(defonce ^{:private true
           :doc "Stores thread-local bindings for each web REPL session."}
  repl-sessions (ref {}))
 
(defn- current-bindings
  "Wrap a new layer of bindings around the dynamically bound variables
  we want to isolate for each web REPL sessions, initializing a few we
  want to start with clean values."
  []
  (binding [*ns* *ns*
            *warn-on-reflection* *warn-on-reflection*
            *math-context* *math-context*
            *print-meta* *print-meta*
            *print-length* *print-length*
            *print-level* *print-level*
            *compile-path* (System/getProperty "clojure.compile.path" "classes")
            *command-line-args* *command-line-args*
            *assert* *assert*
            *1 nil
            *2 nil
            *3 nil
            *e nil]
    (get-thread-bindings)))
 
(defn- bindings-for
  "Look up the dynamic bindings specific to this web REPL session,
  creating fresh ones if this is the first time it is being used.
  Start out in the afterglow.examples namespace."
 [session-key]
  (when-not (@repl-sessions session-key)
    (require '[afterglow.examples])
    (binding [*ns* *ns*]
      (in-ns 'afterglow.examples)
      (dosync
       (commute repl-sessions assoc session-key (current-bindings)))))
  (@repl-sessions session-key))
 
(defn- store-bindings-for
  "Store the dynamic bindings specific to this web REPL session."
 [session-key]
  (dosync
    (commute repl-sessions assoc session-key (current-bindings))))
 
(defmacro with-session
  "Wrap the body in a session-specific set of dynamic variable
  bindings."
  [session-key & body]
  `(with-bindings (bindings-for ~session-key)
    (let [r# ~@body]
      (store-bindings-for ~session-key)
      r#)))
 
(defn discard-bindings
  "Clean up the thread-local bindings stored for a non-web hosted repl
  session, such as those used by
  [afterglow-max](https://github.com/brunchboy/afterglow-max#afterglow-max),
  which are not automatically timed out. `session-key` is the unique,
  non-String key used to identify the REPL session to [[do-eval]]."
  [session-key]
  (dosync
   (commute repl-sessions dissoc session-key)))

(defn do-eval
  "Evaluate an expression sent to the web REPL and return the result
  or an error description. Also supports evaluation of expressions in
  non-web hosting contexts like 
  [afterglow-max](https://github.com/brunchboy/afterglow-max#afterglow-max)
  by passing in a unique non-String value for `session-key`. In such
  cases the thread local bindings will not be automatically cleaned
  up, and it is the responsibility of the hosting implementation to
  call [[discard-bindings]] when they are no longer needed."
  {:doc/format :markdown}
  [txt session-key]
  (with-session session-key
    (let [form (binding [*read-eval* false] (read-string txt))]
      (with-open [writer (java.io.StringWriter.)]
        (binding [*out* writer]
          (try
            (let [r (pr-str (eval form))]
              {:result (str (.toString writer) (str r))})
            (catch Throwable t
              {:error (str (root-cause t))})))))))

(defn handle-command
  "Route which processes a command typed into the web console."
  [req]
  (let [session-key (get-in req [:cookies "ring-session" :value])
        command (get-in req [:params :command])]
    (response (do-eval command session-key))))

(defn page
  "Route which renders the web console interface."
  []
  (layout/render "console.html" {:csrf-token *anti-forgery-token*}))

(defn- still-needed?
  "Returns true if the bindings have a key that either refers to a
  non-expired web session, or is not a String, which means that it
  does not come from a web session at all, but rather a hosting
  environment like
  [afterglow-max](https://github.com/brunchboy/afterglow-max#afterglow-max),
  which does not expire."
  {:doc/format :markdown}
  [web-sessions [id _]]
  (or (not (string? id))
      (some? (web-sessions id))))

(defn clean-expired-bindings
  "Clean out the dynamic variable bindings stored for web sessions
  which have expired. Ignores bindings whose keys are not strings,
  because they do not come from web sessions, but from hosting
  environments like
  [afterglow-max](https://github.com/brunchboy/afterglow-max#afterglow-max)
  which do not expire."
  {:doc/format :markdown}
  [web-sessions]
  (dosync
   (commute repl-sessions #(->> % (filter (partial still-needed? web-sessions)) (into {})))))
