(ns afterglow.ola-client
  (:require [flatland.protobuf.core :refer :all]
            [clojure.java.io :as io]
            [clojure.core.cache :as cache]
            [taoensso.timbre :as timbre])
  (:import [ola.proto Ola$STREAMING_NO_RESPONSE]
           [ola.rpc Rpc$RpcMessage Rpc$Type]
           [java.net Socket]
           [java.nio ByteBuffer ByteOrder]
           [java.io InputStream]
           [flatland.protobuf PersistentProtocolBufferMap$Def$NamingStrategy]
           [com.google.protobuf ByteString]))

(timbre/refer-timbre)

(def RpcMessage (protodef Rpc$RpcMessage
                          {:naming-strategy (reify PersistentProtocolBufferMap$Def$NamingStrategy
                                              (protoName [this clojure-name]
                                                (name clojure-name))
                                              (clojureName [this proto-name]
                                                (keyword proto-name)))}))

;; Values needed to construct proper protocol headers for communicating with OLA server
(def ^:private protocol-version 1)
(def ^:private version-mask 0xf0000000)
(def ^:private version-masked (bit-and (bit-shift-left protocol-version 28) version-mask))
(def ^:private size-mask 0x0fffffff)

;; Local port on which the OLA server listens
(def ^:private olad-port 9010)

;; TTL cache used for keeping track of handlers to be called when OLA server responds. If
;; an hour has gone by, we can be sure that request is never going to see a response.
(def ^:private request-cache-ttl (.convert java.util.concurrent.TimeUnit/MILLISECONDS
                                           1 java.util.concurrent.TimeUnit/HOURS))
(defonce ^:private request-cache (atom (cache/ttl-cache-factory {} :ttl request-cache-ttl)))

;; Sequence number used to tie requests to responses
(defonce ^:private request-number (atom 0))

;; Holds the server connection when active and state information
(defonce ^:private connection (atom {:running false}))

(defn- next-request-id
  "Assign the sequence number for a new request, wrapping at the protocol limit.
Will be safe because it will take far longer than an hour to wrap, and stale
requests will thus be long gone."
  []
  (swap! request-number #(if (= % Integer/MAX_VALUE)
                           1
                           (inc %))))

(defn- disconnect-quietly [conn]
  (try
    (.close (:socket conn))
    (catch Exception e
      (info e "Issue closing olad server socket")))
  (dissoc conn :socket :in :out))

(defn- disconnect-server []
  (when (:socket @connection)
    (swap! connection disconnect-quietly)))

