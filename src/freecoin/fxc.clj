;; Freecoin - digital social currency toolkit

;; part of Decentralized Citizen Engagement Technologies (D-CENT)
;; R&D funded by the European Commission (FP7/CAPS 610349)

;; Copyright (C) 2015 Dyne.org foundation

;; Sourcecode designed, written and maintained by
;; Denis Roio <jaromil@dyne.org>

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


(ns freecoin.fxc
  (:require [freecoin.secretshare :as ssss]
            [freecoin.random :as rand]
            [freecoin.utils :as util]
            [clojure.string :as str]))

(declare render-slice)
(declare extract-quorum)
(declare extract-ahal)
(declare extract-int)


(defn new-passphrase [conf type]
  (format "%s_%s_%s_FXC_%s_%d" (:prefix conf) type
          (:integer (rand/create (:length conf)))
          (:integer (rand/create (:length conf)))
          0))

(defn create-secret
  "Takes a configuration, the blockchain type and optionally two integers, creates a wallet address, returned as a string"
  ([conf type]
   (let [ah (:integer (rand/create (:length conf)))
         al (:integer (rand/create (:length conf)))]
     (create-secret conf type ah al)))

  ([conf type hi lo]
   {:pre  [(contains? conf :version)
           (contains? conf :length)
           (contains? conf :entropy)]
    :post [(= (:total conf) (count (:slices %)))]}

   (let [ah (ssss/shamir-split conf hi)
         al (ssss/shamir-split conf lo)]

     ;; secret wallet rendering
     ;; slices: collection of rendered string slices for ssss

     ;; unique id
     {:_id (format "%s_%s_%s_FXC_%s" (:prefix conf) type
                   (get-in ah [:header :_id])
                   (get-in al [:header :_id]))
      :config (assoc conf :type type)

      :slices (loop [lah (first (:shares ah))
                     lal (first (:shares al))
                     res []
                     c 1]
                (if (< c (count (:shares ah)))
                  (recur (nth (:shares ah) c)
                         (nth (:shares al) c)
                         (merge res (render-slice conf type lah lal c))
                         (inc c))
                  (merge res (render-slice conf type lah lal c))))

      ;; to be deleted by http session
      :cookie (render-slice conf type
                            (first (:shares ah))
                            (first (:shares al)) 1)})))

(defn unlock-secret
  "Takes a secret and a cookie, returns a ready to use NXT passphrase"
  [conf secret slice]
  {:pre [(contains? conf :quorum)
         (contains? secret :slices)]}
  (let [quorum (extract-quorum conf secret slice)
        ah (ssss/shamir-combine (:ah quorum))
        al (ssss/shamir-combine (:al quorum))]
    (render-slice conf ah al 0)))

(defn extract-quorum
  "Takes a config, a secret and a slice and tries to combine the secret and the slice in a collection of integers ready for shamir-combine. Returns a map {:ah :al} with the collections."
  [conf secret slice]
  {:pre  [(contains? conf :quorum)
          (contains? secret :slices)
          (seq slice)]}
  ;; TODO: fix some off-by-one problem here (assert fails with +1)
  ;; :post [(= (count (get-in % (:ah :shares))) (:quorum conf))]}

  (let [ordered (sort-by last (:slices secret))]
  ;; reconstruct a collection of slices
  (loop [cah [(extract-int conf slice "ah")]
         cal [(extract-int conf slice "al")]
         c 1]

    ;; (util/log! 'ACK 'extract-quorum (str "ah:    " [ c cah]))
    ;; (util/log! 'ACK 'extract-quorum (str "al:    " [ c cal]))

    (if (< (count cah) (:quorum conf))

      (recur
       (conj cah (extract-int conf (nth ordered c) "ah"))
       (conj cal (extract-int conf (nth ordered c) "al"))
       (inc c))

      ;; return
      {:ah {:header {:_id (:_id secret)
                     :quorum (int (:quorum conf))
                     :total (int (:total conf))
                     :prime (:prime conf)
                     :description (:description conf)}
            :shares (map biginteger (conj cah (extract-int conf (nth (:slices secret) c) "ah")))}
       :al {:header {:_id (:_id secret)
                     :quorum (int (:quorum conf))
                     :total (int (:total conf))
                     :prime (:prime conf)
                     :description (:description conf)}
            :shares (map biginteger (conj cal (extract-int conf (nth (:slices secret) c) "al")))}}))))

(defn render-slice [conf type ah al idx]
   (format "%s_%s_%s_FXC_%s_%d" (:prefix conf) type ah al idx))

(defn extract-ahal
  "extract the high or low part in an fxc address"
  [conf addr ah-or-al]
  (try
    (let [toks (str/split (util/trunc addr 128) #"_")]
      (if-not (= (subs (first toks) 0 4) "FXC1")
        (throw (Exception.
                (format "Invalid FXC address: %s" (first toks))))
        (nth toks (case ah-or-al "al" 4 "ah" 2 0))
        ;; TODO: check that only valid characters in alphabet are present
        ))

    (catch Exception e
      (let [error (.getMessage e)]
        (util/log! 'ERR 'fxc/extract-lo error)))

    (finally)))

(defn extract-int [conf addr ah-or-al]
   (extract-ahal conf addr ah-or-al))
