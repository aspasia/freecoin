(ns freecoin.routes-test
  (:use midje.sweet)
  (:require [freecoin.routes :as routes]
            [bidi.bidi :as bidi]))

(def an-email-address "name.surname@mail.com")

(fact "Checking bidi routes"
      (fact "Checking that the account route containing an email address works"
            (bidi/match-route routes/routes (str "/account/" an-email-address) :request-method :get)  =>
            {:route-params {:email an-email-address}, :handler :account, :request-method :get})

      (fact "Checking that the account route doesn't work for something else"
            (bidi/match-route routes/routes (str "/account/" "something-else") :request-method :get)  =>
            nil))