(defn- connect-server []
  (disconnect-server)  ;; Clean up any old connection, e.g. if a read failed
  (if (:running @connection)
    (try
      (let [sock (Socket. "localhost" olad-port)]
        (try
          (let [in (io/input-stream sock)
                out (io/output-stream sock)]
            (reset! server-socket (swap! connection #(assoc % :socket sock :in in :out out))))
          (catch Exception e
            (warn e "Problem opening olad server streams; discarding connection")
            (try
              (.close sock)
              (catch Exception e
                (info e "Further exception trying to clean up failed olad connection"))))))
      (catch Exception e
        (warn e "Unable to connect to olad server, is it running?")))
    (warn "connect-server called while running is false; ignoring")))

(defn- build-header
  "Calculates the correct 4-byte header value for an OLA request of the specified length."
  [length]
  (bit-or (bit-and length size-mask) version-masked))

(defn- parse-header
  "Returns the length encoded by a header value, after validating the protocol version."
  [header]
  (if (= (bit-and header version-mask) version-masked)
    (bit-and header size-mask)
    (throw (Exception. (str "Unsupported OLA protocol version, "
                            (bit-shift-right (bit-and header version-mask) 28))))))

(defn- read-fully
  "Will fill the buffer to capacity, or throw an exception. Returns the number of bytes read."
  ^long [^InputStream input ^bytes buf]
  (loop [off 0 len (alength buf)]
    (let [in-size (.read input buf off len)]
      (cond
        (== in-size len) (+ off in-size)
        (neg? in-size) (throw (Exception. (str "Only able to read " off " of " (alength buf) " expected bytes.")))
        :else (recur (+ off in-size) (- len in-size))))))

(defn- write-safely-internal
  "Recursive portion of write-safely, try to write a message to the olad server, reopen
  connection and recur if that fails and it is the first failure."
  [^bytes header ^bytes message ^Boolean first-try]
  (try
      (.write (:out @connection) header)
      (.write (:out @connection) message)
      (try
        (.flush (:out @connection))
        (catch Exception e
          (warn e "Problem flushing message to olad server; not retrying.")))
      (catch Exception e
        (warn e "Problem writing message to olad server")
        (when first-try
          (info "Reopening connection and retrying...")
          (connect-server)
          (write-safely-internal header message false)))))

(defn- write-safely
  "Try to write a message to the olad server, reopen connection and retry once if that failed."
  [^bytes header ^bytes message]
  (write-safely-internal header message true))

(defn- store-handler
  "Record a handler in the cache so it will be ready to call when the OLA server responds."
  [request-id response-type handler]
  (if (cache/has? @request-cache request-id)
    (do
      (swap! request-cache #(cache/hit % request-id))
      (warn "Collision for request id" request-id))
    (swap! request-cache #(cache/miss % request-id {:response-type response-type :handler handler}))))

(defn send-request
  "Send a request to the olad server."
  [name message response-type response-handler]
  (if (:running @connection)
    (let [msg-bytes (ByteString/copyFrom (protobuf-dump message))
          request-id (next-request-id)
          request (protobuf RpcMessage :type :REQUEST :id request-id :name name :buffer msg-bytes)
          request-bytes (protobuf-dump request)
          header-buffer (.order (ByteBuffer/allocate 4) (ByteOrder/nativeOrder))
          header-bytes (byte-array 4)]
      (store-handler request-id response-type response-handler)
      (doto header-buffer
        (.putInt (.intValue (build-header (count request-bytes))))
        (.flip)
        (.get header-bytes))
      (write-safely header-bytes request-bytes))
    (error "ola_client cannot send requests while not running, discarding" name message)))

(defn- find-handler
  "Look up the handler details for a request id, removing them from the cache."
  [request-id]
  (when-let [entry (cache/lookup @request-cache request-id)]
    (swap! request-cache #(cache/evict % request-id))
    entry))

(defn- handle-response
  "Look up the handler associated with an OLA server response and call it on a new thread."
  [wrapper]
  (if-let [handler-entry (find-handler (:id wrapper))]
    (let [response-length (.size (:buffer wrapper))
          response-buffer (.asReadOnlyByteBuffer (:buffer wrapper))
          response-bytes (byte-array response-length)]
      (.get response-buffer response-bytes)
      (future ((:handler handler-entry) (protobuf-load (:response-type handler-entry) response-bytes))))
    (warn "Cannot find handler for response, too old?" wrapper)))

(defn- process-requests
  "Loop to read responses from the OLA server and dispatch them to the proper
  handlers. Needs to be called in a future."
  []
  (let [header-bytes (byte-array 4)
        header-buffer (.order (ByteBuffer/allocate 4) (ByteOrder/nativeOrder))]
    (while (:running @connection)
      (try
        (read-fully (:in @connection) header-bytes)
        (doto header-buffer
          (.clear)
          (.put header-bytes)
          (.flip))
        (let [wrapper-length (parse-header (.getInt header-buffer))
              wrapper-bytes (byte-array wrapper-length)]
          (read-fully (:in @connection) wrapper-bytes)
          (let [wrapper (protobuf-load RpcMessage wrapper-bytes)]
            (if (= (:type wrapper) :RESPONSE)
              (handle-response wrapper)
              (warn "Ignoring unrecognized response type:" wrapper))))
        (catch Exception e
          (when (:running @connection)
            (warn e "Problem reading from olad, trying to reconnect...")
            (connect-server)))))))

(defn start
  "Set up the event handling thread and OLA server connection"
  []
  (if (:running @connection)
    (error "Ignoring attempt to start ola_client when already running")
    (do (swap! connection #(assoc % :running true))
        (connect-server)
        (swap! connection #(assoc % :processor (future (process-requests)))))))

(defn shutdown
  "Stop the event handling thread and close the OLA server connection"
  []
  (if (:running @connection)
    (do (future-cancel (:processor @connection))
        (Thread/sleep 500)
        (disconnect-server)
        (swap! connection #(dissoc % :processor :running)))
    (error "Ignoring attempt to shut down ola_client when not running")))
