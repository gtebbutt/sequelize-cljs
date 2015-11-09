(ns sequelize-cljs.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [>! chan close!]]))

(defonce sequelize-instance (atom nil))
(defonce pending (atom []))

(defn sequelize-lib
  []
  (try
    (js/require "sequelize")
    (catch js/Object e nil)))

(defn db-js-map
  "Transform a clojure map to one appropriate for db schema creation"
  [clj-map]
  (clj->js
   (into
    {}
    (for [[k v] clj-map]
      [k (assoc v :field (string/replace (name k) #"-" "_"))]))))

(defn run-or-defer
  [func & [model?]]
  (if @sequelize-instance
    (func)
    (swap! pending conj {:f func :model? model?})))

(defn define-model!
  [model-name fields]
  (run-or-defer
   #(.define @sequelize-instance
             (name model-name)
             (db-js-map fields))
   true)
  model-name)

(defn get-model
  [model-name]
  (when-let [sequelize @sequelize-instance]
    (try
      (.model sequelize (name model-name))
      (catch js/Object e nil))))

(defn init!
  ([uri]
   (init! uri {}))
  ([uri options]
   (when-let [lib (sequelize-lib)]
     (reset! sequelize-instance (lib. uri
                                      ;TODO: Auto-format options from kebab case
                                      (clj->js options)))
     (doseq [item (reverse (sort-by :model? @pending))]
       ((:f item)))
     (reset! pending []))))

(defn sync-db!
  []
  (let [channel (chan)]
    (if-let [sequelize @sequelize-instance]
      (-> sequelize
          (.sync)
          (.then
           (fn [obj]
                 (go (>! channel
                         {:error? false
                          :content obj
                          :raw obj})
                     (close! channel)))

           (fn [err]
             (go (>! channel {:error? true
                              :msg err})
                 (close! channel)))))
      (close! channel))
    channel))

(defn has-many
  [source target & [rel-name]]
  (run-or-defer
   #(let [opts (when rel-name {:as rel-name})]
      (.hasMany (get-model source) (get-model target) (clj->js (or opts {}))))))

(defn belongs-to
  [source target & [rel-name]]
  (run-or-defer
   #(let [opts (when rel-name {:as rel-name})]
      (.belongsTo (get-model source) (get-model target) (clj->js (or opts {}))))))

(defn belongs-to-many
  [source target rel-name]
  (run-or-defer
   #(.belongsToMany (get-model source) (get-model target) (clj->js {:through
                                                                    (or (get-model rel-name)
                                                                        (name rel-name))}))))

(defn types
  [k]
  ;TODO: Expand to cover library fully
  (when-let [sequelize (sequelize-lib)]
    (get
     {:int (.-INTEGER sequelize)
      :string (.-STRING sequelize)
      :jsonb (.-JSONB sequelize)
      :enum (.-ENUM sequelize)}
     k)))
