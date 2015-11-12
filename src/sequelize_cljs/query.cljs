(ns sequelize-cljs.query
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [clojure.string :as string]
            [clojure.walk :refer [postwalk]]
            [cljs.core.async :refer [>! chan close!]]
            [sequelize-cljs.core :refer [get-model sequelize-instance]]))

(defn parse-model
  [obj]
  (if obj
    (js->clj (.get obj #js {:plain true}) :keywordize-keys true)
    {}))

(defn process-params
  [m]
  (clj->js
   (let [f (fn [[k v]] (if (= (name k) "model") [k (get-model v)] [k v]))]
     (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m))))

(defn async-sequelize
  [{:keys [model id params raw? array? sql-fn plain-obj?]}]
  (assert (or id params))
  (let [channel (chan)
        model-obj (or (get-model model) @sequelize-instance)]
    (-> model-obj
        (sql-fn (or id (process-params params)))
        (.then (fn [obj]
                 (go (>! channel
                         {:error? false
                          :content (cond
                                    plain-obj?
                                    (js->clj obj :keywordize-keys true)

                                    array?
                                    (for [item obj]
                                      (parse-model item))

                                    :else
                                    (parse-model obj))
                          :raw (when raw? obj)})
                     (close! channel)))

               (fn [err]
                 (go (>! channel {:error? true
                                  :msg err})
                     (close! channel)))))
    channel))

(defn raw-query
  [query-str params & {:keys [raw?]}]
  (async-sequelize {:params params
                    :raw? raw?
                    :plain-obj? true
                    :sql-fn #(.query %1 query-str %2)}))

(defn create
  [model params & {:keys [raw? include]}]
  (async-sequelize {:model model
                    :params params
                    :raw? raw?
                    :sql-fn (if include
                              #(.create %1 %2 (process-params {:include include}))
                              #(.create %1 %2))}))

(defn find-all
  [model params & {:keys [raw?]}]
  (async-sequelize {:model model
                    :params params
                    :raw? raw?
                    :array? true
                    :sql-fn #(.findAll %1 %2)}))

(defn find-one
  [model params & {:keys [raw?]}]
  (async-sequelize {:model model
                    :params params
                    :raw? raw?
                    :array? false
                    :sql-fn #(.findOne %1 %2)}))

(defn find-by-id
  [model id & {:keys [raw?]}]
  (async-sequelize {:model model
                    :id id
                    :raw? raw?
                    :sql-fn #(.findById %1 %2)}))
