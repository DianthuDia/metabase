(ns metabase.feature-extraction.core
  "Feature extraction for various models."
  (:require [clojure.walk :refer [postwalk]]
            [kixi.stats.math :as math]
            [medley.core :as m]
            [metabase.db.metadata-queries :as metadata]
            [metabase.feature-extraction
             [comparison :as comparison]
             [costs :as costs]
             [descriptions :refer [add-descriptions]]
             [feature-extractors :as fe]
             [values :as values]]
            [metabase.models
             [card :refer [Card]]
             [field :refer [Field]]
             [metric :refer [Metric]]
             [segment :refer [Segment]]
             [table :refer [Table]]]
            [metabase.query-processor :as qp]
            [metabase.util :as u]
            [redux.core :as redux]))

(defmulti
  ^{:doc "Given a model, fetch corresponding dataset and compute its features.

          Takes a map of options as first argument. Recognized options:
          * `:max-cost`   a map with keys `:computation` and `:query` which
                          limits maximal resource expenditure when computing
                          features.
                          See `metabase.feature-extraction.costs` for details.

                          Note: `extract-features` for `Card`s does not support
                          sampling."
    :arglists '([opts model])}
  extract-features #(type %2))

(def ^:private ^:const ^Long max-sample-size 10000)

(defn- sampled?
  [{:keys [max-cost] :as opts} dataset]
  (and (not (costs/full-scan? max-cost))
       (= (count (:rows dataset dataset)) max-sample-size)))

(defn- extract-query-opts
  [{:keys [max-cost]}]
  (cond-> {}
    (not (costs/full-scan? max-cost)) (assoc :limit max-sample-size)))

(defmethod extract-features (type Field)
  [opts field]
  (let [{:keys [field row]} (values/field-values field (extract-query-opts opts))]
    {:features (->> row
                    (fe/field->features opts field)
                    (merge {:table (Table (:table_id field))}))
     :sample?  (sampled? opts row)}))

(defmethod extract-features (type Table)
  [opts table]
  (let [dataset (values/query-values (metadata/db-id table)
                                     (merge (extract-query-opts opts)
                                            {:source-table (:id table)}))]
    {:constituents (fe/dataset->features opts dataset)
     :features     {:table table}
     :sample?      (sampled? opts dataset)}))

(defn index-of
  "Return index of the first element in `coll` for which `pred` reutrns true."
  [pred coll]
  (first (keep-indexed (fn [i x]
                         (when (pred x) i))
                       coll)))

(defn- ensure-aligment
  [fields cols rows]
  (if (not= fields (take 2 cols))
    (eduction (map (apply juxt (for [field fields]
                                 (let [idx (index-of #{field} cols)]
                                   #(nth % idx)))))
              rows)
    rows))

(defmethod extract-features (type Card)
  [opts card]
  (let [{:keys [rows cols] :as dataset} (values/card-values card)
        {:keys [breakout aggregation]}  (group-by :source cols)
        fields                          [(first breakout)
                                         (or (first aggregation)
                                             (second breakout))]]
    {:constituents (fe/dataset->features opts dataset)
     :features     (merge (when (every? some? fields)
                            (fe/field->features
                             (->> card
                                  :dataset_query
                                  :query
                                  (assoc opts :query))
                             fields
                             (ensure-aligment fields cols rows)))
                          {:card  card
                           :table (Table (:table_id card))})
     :dataset      dataset
     :sample?      (sampled? opts dataset)}))

(defmethod extract-features (type Segment)
  [opts segment]
  (let [dataset (values/query-values (metadata/db-id segment)
                                     (merge (extract-query-opts opts)
                                            (:definition segment)))]
    {:constituents (fe/dataset->features opts dataset)
     :features     {:table   (Table (:table_id segment))
                    :segment segment}
     :sample?      (sampled? opts dataset)}))

(defn- trim-decimals
  [decimal-places features]
  (postwalk
   (fn [x]
     (if (float? x)
       (u/round-to-decimals (+ (- (min (u/order-of-magnitude x) 0))
                               decimal-places)
                            x)
       x))
   features))

(defn x-ray
  "Turn feature vector into an x-ray."
  [features]
  (let [prettify (comp add-descriptions (partial trim-decimals 2) fe/x-ray)]
    (-> features
        (u/update-when :features prettify)
        (u/update-when :constituents (fn [constituents]
                                       (if (sequential? constituents)
                                         (map x-ray constituents)
                                         (m/map-vals prettify constituents)))))))

(defn- top-contributors
  [comparisons]
  (if (map? comparisons)
    (->> comparisons
         (comparison/head-tails-breaks (comp :distance val))
         (mapcat (fn [[field {:keys [top-contributors distance]}]]
                   (for [[feature {:keys [difference]}] top-contributors]
                     {:feature      feature
                      :field        field
                      :contribution (* (math/sqrt distance) difference)})))
         (comparison/head-tails-breaks :contribution))
    (->> comparisons
         :top-contributors
         (map (fn [[feature difference]]
                {:feature    feature
                 :difference difference})))))

(defn compare-features
  "Compare feature vectors of two models."
  [opts a b]
  (let [[a b]       (map (partial extract-features opts) [a b])
        comparisons (if (:constituents a)
                      (into {}
                        (map (fn [[field a] [_ b]]
                               [field (comparison/features-distance a b)])
                             (:constituents a)
                             (:constituents b)))
                      (comparison/features-distance (:features a)
                                                    (:features b)))]
    {:constituents     [a b]
     :comparison       comparisons
     :top-contributors (top-contributors comparisons)
     :sample?          (some :sample? [a b])
     :significant?     (if (:constituents a)
                         (some :significant? (vals comparisons))
                         (:significant? comparisons))}))
