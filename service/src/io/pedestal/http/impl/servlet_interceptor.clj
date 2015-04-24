; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.impl.servlet-interceptor
  "Interceptors for adapting the Java HTTP Servlet interfaces."
  (:require [io.pedestal.http.platform :refer [IHTTPRequest IHTTPResponse IHTTPAsyncRequest]])
  (:import (javax.servlet Servlet ServletRequest ServletConfig)
           (javax.servlet.http HttpServletRequest HttpServletResponse)))



;; Should we also set character encoding explicitly - if so, where
;; should it be stored in the response map, headers? If not,
;; should we provide help for adding it to content-type string?
(defn- set-header [^HttpServletResponse servlet-resp h vs]
  (cond
   (= h "Content-Type") (.setContentType servlet-resp vs)
   (= h "Content-Length") (.setContentLengthLong servlet-resp (Long/parseLong vs))
   (string? vs) (.setHeader servlet-resp h vs)
   (sequential? vs) (doseq [v vs] (.addHeader servlet-resp h v))
   :else
   (throw (ex-info "Invalid header value" {:value vs}))))



;;; HTTP Request

(defn- request-headers [^HttpServletRequest servlet-req]
  (loop [out (transient {})
         names (enumeration-seq (.getHeaderNames servlet-req))]
    (if (seq names)
      (let [key (first names)]
        (recur (assoc! out (.toLowerCase ^String key)
                       (.getHeader servlet-req key))
               (rest names)))
      (persistent! out))))

(defn- path-info [^HttpServletRequest request]
  (let [path-info (.substring (.getRequestURI request)
                              (.length (.getContextPath request)))]
    (if (.isEmpty path-info)
      "/"
      path-info)))

(defn- base-request-map [servlet ^HttpServletRequest servlet-req servlet-resp]
  {:server-port       (.getServerPort servlet-req)
   :server-name       (.getServerName servlet-req)
   :remote-addr       (.getRemoteAddr servlet-req)
   :uri               (.getRequestURI servlet-req)
   :query-string      (.getQueryString servlet-req)
   :scheme            (keyword (.getScheme servlet-req))
   :request-method    (keyword (.toLowerCase (.getMethod servlet-req)))
   :headers           (request-headers servlet-req)
   :body              (.getInputStream servlet-req)
   :servlet           servlet
   :servlet-request   servlet-req
   :servlet-response  servlet-resp
   :servlet-context   (.getServletContext ^ServletConfig servlet)
   :context-path      (.getContextPath servlet-req)
   :servlet-path      (.getServletPath servlet-req)
   :path-info         (path-info servlet-req)
   ::protocol         (.getProtocol servlet-req)
   ::async-supported? (.isAsyncSupported servlet-req)})

(defn- add-content-type [req-map ^HttpServletRequest servlet-req]
  (if-let [ctype (.getContentType servlet-req)]
    (let [headers (:headers req-map)]
      (-> (assoc! req-map :content-type ctype)
          (assoc! :headers (assoc headers "content-type" ctype))))
    req-map))

(defn- add-content-length [req-map ^HttpServletRequest servlet-req]
  (let [c (.getContentLengthLong servlet-req)
        headers (:headers req-map)]
    (if (neg? c)
      req-map
      (-> (assoc! req-map :content-length c)
          (assoc! :headers (assoc headers "content-length" c))))))

(defn- add-character-encoding [req-map ^HttpServletRequest servlet-req]
  (if-let [e (.getCharacterEncoding servlet-req)]
    (assoc! req-map :character-encoding e)
    req-map))

(defn- add-ssl-client-cert [req-map ^HttpServletRequest servlet-req]
  (if-let [c (.getAttribute servlet-req "javax.servlet.request.X509Certificate")]
    (assoc! req-map :ssl-client-cert c)
    req-map))

(extend-type HttpServletRequest
  IHTTPRequest
  (request-map [^HttpServletRequest servlet-req ^Servlet service servlet-resp]
    (-> (base-request-map service servlet-req servlet-resp)
        transient
        (add-content-length servlet-req)
        (add-content-type servlet-req)
        (add-character-encoding servlet-req)
        (add-ssl-client-cert servlet-req)
        persistent!))
  IHTTPAsyncRequest
  (start-async! [^ServletRequest servlet-request]
    ;; TODO: fix?
    ;; Embedded Tomcat doesn't allow .startAsync by default, even if the
    ;; Servlet was annotated with asyncSupported=true. We have to
    ;; explicitly set it on the request.
    ;; See http://stackoverflow.com/questions/7749350
    (.setAttribute servlet-request "org.apache.catalina.ASYNC_SUPPORTED" true)
    (doto (.startAsync servlet-request)
      (.setTimeout 0)))
  (async? [^ServletRequest servlet-request]
    (.isAsyncStarted servlet-request)))

(extend-type HttpServletResponse
  IHTTPResponse
  (output-stream [^HttpServletResponse this]
    (.getOutputStream this))
  (set-header! [^HttpServletResponse this h vs]
    (cond
      (= h "Content-Type") (.setContentType this vs)
      (= h "Content-Length") (.setContentLengthLong this (Long/parseLong vs))
      (string? vs) (.setHeader this h vs)
      (sequential? vs) (doseq [v vs] (.addHeader this h v))
      :else
      (throw (ex-info "Invalid header value" {:value vs}))))
  (set-status! [^HttpServletResponse this status]
    (.setStatus this (int status)))
  (committed? [^HttpServletResponse this]
    (.isCommitted this))
  (flush-buffer! [^HttpServletResponse this]
    (.flushBuffer this)))









