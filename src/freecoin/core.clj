;; Freecoin - digital social currency toolkit

;; part of Decentralized Citizen Engagement Technologies (D-CENT)
;; R&D funded by the European Commission (FP7/CAPS 610349)

;; Copyright (C) 2015 Dyne.org foundation
;; Copyright (C) 2015 Thoughtworks, Inc.

;; Sourcecode designed, written and maintained by
;; Denis Roio <jaromil@dyne.org>

;; With contributions by
;; Gareth Rogers <grogers@thoughtworks.com>
;; Duncan Mortimer <dmortime@thoughtworks.com>

;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.

;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.

;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns freecoin.core
  (:require [org.httpkit.server :as server]
            [liberator.dev :as ld]
            [liberator.representation :as lr]
            [taoensso.timbre :as log]
            [clojure.data.json :as json]
            [ring.middleware.defaults :as ring-mw]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.logger :as mw-logger]
            [scenic.routes :as scenic]
            [stonecutter-oauth.client :as soc]
            [freecoin.db.mongo :as mongo]
            [freecoin.db.storage :as storage]
            [freecoin.blockchain :as blockchain]
            [freecoin.translation :as translation]
            [freecoin.routes :as routes]
            [freecoin.config :as config]
            [freecoin.handlers.sign-in :as sign-in]
            [freecoin.handlers.participants :as participants]
            [freecoin.handlers.transaction-form :as transaction-form]
            [freecoin.handlers.tags :as tags]
            [freecoin.handlers.confirm-transaction-form :as confirm-transaction-form]
            [freecoin.handlers.transactions-list :as transactions-list]
            [freecoin.handlers.debug :as debug]
            [freecoin.handlers.qrcode :as qrcode]))

(defn not-found [request]
  {:status 404
   :body "Oops! Page not found."})

(defn create-stonecutter-config [config-m]
  (soc/configure (config/auth-url config-m)
                 (config/client-id config-m)
                 (config/client-secret config-m)
                 (routes/absolute-path :sso-callback)))

(defmethod lr/render-seq-generic "application/activity+json" [data _]
  (json/write-str data))

(defmethod lr/render-map-generic "application/activity+json" [data _]
  (json/write-str data))

(defmethod lr/render-seq-generic "application/json" [data _]
  (json/write-str data))

(defmethod lr/render-map-generic "application/json" [data _]
  (json/write-str data))

(defn todo [_]
  {:status 503
   :body "Work-in-progress"
   :headers {"Content-Type" "text/html"}})

(defn handlers [config-m stores-m blockchain]
  (let [wallet-store (storage/get-wallet-store stores-m)
        confirmation-store (storage/get-confirmation-store stores-m)
        sso-configuration (create-stonecutter-config config-m)]
    (when (= :invalid-configuration sso-configuration)
      (throw (Exception. "Invalid stonecutter configuration. Application launch aborted.")))
    {:version                       (debug/version sso-configuration)
     :echo                          (debug/echo    sso-configuration)
     :qrcode                        (qrcode/qr-participant-sendto wallet-store)
     :index                         sign-in/index-page
     :landing-page                  (sign-in/landing-page wallet-store blockchain)
     :sign-in                       (sign-in/sign-in sso-configuration)
     :sso-callback                  (sign-in/sso-callback wallet-store blockchain sso-configuration)
     :sign-out                      sign-in/sign-out
     :forget-secret                 sign-in/forget-secret
     :account                       (participants/account      wallet-store blockchain)
     :get-participant-search-form   (participants/query-form   wallet-store)
     :participants                  (participants/participants wallet-store)

     :get-transaction-form          (transaction-form/get-transaction-form          wallet-store)
     :post-transaction-form         (transaction-form/post-transaction-form         wallet-store confirmation-store)

     :get-all-tags                  (tags/get-tags blockchain)

     :get-confirm-transaction-form  (confirm-transaction-form/get-confirm-transaction-form  wallet-store confirmation-store)
     :post-confirm-transaction-form (confirm-transaction-form/post-confirm-transaction-form wallet-store confirmation-store blockchain)
     :get-user-transactions         (transactions-list/list-user-transactions        wallet-store blockchain)
     :get-all-transactions          (transactions-list/list-all-transactions         wallet-store blockchain)

     :get-activity-streams          (transactions-list/list-all-activity-streams  wallet-store blockchain)
     :nxt                           todo}))

