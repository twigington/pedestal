(ns io.pedestal.http.platform
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [io.pedestal.http.container :as container]
            [io.pedestal.log :as log]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stacktrace]
            [clojure.core.async :as async]
            [io.pedestal.interceptor]
            [io.pedestal.interceptor.helpers :as interceptor]
            [io.pedestal.http.route :as route]
            [io.pedestal.impl.interceptor :as interceptor-impl]
            [ring.util.response :as ring-response])
  (:import (java.io OutputStream)
           (java.io OutputStreamWriter OutputStream)
           (java.nio.channels ReadableByteChannel)
           (java.nio ByteBuffer)))

(defprotocol IHTTPRequest
  (request-map [this service response]
                      "Returns a hashmap with request information. At a bare minimum this should contain the following:
                       :server-port           - the port number the server
                       :server-name           - the host name of the server
                       :remote-addr           - the remote address of the server as a string
                       :uri                   - the request URI as a string
                       :query-string          - the query string as a string
                       :scheme                - the scheme of the request as a keyword (e.g. :http, :https)
                       :request-method        - the request method as a lowercase keyword (e.g. :get, :put)
                       :headers               - a hashmap of the request headers keys are keywords
                                                 here are common entries in this map
                                                 :content-type       - the request content type as a string
                                                 :content-length     - the content length (if available) as a long
                                                 :character-encoding - the character encoding as a string
                                                 :ssl-client-sert    - the X509Certificate of the request
                       :body                   - the request's InputStream
                       :platform               - the request's platform implementation
                       :platform-request       - this IHTTPRequest
                       :platform-response      - a matching IHTTPResponse for this IHTTPRequest
                       :path-info              - the path of the request
                       :http.protocol          - the protocol of the request as a string (e.g. \"HTTP/1.1\")
                       :http.async-supported?  - true if :platform-response satisfies IHTTPAsyncResponse"))

(defprotocol IHTTPResponse
  (output-stream ^OutputStream [this] "Returns the Response's OutputStream")
  (set-header! [this h vs] "Sets header values for this response")
  (set-status! [this status] "Sets the response status code")
  (committed? [this] "Returns true if this response has been committed (headers have been sent)")
  (flush-buffer! [this] "Flushes any pending writes"))

(defprotocol IHTTPAsyncRequest
  (async? [this] "Returns true if the response is currently in async mode")
  (start-async! [this] "Switches this response into async mode")
  (stop-async! [this] "Finishes an async response"))




;;; HTTP Response Utilities

(defprotocol WriteableBody
  (default-content-type [body] "Get default HTTP content-type for `body`.")
  (write-body-to-stream [body output-stream] "Write `body` to the stream output-stream."))

(extend-protocol WriteableBody

  (class (byte-array 0))
  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [byte-array output-stream]
    (io/copy byte-array output-stream))

  String
  (default-content-type [_] "text/plain")
  (write-body-to-stream [string output-stream]
    (let [writer (OutputStreamWriter. output-stream)]
      (.write writer string)
      (.flush writer)))

  clojure.lang.IPersistentCollection
  (default-content-type [_] "application/edn")
  (write-body-to-stream [o output-stream]
    (let [writer (OutputStreamWriter. output-stream)]
      (binding [*out* writer]
        (pr o))
      (.flush writer)))

  clojure.lang.Fn
  (default-content-type [_] nil)
  (write-body-to-stream [f output-stream]
    (f output-stream))

  java.io.File
  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [file output-stream]
    (io/copy file output-stream))

  java.io.InputStream
  (default-content-type [_] "application/octet-stream")
  (write-body-to-stream [input-stream output-stream]
    (try
      (io/copy input-stream output-stream)
      (finally (.close input-stream))))

  java.nio.channels.ReadableByteChannel
  (default-content-type [_] "application/octet-stream")

  java.nio.ByteBuffer
  (default-content-type [_] "application/octet-stream")

  nil
  (default-content-type [_] nil)
  (write-body-to-stream [_ _] ()))

(defn- write-body [response body]
  (write-body-to-stream body (output-stream response)))

(defprotocol WriteableBodyAsync
  (write-body-async [body servlet-response resume-chan context]))

(extend-protocol WriteableBodyAsync

  clojure.core.async.impl.protocols.Channel
  (write-body-async [body response resume-chan context]
    (async/go
      (loop []
        (when-let [body-part (async/<! body)]
          (try
            (write-body response body-part)
            (flush-buffer! response)
            (catch Throwable t
              (log/error :msg "An error occured when async writing to the client"
                         :throwable t
                         :src-chan body)
              (async/close! body)))
          (recur)))
      (async/>! resume-chan context)
      (async/close! resume-chan)))

  java.nio.channels.ReadableByteChannel
  (write-body-async [body servlet-response resume-chan context]
    ;; Writing NIO is container specific, based on the implementation details of Response
    (container/write-byte-channel-body servlet-response body resume-chan context))

  java.nio.ByteBuffer
  (write-body-async [body servlet-response resume-chan context]
    ;; Writing NIO is container specific, based on the implementation details of Response
    (container/write-byte-buffer-body servlet-response body resume-chan context)))


(defn- set-default-content-type
  [{:keys [headers body] :or {headers {}} :as resp-map}]
  (let [content-type (headers "Content-Type")]
    (update-in resp-map [:headers] merge {"Content-Type" (or content-type
                                                             (default-content-type body))})))

(defn set-response
  ([response resp-map]
   (let [{:keys [status headers]} (set-default-content-type resp-map)]
     (set-status! response status)
     (reduce-kv
       (fn [response k v]
         (set-header! response k v)
         response)
       response
       headers))))

(defn- start-servlet-async
  [{:keys [request] :as context}]
  (when-not (and (satisfies? IHTTPAsyncRequest request)
                 (async? request))
    (start-async! request)))

(defn- enter-stylobate
  [{:keys [servlet servlet-request servlet-response] :as context}]
  (-> context
      (assoc :request (request-map servlet-request servlet servlet-response))
      (update-in [:enter-async] (fnil conj []) start-servlet-async)))

(defn- leave-stylobate
  [{:keys [request] :as context}]
  (when (and (satisfies? IHTTPAsyncRequest request)
             (async? request))
    (stop-async! request))
  context)



(defn- send-response
  [{:keys [servlet-response response] :as context}]
  (when-not (committed? servlet-response)
    (set-response servlet-response response))
  (let [body (:body response)]
    (if (and (satisfies? WriteableBodyAsync body))
      (write-body-async body servlet-response (::resume-channel context) context)
      (do
        (write-body servlet-response body)
        (flush-buffer! servlet-response)))))

(defn- send-error
  [context message]
  (log/info :msg "sending error" :message message)
  (send-response (assoc context :response {:status 500 :body message})))

(defn- leave-ring-response
  [{{body :body :as response} :response :as context}]
  (log/debug :in :leave-ring-response :response response)

  (cond
    (nil? response) (do
                      (send-error context "Internal server error: no response")
                      context)
    (satisfies? WriteableBodyAsync body) (let [chan (::resume-channel context (async/chan))]
                                           (send-response (assoc context ::resume-channel chan))
                                           chan)
    true (do (send-response context)
             context)))

(defn- terminator-inject
  [context]
  (interceptor-impl/terminate-when context #(ring-response/response? (:response %))))

(defn- error-stylobate
  "Makes sure we send an error response on an exception, even in the
  async case. This is just to make sure exceptions get returned
  somehow; application code should probably catch and log exceptions
  in its own interceptors."
  [context exception]
  (log/error :msg "error-stylobate triggered"
             :exception exception
             :context context)
  (leave-stylobate context))

(defn- error-ring-response
  "Makes sure we send an error response on an exception, even in the
  async case. This is just to make sure exceptions get returned
  somehow; application code should probably catch and log exceptions
  in its own interceptors."
  [context exception]
  (log/error :msg "error-ring-response triggered"
             :exception exception
             :context context)
  (send-error context "Internal server error: exception")
  context)

(def stylobate
  "An interceptor which creates favorable pre-conditions for further
  io.pedestal.interceptors, and handles all post-conditions for
  processing an interceptor chain. It expects a context map
  with :servlet-request, :servlet-response, and :servlet keys.

  After entering this interceptor, the context will contain a new
  key :request, the value will be a request map adhering to the Ring
  specification[1].

  This interceptor supports asynchronous responses as defined in the
  Java Servlet Specification[2] version 3.0. On leaving this
  interceptor, if the servlet request has been set asynchronous, all
  asynchronous resources will be closed. Pausing this interceptor will
  inform the servlet container that the response will be delivered
  asynchronously.

  If a later interceptor in this context throws an exception which is
  not caught, this interceptor will log the error but not communicate
  any details to the client.

  [1]: https://github.com/ring-clojure/ring/blob/master/SPEC
  [2]: http://jcp.org/aboutJava/communityprocess/final/jsr315/index.html"

  (io.pedestal.interceptor/interceptor {:name ::stylobate
                                        :enter enter-stylobate
                                        :leave leave-stylobate
                                        :error error-stylobate}))

(def ring-response
  "An interceptor which transmits a Ring specified response map to an
  HTTP response.

  If a later interceptor in this context throws an exception which is
  not caught, this interceptor will set the HTTP response status code
  to 500 with a generic error message. Also, if later interceptors
  fail to furnish the context with a :response map, this interceptor
  will set the HTTP response to a 500 error."
  (io.pedestal.interceptor/interceptor {:name ::ring-response
                                        :leave leave-ring-response
                                        :error error-ring-response}))

(def terminator-injector
  "An interceptor which causes a interceptor to terminate when one of
  the interceptors produces a response, as defined by
  ring.util.response/response?"
  (interceptor/before
    ::terminator-injector
    terminator-inject))

(defn- error-debug
  "When an error propagates to this interceptor error fn, trap it,
  print it to the output stream of the HTTP request, and do not
  rethrow it."
  [{:keys [servlet-response] :as context} exception]
  (assoc context
    :response (ring-response/response
                (with-out-str (println "Error processing request!")
                              (println "Exception:\n")
                              (stacktrace/print-cause-trace exception)
                              (println "\nContext:\n")
                              (pprint/pprint context)))))

(def exception-debug
  "An interceptor which catches errors, renders them to readable text
  and sends them to the user. This interceptor is intended for
  development time assistance in debugging problems in pedestal
  services. Including it in interceptor paths on production systems
  may present a security risk by exposing call stacks of the
  application when exceptions are encountered."
  (io.pedestal.interceptor/interceptor {:name ::exception-debug
                                        :error error-debug}))

(defn- interceptor-service-fn
  "Returns a function which can be used as an implementation of the
  Servlet.service method. It executes the interceptors on an initial
  context map containing :servlet, :servlet-config, :servlet-request,
  and :servlet-response."
  [interceptors default-context]
  (fn [servlet servlet-request servlet-response]
    (let [context (merge default-context
                         {:servlet-request servlet-request
                          :servlet-response servlet-response
                          ;:servlet-config (.getServletConfig servlet)
                          :servlet servlet})]
      (log/debug :in :interceptor-service-fn
                 :context context)
      (try
        (let [final-context (interceptor-impl/execute
                              (apply interceptor-impl/enqueue context interceptors))]
          (log/debug :msg "Leaving servlet"
                     :final-context final-context))
        (catch Throwable t
          (log/error :msg "Servlet code threw an exception"
                     :throwable t
                     :cause-trace (with-out-str
                                    (stacktrace/print-cause-trace t))))))))

(defn http-interceptor-service-fn
  "Returns a function which can be used as an implementation of the
  Servlet.service method. It executes the interceptors on an initial
  context map containing :servlet, :servlet-config, :servlet-request,
  and :servlet-response. The terminator-injector, stylobate,
  and ring-response are prepended to the sequence of interceptors."
  ([interceptors] (http-interceptor-service-fn interceptors {}))
  ([interceptors default-context]
   (interceptor-service-fn
     (concat [terminator-injector
              stylobate
              ring-response]
             interceptors)
     default-context)))