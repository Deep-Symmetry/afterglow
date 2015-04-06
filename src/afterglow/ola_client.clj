(ns afterglow.ola-client
  (:require [flatland.protobuf.core :refer :all]
            [clojure.java.io :as io])
  (:import [ola.proto Ola$STREAMING_NO_RESPONSE]
           [ola.rpc Rpc$RpcMessage Rpc$Type]
           [java.net Socket]
           [java.nio ByteBuffer]
           [java.io DataOutputStream]
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

(defn send-request [name message]
  (with-open [sock (Socket. "localhost" 9010)
              in (io/input-stream sock)
              out (io/output-stream sock)]
    ;(println (protobuf-schema RpcMessage))
    (let [msg-bytes (ByteString/copyFrom (protobuf-dump message))
          request (protobuf RpcMessage :type :REQUEST :id 0 :name name :buffer msg-bytes)
          request-bytes (protobuf-dump request)
          send-length (+ 4 (count request-bytes))
          request-buffer (.order (ByteBuffer/allocate send-length) (ByteOrder/nativeOrder))
          send-buffer (byte-array send-length)]

      (doto request-buffer
        (.putInt (.intValue (build-header (count request-bytes))))
        (.put request-bytes)
        (.flip)
        (.get send-buffer))
      (println (count send-buffer))
      (doseq [b send-buffer] (println b))
      (.write out send-buffer)
      (Thread/sleep 2000)
      #_(let [buf (byte-array 1000) 
            n (.read in buf)]
        (if (> n 4)
          (let [bb (ByteBuffer/allocate 4)]
            (.put bb buf 0 4)
            (.flip bb)
            (let [response-len (.getInt bb)]
              (if (= response-len (- n 4))
                (let [response (byte-array response-len)]
                  (.get bb response)
                  (protobuf-load RpcMessage buf 4 (response-len)))
                (throw (Exception. (str "Read wrong size? Response should be " response-len " bytes, total read: " n " (4 byte header)"))))))
          (throw (Exception. (str "Read less than 4 bytes from olad: " n))))))
    ))