(defn handle-anti-forgery-error [request]
  (log/warn "ANTY_FORGERY_ERROR - headers: " (:headers request))
  {:status 403
   :body "CSRF token mismatch"})

(defn defaults [secure?]
  (if secure?
    (assoc ring-mw/secure-site-defaults :proxy true)
    ring-mw/site-defaults))

(defn wrap-defaults-config [session-store secure?]
  (-> (defaults secure?)
      (assoc-in [:session :cookie-name] "freecoin-session")
      (assoc-in [:session :flash] true)
      (assoc-in [:session :store] session-store)
      (assoc-in [:security :anti-forgery] {:error-handler handle-anti-forgery-error})))

(defn conditionally-wrap-with [handler wrapper wrap-when-true]
  (if wrap-when-true
    (wrapper handler)
    handler))

(defn create-app [config-m stores-m blockchain]
  (let [debug-mode (config/debug config-m)]
    (-> (scenic/scenic-handler routes/routes (handlers config-m stores-m blockchain) not-found)
        (conditionally-wrap-with #(ld/wrap-trace % :header :ui) debug-mode)
        (ring-mw/wrap-defaults (wrap-defaults-config (cookie-store (config/cookie-secret config-m))
                                                     (config/secure? config-m)))
        #_(mw-logger/wrap-with-logger))))

;; launching and halting the app
(defonce app-state (atom {}))

(defn connect-db [app-state]
  (if (:db app-state)
    app-state
    (let [config-m (config/create-config)
          db (-> config-m config/mongo-uri mongo/get-mongo-db)]
      (assoc app-state :db db))))

(defn disconnect-db [app-state]
  (when (:db app-state)
    (prn "WIP: disconnect db"))
  (dissoc app-state :db))

(defn disconnect-stores [app-state]
  (when (:stores-m app-state)
    (prn "WIP: disconnect stores"))
  (dissoc app-state :stores-m))

(defn launch [app-state]
  (if (:server app-state)
    app-state
    (if-let [db (:db app-state)]
      (let [config-m (config/create-config)
            stores-m (storage/create-mongo-stores db)
            blockchain (blockchain/new-stub db)
            server (-> (create-app config-m stores-m blockchain)
                       (server/run-server {:port (config/port config-m)
                                           :host (config/host config-m)}))]
        
        (assoc app-state
               :server server
               :stores-m stores-m)))))

(defn halt [app-state]
  (when-let [server (:server app-state)]
    (prn "Stopping ring server")
    (server)
    (prn "Ring server stopped")
    (dissoc app-state :server))
  (prn "There was no Ring server to stop"))

;; For running from the repl
(defn start []
  (swap! app-state (comp launch connect-db)))

(defn stop []
  (swap! app-state (comp disconnect-db disconnect-stores halt)))

(defn empty-db []
  (if-let [stores-m (:stores-m @app-state)]
    (storage/empty-db-stores stores-m)
    (println "No db found")))

;; For running using lein-ring server
(defonce lein-ring-handler (atom nil))

(defn lein-ring-server [req]
  (if-let [handler @lein-ring-handler]
    (handler req)))

(defn lein-ring-init []
  (prn "lein-ring-init")
  (swap! app-state connect-db)
  (swap! lein-ring-handler
                  (fn [_] (let [config-m (config/create-config)
                                db (:db app-state)
                                stores-m (storage/create-mongo-stores db)
                                blockchain (blockchain/new-stub db)]
                            (prn "Restarting server....")
                            (create-app config-m stores-m blockchain)))))

(defn lein-ring-stop []
  (swap! app-state disconnect-db))
