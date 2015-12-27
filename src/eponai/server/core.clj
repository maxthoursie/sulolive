(ns eponai.server.core
  (:gen-class)
  (:require [compojure.core :refer :all]
            [eponai.server.datomic.pull :as p]
            [eponai.server.datomic.transact :as t]
            [eponai.server.auth :as a]
            [datomic.api :only [q db] :as d]
            [cemerick.friend :as friend]
            [eponai.server.openexchangerates :as exch]
            [clojure.core.async :refer [>! <! go chan]]
            [clojure.edn :as edn]
            [ring.adapter.jetty :as jetty]
            [eponai.server.datomic_dev :refer [connect!]]
            [eponai.server.parser :as parser]
            [eponai.server.api :as api :refer [api-routes]]
            [eponai.server.site :refer [site-routes]]
            [eponai.server.middleware.api :as m]))

(def currency-chan (chan))
(def email-chan (chan))

(def parser (parser/parser {:read parser/read :mutate parser/mutate}))

(defn init
  ([]
   (println "Using remote resources.")
   (let [conn (connect!)]
     ;; Defines the 'app var when init is run.
     (def app
       (-> (routes api-routes site-routes)
           (friend/authenticate {:credential-fn       (partial a/cred-fn #(api/user-creds (d/db conn) %))
                                 :workflows           [(a/form)]
                                 :default-landing-uri "/dev/budget.html"})
           m/wrap-error
           m/wrap-transit
           (m/wrap-state {::m/conn          conn
                          ::m/parser        parser
                          ::m/currency-chan currency-chan
                          ::m/email-chan    email-chan})
           m/wrap-defaults
           m/wrap-log
           m/wrap-gzip))

     (init conn
           (partial exch/currency-rates nil)
           (partial a/send-email-verification (a/smtp)))))
  ([conn cur-fn email-fn]
   (println "Initializing server...")

   (go (while true (try
                     (api/post-currency-rates conn cur-fn (<! currency-chan))
                     (catch Exception e
                       (println (.getMessage e))))))
   (go (while true (try
                     (api/send-email-verification email-fn (<! email-chan))
                     (catch Exception e
                       (println (.getMessage e))))))
   (println "Done.")))

(defn -main [& args]
  (init)
  (let [default-port 3000
        port (try
               (Long/parseLong (first args))
               (catch Exception e
                 default-port))]
    ;; by passing (var app) to run-jetty, it'll be forced to
    ;; evaluate app as code changes.
    (jetty/run-jetty (var app) {:port port})))

(defn main-debug
  "For repl-debug use.
  Returns a future with the jetty-server.
  The jetty-server will block the current thread, so
  we just wrap it in something dereffable."
  []
  (future (-main)))
