(ns afterglow.core
  "This is the main class for running Afterglow as a self-contained JAR application.
  When you are learning and experimenting in your REPL, the main
  namespace you want to be using is afterglow.examples"
  (:require [afterglow.fixtures.qxf :as qxf]
            [afterglow.init]
            [afterglow.version :as version]
            [afterglow.web.handler :refer [app]]
            [afterglow.web.session :as session]
            [clojure.java.browse :as browse]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.nrepl.server :as nrepl]
            [environ.core :refer [env]]
            [ola-clojure.ola-client :as ola-client]
            [org.httpkit.server :as http-kit]
            [overtone.osc :as osc]
            [selmer.parser :as parser]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
            [taoensso.timbre :as timbre])
  (:import [java.net InetAddress]
           [org.deepsymmetry.beatlink DeviceFinder])
  (:gen-class))

(defonce ^{:doc "Holds the running web UI server, if there is one, for later shutdown."}
  web-server (atom nil))

(defonce ^{:doc "Holds the running OSC server, if there is one, for later shutdown."}
  osc-server (atom nil))

(defonce ^{:doc "Holds the future which is cleaning up expired web sessions, if any."}
  session-cleaner (atom nil))

(defonce ^{:doc "Holds the running REPL server, if there is one, for later shutdown."}
  nrepl-server (atom nil))

(defn- create-appenders
  "Create a set of appenders which rotate the file at the specified path."
  [path]
  {:rotor (rotor/rotor-appender {:path path
                                 :max-size 100000
                                 :backlog 5})})

