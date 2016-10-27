(ns eponai.web.ui.project.add-transaction
  (:require
    [cljsjs.moment]
    [cljsjs.pikaday]
    [cljsjs.react-select]
    [datascript.core :as d]
    [eponai.client.ui :refer [map-all] :refer-macros [style opts]]
    [eponai.client.parser.message :as message]
    [eponai.common.format.date :as date]
    [eponai.web.ui.daterangepicker :refer [->DateRangePicker]]
    [eponai.web.ui.select :as sel]
    [eponai.web.ui.utils :as utils]
    [garden.core :refer [css]]
    [om.next :as om :refer-macros [defui]]
    [sablono.core :refer-macros [html]]
    [taoensso.timbre :refer-macros [debug]]))

(defn- delete-tag-fn [component tag]
  (om/update-state! component update-in [:input-transaction :transaction/tags] disj tag))

(defn- add-tag [component tag]
  (om/update-state! component
                    #(-> %
                         (assoc :input-tag "")
                         (update-in [:input-transaction :transaction/tags] conj tag))))

(defui AddTransaction
  static om/IQuery
  (query [_]
    [:query/message-fn
     {:query/all-currencies [:currency/code]}
     {:query/all-tags [:tag/name]}
     {:query/all-categories [:category/name]}])
  Object
  (add-transaction [this]
    (let [st (om/get-state this)
          update-category (fn [tx]
                               (let [{:keys [transaction/category]} tx]
                                 (if (nil? category)
                                   (dissoc tx :transaction/category)
                                   (update tx :transaction/category (fn [{:keys [label _]}]
                                                                      {:category/name label})))))
          message-id (message/om-transact! this
                                           `[(transaction/create
                                               ~(-> (:input-transaction st)
                                                    (assoc :transaction/uuid (d/squuid))
                                                    (update :transaction/currency :value)
                                                    update-category
                                                    (update :transaction/tags (fn [ts]
                                                                                (map (fn [{:keys [label _]}]
                                                                                       {:tag/name label}) ts)))
                                                    ;(update :transaction/category (fn [{:keys [label _]}]
                                                    ;                                {:category/name label}))
                                                    (assoc :transaction/created-at (.getTime (js/Date.)))))
                                             :routing/project])]
      (om/update-state! this assoc :pending-transaction message-id)))
  (initLocalState [this]
    (let [{:keys [query/all-currencies query/all-categories]} (om/props this)
          {:keys [project-id]} (om/get-computed this)
          usd-entity (some #(when (= (:currency/code %) "USD") %) all-currencies)
          category-entity (first all-categories)]
      {:input-transaction {:transaction/date     (date/date-map (date/today))
                           :transaction/tags     #{}
                           :transaction/currency {:label (:currency/code usd-entity)
                                                  :value (:db/id usd-entity)}
                           ;:transaction/category {:label (:category/name category-entity)
                           ;                       :value (:db/id category-entity)}
                           :transaction/project  project-id
                           :transaction/type     :transaction.type/expense}
       :type              :expense}))
  (componentDidUpdate [this _ _]
  (when-let [history-id (:pending-transaction (om/get-state this))]
    (let [{:keys [query/message-fn]} (om/props this)
          {:keys [on-close]} (om/get-computed this)
          message (message-fn history-id 'transaction/create)]
      (when message
        (js/alert (message/message message))
        (when on-close
          (on-close))))))

  (render [this]
    (let [{:keys [type input-transaction]} (om/get-state this)
          {:keys [transaction/date transaction/currency transaction/category]} input-transaction
          {:keys [query/all-currencies
                  query/all-tags
                  query/all-categories]} (om/props this)]
      ;(debug "Input transaction: " input-transaction)
      (html
        [:div#add-transaction
         [:h4.header "New Transaction"]
         [:div.top-bar-container.subnav
          [:div.top-bar
           [:div.top-bar-left.menu
            [:a
             {:class    (when (= type :expense) "active")
              :on-click #(om/update-state! this (fn [st]
                                                  (-> st
                                                      (assoc :type :expense)
                                                      (assoc-in [:input-transaction :transaction/type] :transaction.type/expense))))}
             "Expense"]
            [:a
             {:class    (when (= type :income) "active")
              :on-click #(om/update-state! this (fn [st]
                                                  (-> st
                                                      (assoc :type :income)
                                                      (assoc-in [:input-transaction :transaction/type] :transaction.type/income))))}
             "Income"]
            [:a
             {:class    (when (= type :accomodation) "active")
              :on-click #(om/update-state! this assoc :type :accomodation)}
             "Accomodation"]
            [:a
             {:class    (when (= type :transport) "active")
              :on-click #(om/update-state! this assoc :type :transport)}
             "Transport"]
            [:a
             {:class    (when (= type :atm) "active")
              :on-click #(om/update-state! this assoc :type :atm)}
             "ATM"]]]]
         [:div.content


          [:div.content-section
           [:div.row
            [:div.columns.small-3.text-right
             [:label "Date:"]]

            [:div.columns.small-4.end
             (->DateRangePicker (om/computed {:single-calendar? true
                                              :start-date       (date/date-time date)}
                                             {:on-apply #(om/update-state! this assoc-in [:input-transaction :transaction/date] %)
                                              :format   "MMM dd"}))]]

           [:div.row
            [:div.columns.small-3.text-right
             [:label "Amount:"]]
            [:div.columns.small-4
             [:input
              {:type        "number"
               :min         "0"
               :placeholder "0.00"
               :on-change   #(om/update-state! this assoc-in [:input-transaction :transaction/amount] (.. % -target -value))}]]
            [:div.columns.small-2.text-right
             [:label "Currency:"]]
            [:div.columns.small-3
             (sel/->Select (om/computed {:options (map (fn [{:keys [currency/code db/id]}]
                                                                {:label code
                                                                 :value id})
                                                              all-currencies)
                                         :value currency}
                                        {:on-select #(om/update-state! this assoc-in [:input-transaction :transaction/currency] %)}))]]
           [:div.row
            [:div.columns.small-3.text-right
             [:label "Category:"]]
            [:div.columns.small-9
             (sel/->Select (om/computed {:options (map (fn [c]
                                                         {:label (:category/name c)
                                                          :value (:db/id c)})
                                                       all-categories)
                                         :value   category
                                         :clearable true}
                                        {:on-select #(om/update-state! this assoc-in [:input-transaction :transaction/category] %)}))
             [:input

              {:type "text"
               :on-change #(om/update-state! this assoc-in [:input-transaction :transaction/category :category/name] (.. % -target -value))}]]]

           [:div.row
            [:div.columns.small-3.text-right
             [:label "Tags:"]]
            [:div.columns.small-9
             (sel/->SelectTags (om/computed {:options (map (fn [{:keys [tag/name db/id]}]
                                                             {:label name
                                                              :value id})
                                                           all-tags)
                                             :value   #js []}
                                            {:on-select #(om/update-state! this assoc-in [:input-transaction :transaction/tags] %)}))
             ]]

           [:div.row
            [:div.columns.small-3.text-right
             [:label "Note:"]]
            [:div.columns.small-9
             [:textarea
              {:type "text"}]]]]

          [:div.content-section.clearfix
           [:a.button.hollow.float-right
            {:on-click #(.add-transaction this)}
            "Save"]]]]))))

;(defui AddTransaction
;  static om/IQuery
;  (query [_]
;    [:query/message-fn
;     {:query/all-currencies [:currency/code]}
;     {:query/all-projects [:project/uuid
;                          :project/name]}])
;  Object
;  (initLocalState [this]
;    (let [{:keys [query/all-currencies
;                  query/all-projects]} (om/props this)]
;      {:input-transaction {:transaction/date     (date/date-map (date/today))
;                           :transaction/tags     #{}
;                           :transaction/currency {:currency/code (-> all-currencies
;                                                                     first
;                                                                     :currency/code)}
;                           :transaction/project  {:project/uuid (-> all-projects
;                                                                    first
;                                                                    :project/uuid)}
;                           :transaction/type     :transaction.type/expense}}))
;  (add-transaction [this]
;    (let [st (om/get-state this)
;          message-id (message/om-transact! this
;                                           `[(transaction/create
;                                               ~(-> (:input-transaction st)
;                                                    (assoc :transaction/uuid (d/squuid))
;                                                    (assoc :transaction/created-at (.getTime (js/Date.)))
;                                                    ;(update :transaction/date (fn [d] {:date/ymd (f/date->ymd-string d)}))
;                                                    ))
;                                             :routing/project])]
;      (om/update-state! this assoc :pending-transaction message-id)))
;
;  (toggle-input-type [this]
;    (let [{:keys [input-transaction]} (om/get-state this)
;          type (:transaction/type input-transaction)]
;      (cond (= type :transaction.type/expense)
;            (om/update-state! this assoc-in [:input-transaction :transaction/type] :transaction.type/income)
;
;            (= type :transaction.type/income)
;            (om/update-state! this assoc-in [:input-transaction :transaction/type] :transaction.type/expense))))
;
;  (select-date [this date]
;    (om/update-state! this assoc-in [:input-transaction :transaction/date] date))
;  (select-project [this project-uuid]
;    (om/update-state! this assoc-in [:input-transaction :transaction/project :project/uuid] (uuid project-uuid)))
;
;  (ui-config [this]
;    (let [{:keys [input-transaction]} (om/get-state this)
;          type (:transaction/type input-transaction)]
;      (cond (= type :transaction.type/expense)
;            {:btn-class "button alert small"
;             :i-class "fa fa-minus"}
;
;            (= type :transaction.type/income)
;            {:btn-class "button success small"
;             :i-class "fa fa-plus"})))
;
;  (componentDidUpdate [this prev-props prev-state]
;    (when-let [history-id (:pending-transaction (om/get-state this))]
;      (let [{:keys [query/message-fn]} (om/props this)
;            message (message-fn history-id 'transaction/create)]
;        (when message
;          (debug "Got message: " message)
;          (js/alert (message/message message))
;          ((:on-close (om/get-computed this)))))))
;  (render
;    [this]
;    (let [{:keys [query/all-currencies
;                  query/all-projects]} (om/props this)
;          {:keys [input-tag
;                  input-transaction]} (om/get-state this)
;          {:keys [transaction/date transaction/tags transaction/currency
;                  transaction/project transaction/amount transaction/title
;                  transaction/type]} input-transaction
;          {:keys [on-close]} (om/get-computed this)
;          {:keys [btn-class
;                  i-class] :as conf} (.ui-config this)]
;      (html
;        [:div
;
;         [:div
;          {:class (if (= type :transaction.type/expense) "alert" "success")}
;          [:h3
;           (if (= type :transaction.type/expense)
;             "New expense"
;             "New income")]]
;
;         [:div
;          [:label
;           "Project:"]
;          [:select
;           {:on-change     #(.select-project this (.-value (.-target %)))
;            :type          "text"
;            :default-value project}
;           (map-all all-projects
;                    (fn [project]
;                      [:option
;                       (opts {:value (:project/uuid project)
;                              :key   [(:db/id project)]})
;                       (or (:project/name project) "Untitled")]))]
;
;          [:label
;           "Amount:"]
;          ;; Input amount with currency
;          [:div.input-group
;           [:a.input-group-button
;            (opts {:on-click #(.toggle-input-type this)
;                   :class    btn-class})
;            [:i
;             {:class i-class}]]
;           [:input.input-group-field
;            (opts {:type        "number"
;                   :placeholder "0.00"
;                   :min         "0"
;                   :value       (or amount "")
;                   :on-change   (utils/on-change-in this [:input-transaction :transaction/amount])})]
;
;           [:select.input-group-field
;            (opts {:on-change     (utils/on-change-in this [:input-transaction :transaction/currency :currency/code])
;                   :default-value currency})
;            (map-all all-currencies
;                     (fn [{:keys [currency/code]}]
;                       [:option
;                        (opts {:value (name code)
;                               :key   [code]})
;                        (name code)]))]]
;
;          [:label.form
;           "Title:"]
;
;          [:input
;           {:on-change (utils/on-change-in this [:input-transaction :transaction/title])
;            :type      "text"
;            :value     (or title "")}]
;
;          [:label
;           "Date:"]
;
;          ; Input date with datepicker
;
;          (->Datepicker
;            (opts {:key       [::date-picker]
;                   :value     date
;                   :on-change #(.select-date this %)}))
;
;          [:label
;           "Tags:"]
;
;          (utils/tag-input
;            {:input-tag     input-tag
;             :selected-tags tags
;             :on-change     #(om/update-state! this assoc :input-tag %)
;             :on-add-tag    #(add-tag this %)
;             :on-delete-tag #(delete-tag-fn this %)
;             :placeholder   "Select tags..."})]
;
;         [:div
;          (opts {:style {:float :right}})
;          [:a
;           (opts {:class    "button secondary"
;                  :on-click on-close})
;           "Cancel"]
;          [:button.btn.btn-md
;           (opts {:class    "button"
;                  :on-click #(.add-transaction this)})
;           "Save"]]]))))

(def ->AddTransaction (om/factory AddTransaction))