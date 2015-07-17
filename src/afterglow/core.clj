(ns afterglow.core
  "This is the main class for running Afterglow as a self-contained JAR application.
  When you are learning and experimenting in your REPL, the main
  namespace you want to be using is afterglow.examples"
  (:require [afterglow.web.handler :refer [app]]
            [afterglow.web.session :as session]
            [org.httpkit.server :as http-kit]
            [environ.core :refer [env]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.nrepl.server :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [clojure.java.browse :as browse]
            [selmer.parser :as parser]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor])
  (:gen-class))

(defonce ^{:doc "Keeps track of whether init has been called."}
  initialized (atom false))

(defonce ^{:doc "Holds the running web UI server, if there is one, for later shutdown."}
  web-server (atom nil))

(defonce ^{:doc "Holds the future which is cleaning up expired web sessions, if any."}
  session-cleaner (atom nil))

(defonce ^{:doc "Holds the running REPL server, if there is one, for later shutdown."}
  nrepl-server (atom nil))

(defn init-logging
  "Set up the logging environment for Afterglow. Called by main when invoked
  as a jar, and by the examples namespace when brought up in a REPL for exploration."
  []
  (when-not @initialized
    ;; Make sure the experimenter does not get blasted with a ton of debug messages
    (timbre/set-config!
     {:level :info  ; #{:trace :debug :info :warn :error :fatal :report}
      :enabled? true

      ;; Control log filtering by namespaces/patterns. Useful for turning off
      ;; logging in noisy libraries, etc.:
      :ns-whitelist  [] #_["my-app.foo-ns"]
      :ns-blacklist  [] #_["taoensso.*"]
      
      :middleware [] ; (fns [data]) -> ?data, applied left->right
      
      ;; Clj only:
      :timestamp-opts timbre/default-timestamp-opts ; {:pattern _ :locale _ :timezone _}
      
      :output-fn timbre/default-output-fn ; (fn [data]) -> string
      })

    ;; Provide a nice, organized set of log files to help hunt down problems, especially
    ;; for exceptions which occur on background threads.
    (timbre/merge-config!
     {:appenders {:rotor (rotor/rotor-appender {:path "logs/afterglow.log"
                                                :max-size 100000
                                                :backlog 5})}})


    ;; Disable Selmer's template cache in development mode
    (if (env :dev) (parser/cache-off!))

    (reset! initialized true)))

(def cli-options
  "The command-line options supported by Afterglow."
  [["-w" "--web-port PORT" "Port number for web UI"
    :default (or (when-let [default (env :web-port)] (Integer/parseInt default)) 16000)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-o" "--osc-port PORT" "Port number for OSC server"
    :default (or (when-let [default (env :osc-port)] (Integer/parseInt default)) 16001)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-r" "--repl-port PORT" "Port number for REPL, if desired"
    :default (env :repl-port)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]])

(defn usage
  "Print message explaining command-line invocation options."
  [options-summary]
  (clojure.string/join
   \newline
   ["Afterglow, a functional lighting control environment."
    ""
    "Usage: afterglow [options]"
    ""
    "Options:"
    options-summary
    ""
    "Please see https://github.com/brunchboy/afterglow for more information."]))

(defn error-msg [errors]
  "Format an error message related to command-line invocation."
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn exit [status msg]
  "Terminate execution with a message to the command-line user."
  (println msg)
  (System/exit status))

(defn start-web-server
  "Start the embedded web UI server on the specified port. If a truthy
  value is supplied for browser, opens a web browser window on the
  newly launched server."
  ([port]
   (start-web-server port false))
  ([port browser]
   (when @web-server (throw (IllegalStateException. "Web UI server is already running.")))
   (reset! web-server
           (http-kit/run-server
            app
            {:port port}))
   ;;Start the expired session cleanup job
   (reset! session-cleaner (session/start-cleanup-job!))
   (when browser (browse/browse-url (str "http://localhost:" port)))))

(defn start-nrepl
  "Start a network REPL for debugging or remote control."
  [port]
  (try
    (swap! nrepl-server #(do (when % (nrepl/stop-server %))
                             (nrepl/start-server :port port :handler cider-nrepl-handler)))
    (timbre/info "nREPL server started on port" port)
    (catch Throwable t
      (timbre/error "failed to start nREPL" t))))

;; TODO: Stop OSC server too
(defn stop-servers
  "Shut down the embedded web UI, OSC and NREPL servers."
  []
  (timbre/info "shutting down embedded servers...")
  (swap! web-server #(do (when % (% :timeout 100)) nil))
  (swap! nrepl-server #(do (when % (nrepl/stop-server %)) nil))
  (swap! session-cleaner #(do (when % (future-cancel %)) nil))
  (timbre/info "shutdown complete!"))

;; TODO: Start OSC server too, and nrepl if requested.
(defn -main
  "The entry point when invoked as a jar from the command line. Parse options
  and start servers on the appropriate ports."
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    ;; Handle help and error conditions
    (cond
      (:help options) (exit 0 (usage summary))
      (not= (count arguments) 0) (exit 1 (usage summary))
      errors (exit 1 (str (error-msg errors) "\n\n" (usage summary))))
    (init-logging)
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-servers))
    (clojure.pprint/pprint options)
    (start-web-server (:web-port options) true)
    (timbre/info (str "\n-=[ afterglow started successfully"
                      (when (env :dev) "using the development profile") "]=-"))
    (timbre/info "Web UI server on port:" (:web-port options))))