(defonce ^{:private true
           :doc "The default log appenders, which rotate between files
           in a logs subdirectory."}
  appenders (atom (create-appenders "logs/afterglow.log")))

(defn- init-logging-internal
  "Performs the actual initialization of the logging environment,
  protected by the delay below to insure it happens only once."
  []
  (timbre/set-config!
   ;; See http://ptaoussanis.github.io/timbre/taoensso.timbre.html#var-*config* for more details.
   {:level :info  ; #{:trace :debug :info :warn :error :fatal :report}
    :enabled? true

    :middleware [] ; (fns [data]) -> ?data, applied left->right

    :timestamp-opts {:pattern "yyyy-MMM-dd HH:mm:ss"
                     :locale :jvm-default
                     :timezone (java.util.TimeZone/getDefault)}

    :output-fn timbre/default-output-fn ; (fn [data]) -> string
    })

  ;; Install the desired log appenders
  (timbre/merge-config!
   {:appenders @appenders})

  ;; Disable Selmer's template cache in development mode
  (when (Boolean/valueOf (env :dev)) (parser/cache-off!)))

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

(defn- valid-host?
  "Check whether a string represents a valid host name."
  [name]
  (try
    (InetAddress/getByName name)
    true
    (catch Exception e
      false)))

(defn- println-err
  "Prints objects to stderr followed by a newline."
  [& more]
  (binding [*out* *err*]
    (apply println more)))

(def ^:private log-file-error
  "Holds the validation failure message if the log file argument was
  not acceptable."
  (atom nil))

(defn- bad-log-arg
  "Records a validation failure message for the log file argument, so
  a more specific diagnosis can be given to the user. Returns false to
  make it easy to invoke from the validation function, to indicate
  that validation failed after recording the reason."
  [& messages]
  (reset! log-file-error (clojure.string/join " " messages))
  false)

(defn- valid-log-file?
  "Check whether a string identifies a file that can be used for logging."
  [path]
  (let [f (clojure.java.io/file path)
        dir (or (.getParentFile f) (.. f (getAbsoluteFile) (getParentFile)))]
    (if (.exists f)
      (cond  ; The file exists, so make sure it is writable and a plain file
        (not (.canWrite f)) (bad-log-arg "Cannot write to log file")
        (.isDirectory f) (bad-log-arg "Requested log file is actually a directory")
        ;; Requested existing file looks fine, make sure we can roll over
        :else (or (.canWrite dir)
                  (bad-log-arg "Cannot create rollover log files in directory" (.getPath dir))))
      ;; The requested file does not exist, make sure we can create it
      (if (.exists dir)
        (and (or (.isDirectory dir)
                 (bad-log-arg "Log directory is not a directory:" (.getPath dir)))
             (or (.canWrite dir) ; The parent directory exists, make sure we can write to it
                 (bad-log-arg "Cannot create log file in directory" (.getPath dir))))
        (or (.mkdirs dir) ; The parent directory doesn't exist, make sure we can create it
          (bad-log-arg "Cannot create log directory" (.getPath dir)))))))

(def cli-options
  "The command-line options supported by Afterglow."
  [["-w" "--web-port PORT" "Port number for web UI"
    :default (or (when-let [default (env :web-port)] (Integer/parseInt default)) 16000)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-n" "--no-browser" "Don't launch web browser"]
   ["-o" "--osc-port PORT" "Port number for OSC server"
    :default (or (when-let [default (env :osc-port)] (Integer/parseInt default)) 16001)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-r" "--repl-port PORT" "Port number for REPL, if desired"
    :default (env :repl-port)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-l" "--log-file PATH" "File into which log is written"
    :default (or (env :log-file) "logs/afterglow.log")
    :validate [valid-log-file? @log-file-error]]
   ["-H" "--olad-host HOST" "Host name or address of OLA daemon"
    :default (or (env :olad-host) "localhost")
    :validate [valid-host? "Must be a valid host name"]]
   ["-P" "--olad-port PORT" "Port number OLA daemon listens on"
    :default (or (when-let [default (env :olad-port)] (Integer/parseInt default)) 9010)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-q" "--convert-qxf PATH" "Convert QLC+ fixture file and exit"]
   ["-h" "--help" "Display help information and exit"]])

(defn usage
  "Print message explaining command-line invocation options."
  [options-summary]
  (clojure.string/join
   \newline
   [(str (version/title) " " (version/tag) ", a live-coding environment for light shows.")
    (str "Usage: " (version/title) " [options] [init-file ...]")
    "  Any init-files specified as arguments will be loaded at startup,"
    "  in the order they are given, before creating any embedded servers."
    ""
    "Options:"
    options-summary
    ""
    "If you translate a QLC+ fixture definition file, Afterglow will try to write"
    "its version in the same directory, but won't overwrite an existing file."
    ""
    "If you do not explicitly specify a log file, and Afterglow cannot write to"
    "the default log file path, logging will be silently suppressed."
    ""
    "Please see https://github.com/Deep-Symmetry/afterglow for more information."]))

(defn error-msg
  "Format an error message related to command-line invocation."
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn exit
  "Terminate execution with a message to the command-line user."
  [status msg]
  (if (zero? status)
    (println msg)
    (println-err msg))
  (System/exit status))

(defn start-web-server
  "Start the embedded web UI server on the specified port. If a truthy
  value is supplied for browser, opens a web browser window on the
  newly launched server. If the server was already running, logs a
  warning. Either way, makes sure the background thread which cleans
  up expired sessions is running."
  ([port]
   (start-web-server port false))
  ([port browser]
   (swap! web-server #(if %
                        (timbre/warn "Not starting web server because it is already running.")
                        (do
                          (.start (DeviceFinder/getInstance)) ; The web UI wants to know about DJ Link devices
                          (http-kit/run-server app {:port port}))))

   ;;Start the expired session cleanup job if needed
   (swap! session-cleaner #(or % (session/start-cleanup-job!)))

   ;; Launch the browser if requested
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
      (timbre/error t "failed to shut down OSC server"))))

(defn start-nrepl
  "Start a network REPL for debugging or remote control."
  [port]
  (try
    (swap! nrepl-server #(do (when % (nrepl/stop-server %)) (nrepl/start-server :port port)))
    (timbre/info "nREPL server started on port" port)
    (catch Throwable t
      (timbre/error t "failed to start nREPL"))))

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

    ;; Handle help, error conditions, and fixture definition translation
    (cond
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (str (error-msg errors) "\n\n" (usage summary)))
      (:convert-qxf options) (let [[status message] (qxf/convert-qxf (:convert-qxf options))]
                               (exit status message)))

    ;; Set up the logging environment
    (reset! appenders (create-appenders (:log-file options)))
    (init-logging)

    ;; Load any requested initialization files
    (doseq [f arguments]
      (try
        (timbre/info "Loading init-file" f)
        (binding [*ns* (the-ns 'afterglow.init)]
          (load-file f))
        (catch Throwable t
          (timbre/error t "Problem loading init-file" f)
          (println-err "Failed to load init-file" f)
          (println-err (.getMessage t))
          (println-err "See" (:log-file options) "for stack trace.")
          (System/exit 1))))

    ;; Set up embedded servers
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-servers))
    (reset! ola-client/olad-host (:olad-host options))
    (when-not (#{"localhost" "127.0.0.1"} @ola-client/olad-host) (ola-client/use-buffered-channel))
    (reset! ola-client/olad-port (:olad-port options))
    (timbre/info "Will find OLA daemon on host" @ola-client/olad-host ", port" @ola-client/olad-port)
    (start-web-server (:web-port options) (not (:no-browser options)))
    (timbre/info "Web UI server on port:" (:web-port options))
    (start-osc-server (:osc-port options))
    (timbre/info "OSC server on port:" (:osc-port options))
    (when-let [port (:repl-port options)]
      (start-nrepl port)
      (timbre/info "nrepl server on port:" port))

    (timbre/info "Startup complete:" (version/title) (version/tag))))
