(ns afterglow.ola-client
  (:require [flatland.protobuf.core :refer :all]
            [clojure.java.io :as io])
  (:import [ola.proto Ola$STREAMING_NO_RESPONSE]
           [ola.rpc Rpc$RpcMessage Rpc$Type]
           [java.net Socket]
           [java.nio ByteBuffer]
           [java.io InputStream]
           [flatland.protobuf PersistentProtocolBufferMap$Def$NamingStrategy]
           [com.google.protobuf ByteString]))

(def RpcMessage (protodef Rpc$RpcMessage
                          {:naming-strategy (reify PersistentProtocolBufferMap$Def$NamingStrategy
                                              (protoName [this clojure-name]
                                                (name clojure-name))
                                              (clojureName [this proto-name]
                                                (keyword proto-name)))}))

(def ^:private protocol-version 1)
(def ^:private version-mask 0xf0000000)
(def ^:private version-masked (bit-and (bit-shift-left protocol-version 28) version-mask))
(def ^:private size-mask 0x0fffffff)

(defn build-header [length]
  "Calculates the correct 4-byte header value for an OLA request of the specified length."
  (bit-or (bit-and length size-mask) version-masked))

(defn parse-header [header]
  "Returns the length encoded by a header value, after validating the protocol version."
  (if (= (bit-and header version-mask) version-masked)
    (bit-and header size-mask)
    (throw (Exception. (str "Unsupported OLA protocol version, "
                            (bit-shift-right (bit-and header version-mask) 28))))))

(defn- read-fully
  "Will fill the buffer to capacity, or throw an exception.
  Returns the number of bytes read."
  ^long [^InputStream input ^bytes buf]
  (loop [off 0 len (alength buf)]
    (let [in-size (.read input buf off len)]
      (cond
        (== in-size len) (+ off in-size)
        (neg? in-size) (throw (Exception. (str "Only able to read " off " of " (alength buf) " expected bytes.")))
        :else (recur (+ off in-size) (- len in-size))))))

(defn send-request [name message response-type]
  (with-open [sock (Socket. "localhost" 9010)
              in (io/input-stream sock)
              out (io/output-stream sock)]
    ;(println (protobuf-schema RpcMessage))
    (let [msg-bytes (ByteString/copyFrom (protobuf-dump message))
          request (protobuf RpcMessage :type :REQUEST :id 0 :name name :buffer msg-bytes)
          request-bytes (protobuf-dump request)
          header-buffer (.order (ByteBuffer/allocate 4) (ByteOrder/nativeOrder))
          header-bytes (byte-array 4)]
      (doto header-buffer
        (.putInt (.intValue (build-header (count request-bytes))))
        (.flip)
        (.get header-bytes))
      (.write out header-bytes)
      (.write out request-bytes)
      (.flush out)

      (read-fully in header-bytes)
      (doto header-buffer
        (.clear)
        (.put header-bytes)
        (.flip))
      (let [wrapper-length (parse-header (.getInt header-buffer))
            wrapper-bytes (byte-array wrapper-length)]
        (read-fully in wrapper-bytes)
        (let [wrapper (protobuf-load RpcMessage wrapper-bytes)
              response-length (.size (:buffer wrapper))
              response-buffer (.asReadOnlyByteBuffer (:buffer wrapper))
              response-bytes (byte-array response-length)]
          (println "Wrapper:" wrapper)
          (.get response-buffer response-bytes)
          (protobuf-load response-type response-bytes))))))
