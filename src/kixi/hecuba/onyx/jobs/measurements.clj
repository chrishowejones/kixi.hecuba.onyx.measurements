(ns kixi.hecuba.onyx.jobs.measurements
  (:require [clojure.core.async :refer [chan >! <! close! timeout go-loop]]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api]
            [onyx.plugin.kafka]
            [kixi.hecuba.onyx.jobs.shared]
            [kixi.hecuba.onyx.jobs.measurements-connector]))

(def workflow
  [[:event/in-queue       :event/save-measurements]
   [:event/save-measurements  :event/out-confirm]])

(defn build-catalog
  [batch-size batch-timeout]
  [{:onyx/name :event/in-queue
    :onyx/batch-size batch-size
    :onyx/min-peers 1 ;; should be number of partitions
    :onyx/max-peers 1
    :kafka/topic "hecuba-measurements-queue"
    :kafka/group-id "kixi-hecuba-weather"
    :kafka/zookeeper "127.0.0.1:2181"
    :kafka/deserializer-fn :kixi.hecuba.onyx.jobs.shared/deserialize-message-json
    :onyx/plugin :onyx.plugin.kafka/read-messages
    :onyx/type :input
    :onyx/medium :kafka
    :kafka/fetch-size 307200
    :kafka/chan-capacity 1000
    :kafka/offset-reset :smallest
    :kafka/force-reset? false
    :kafka/empty-read-back-off 500
    :kafka/commit-interval 500
    :onyx/doc "Reads messages from a Kafka topic"}

   {:onyx/name :event/save-measurements
    :onyx/plugin :onyx.peer.function/function
    :onyx/fn :kixi.hecuba.onyx.jobs.measurements-connector/get-data
    :onyx/type :function
    :onyx/batch-size batch-size
    :onyx/batch-timeout batch-timeout
    :onyx/doc "Identity output"}

   {:onyx/name :out-confirm
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/batch-size batch-size
    :onyx/batch-timeout batch-timeout
    :onyx/max-peers 1
    :onyx/doc "Writes segments to a core.async channel"}
   ])

(def lifecycles
  (->> (build-catalog 0 0)
       (map :onyx/name)
       (mapv #(hash-map :lifecycle/task %
                        :lifecycle/calls :kixi.hecuba.onyx.jobs.shared/log-calls))
       (into [{:lifecycle/task :event/in-queue
               :lifecycle/calls :onyx.plugin.kafka/read-messages-calls}])))

(def flow-conditions
  [])

(defn build-job
  [mode batch-size batch-timeout]
  {:catalog (build-catalog batch-size batch-timeout)
   :workflow workflow
   :lifecycles lifecycles
   :task-scheduler :onyx.task-scheduler/balanced
   :flow-conditions flow-conditions})
