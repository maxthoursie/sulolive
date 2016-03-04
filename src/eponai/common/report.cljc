(ns eponai.common.report
  (:require
    #?(:clj [taoensso.timbre :refer [debug]]
       :cljs [taoensso.timbre :refer-macros [debug]])))

(defn converted-amount [tx]
  #?(:clj  (with-precision 2 (/ (:transaction/amount tx)
                                (:conversion/rate (:transaction/conversion tx))))
     :cljs (/ (:transaction/amount tx)
              (:conversion/rate (:transaction/conversion tx)))))

(defmulti sum (fn [k _ _] k))

(defmethod sum :default
  [_ data-filter transactions]
  (let [sum-fn (fn [s tx]
                 (+ s (converted-amount tx)))]
    {:key    "All Transactions"
     :values [(reduce sum-fn 0 transactions)]}))

(defmethod sum :transaction/date
  [_ data-filter transactions]
  (let [grouped (group-by :transaction/date transactions)
        sum-fn (fn [[day ts]]
                 (assoc day :date/sum (reduce (fn [s tx]
                                                (+ s (converted-amount tx)))
                                              0
                                              ts)))
        sum-by-day (map sum-fn grouped)]
    [{:key    "All Transactions"
      :values (reduce #(conj %1
                             {:name  (:date/timestamp %2)   ;date timestamp
                              :value (:date/sum %2)})       ;sum for date
                      []
                      (sort-by :date/timestamp sum-by-day))}]))

(defmethod sum :transaction/tags
  [_ data-filter transactions]
  (let [sum-fn (fn [m transaction]
                 (let [include-tag-names (map :tag/name (:filter/include-tags data-filter))
                       tags (:transaction/tags transaction)
                       filtered-tags (if (seq include-tag-names)
                                       (filter #(contains? (set include-tag-names) (:tag/name %)) tags)
                                       tags)]
                   (reduce (fn [m2 tagname]
                             (update m2 tagname
                                     (fn [me]
                                       (if me
                                         (+ me (converted-amount transaction))
                                         (converted-amount transaction)))))
                           m
                           (if (empty? filtered-tags)
                             ["no tags"]
                             (map :tag/name filtered-tags)))))
        sum-by-tag (reduce sum-fn {} transactions)]

    [{:key    "All Transactions"
      :values (reduce #(conj %1 {:name  (first %2)          ;tag name
                                 :value (second %2)})       ;sum for tag
                      []
                      sum-by-tag)}]))


(defmulti calculation (fn [_ function-id _ _] function-id))

(defmethod calculation :report.function.id/sum
  [{:keys [report/group-by]} _ data-filter transactions]
  (sum group-by data-filter transactions))

(defn generate-data [report data-filter transactions]
  (let [functions (:report/functions report)
        k (:report.function/id (first functions))]
    (calculation report (or k :report.function.id/sum) data-filter transactions)))