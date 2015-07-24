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
            [overtone.osc :as osc]
            [selmer.parser :as parser]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor])
  (:gen-class))

(defonce ^{:doc "Holds the running web UI server, if there is one, for later shutdown."}
  web-server (atom nil))

(defonce ^{:doc "Holds the running OSC server, if there is one, for later shutdown."}
  osc-server (atom nil))

(defonce ^{:doc "Holds the future which is cleaning up expired web sessions, if any."}
  session-cleaner (atom nil))

(defonce ^{:doc "Holds the running REPL server, if there is one, for later shutdown."}
  nrepl-server (atom nil))

(defonce ^{:private true
           :doc "The default log appenders, which rotate between files
           in a logs subdirectory."}
  appenders (atom {:rotor (rotor/rotor-appender {:path "logs/afterglow.log"
                                                 :max-size 100000
                                                 :backlog 5})}))

(defn- init-logging-internal
  "Performs the actual initialization of the logging environment,
  protected by the delay below to insure it happens only once."
  []
  (timbre/set-config!
   {:level :info  ; #{:trace :debug :info :warn :error :fatal :report}
    :enabled? true

    ;; Control log filtering by namespaces/patterns. Useful for turning off
    ;; logging in noisy libraries, etc.:
    :ns-whitelist  [] #_["my-app.foo-ns"]
    :ns-blacklist  [] #_["taoensso.*"]
    
    :middleware [] ; (fns [data]) -> ?data, applied left->right
    
    :timestamp-opts timbre/default-timestamp-opts ; {:pattern _ :locale _ :timezone _}
    
    :output-fn timbre/default-output-fn ; (fn [data]) -> string
    })

  ;; Install the desired log appenders
  (timbre/merge-config!
   {:appenders @appenders})

  ;; Disable Selmer's template cache in development mode
  (if (env :dev) (parser/cache-off!)))

(defonce ^{:private true
           :doc "Used to ensure log initialization takes place exactly once."}
  initialized (delay (init-logging-internal)))

(defn init-logging
  "Set up the logging environment for Afterglow. Called by main when invoked
  as a jar, and by the examples namespace when brought up in a REPL for exploration,
  and by extensions such as afterglow-max which host Afterglow in Cycling '74's Max."
  ([] ;; Resolve the delay, causing initialization to happen if it has not yet.
   @initialized)
  ([appenders-map] ;; Override the default appenders, then initialize as above.
   (reset! appenders appenders-map)
   (init-logging)))

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

(defn start-osc-server
  "Start the embedded OSC server on the specified port."
  [port]
  (when @osc-server (throw (IllegalStateException. "OSC server is already running.")))
  (reset! osc-server (osc/osc-server port "Afterglow")))

(defn stop-osc-server
  "Shut down the embedded OSC server if it is running."
  []
  #_(osc/zero-conf-off)
  (try
    (swap! osc-server (fn [server]
                        (when server
                          (osc/osc-rm-all-listeners server)
                          (osc/osc-rm-all-handlers server)
                          (osc/osc-close server)
                          nil)))
    (catch Throwable t
      (timbre/error "failed to shut down OSC server" t))))

(defn start-nrepl
  "Start a network REPL for debugging or remote control."
  [port]
  (try
    (swap! nrepl-server #(do (when % (nrepl/stop-server %))
                             (nrepl/start-server :port port :handler cider-nrepl-handler)))
    (timbre/info "nREPL server started on port" port)
    (catch Throwable t
      (timbre/error "failed to start nREPL" t))))

(defn stop-servers
  "Shut down the embedded web UI, OSC and NREPL servers."
  []
  (timbre/info "shutting down embedded servers...")
  (swap! web-server #(do (when % (% :timeout 100)) nil))
  (stop-osc-server)
  (swap! nrepl-server #(do (when % (nrepl/stop-server %)) nil))
  (swap! session-cleaner #(do (when % (future-cancel %)) nil))
  (timbre/info "shutdown complete!"))

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
    (timbre/info "Web UI server on port:" (:web-port options))
    (start-osc-server (:osc-port options))
    (timbre/info "OSC server on port:" (:osc-port options))
    (when-let [port (:repl-port options)]
      (start-nrepl port)
      (timbre/info "nrepl server on port:" port))
    (timbre/info (str "\n-=[ afterglow startup concluded successfully"
                      (when (env :dev) "using the development profile") "]=-"))))
