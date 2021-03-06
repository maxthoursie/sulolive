(ns eponai.common.ui.user.order-list
  (:require
    [eponai.common.ui.dom :as dom]
    [om.next :as om :refer [defui]]
    [eponai.common.ui.elements.css :as css]
    [taoensso.timbre :refer [debug]]
    [eponai.client.routes :as routes]
    [eponai.common.ui.common :as common]
    [eponai.common.ui.elements.table :as table]
    [eponai.common.ui.utils :as ui-utils]
    [eponai.common.ui.elements.callout :as callout]
    [eponai.common.ui.elements.grid :as grid]
    [eponai.common.format.date :as date]
    [eponai.web.ui.photo :as photo]
    [eponai.common.ui.elements.menu :as menu]
    [clojure.string :as string]
    [eponai.web.ui.button :as button]
    #?(:cljs [eponai.web.utils :as web.utils])
    [eponai.web.ui.content-item :as ci]))

(defui OrderList

  static om/IQuery
  (query [_]
    [:query/current-route
     {:query/orders [:db/id
                     :order/uuid
                     :order/status
                     :order/amount
                     {:order/items [:order.item/type
                                    :order.item/amount
                                    :order.item/description
                                    :order.item/title
                                    {:order.item/parent [:store.item.sku/variation
                                                         {:store.item/_skus [:store.item/name
                                                                             :store.item/price
                                                                             {:store.item/photos [{:store.item.photo/photo [:photo/id]}
                                                                                                  :store.item.photo/index]}]}]}]}
                     {:order/shipping [:shipping/name
                                       {:shipping/address [:shipping.address/street
                                                           :shipping.address/postal
                                                           :shipping.address/locality
                                                           :shipping.address/region
                                                           :shipping.address/country]}
                                       ]}
                     :order/created-at
                     :order/user
                     {:order/store [{:store/profile [{:store.profile/photo [:photo/id]}
                                                     {:store.profile/cover [:photo/id]}
                                                     :store.profile/name]}]}]}
     {:query/featured-items (om/get-query ci/ProductItem)}])

  Object
  (render [this]
    (let [{:query/keys [orders current-route featured-items]} (om/props this)
          orders-by-month (group-by #(date/month->long (:order/created-at % 0)) orders)]
      (debug "Got orders: " orders)
      (dom/div
        {:id "sulo-user-order-list"}

        (grid/row-column
          nil
          (dom/h1 nil "Purchases"))
        (if (not-empty orders)
          (grid/row-column
            nil
            (map (fn [[timestamp os]]
                   (dom/div
                     nil
                     (dom/h2 nil (date/date->string timestamp "MMMM YYYY"))
                     (map
                       (fn [o]
                         (let [{:order/keys [store]} o
                               {store-name :store.profile/name} (:store/profile store)
                               skus (filter #(= (:order.item/type %) :order.item.type/sku) (:order/items o))
                               shipping (some #(when (= (:order.item/type %) :order.item.type/shipping) %) (:order/items o))]
                           (callout/callout
                             (css/add-class :sl-order-card)
                             (dom/div
                               (css/add-classes [:section-title :sl-order-card-title])

                               (dom/a
                                 (css/add-class :sl-order-card-title--store {:href (routes/url :store {:store-id (:db/id store)})})
                                 (photo/store-photo store {:transformation :transformation/thumbnail})
                                 (dom/p nil
                                        (dom/span nil (str store-name))))

                               (dom/div
                                 (css/text-align :right)
                                 (dom/p nil (dom/small nil (date/date->string (:order/created-at o) "MMMM dd, YYYY")))
                                 (button/store-setting-default {:href (routes/url :user/order {:order-id (:db/id o)})} "View receipt")))

                             (dom/div
                               (css/add-classes [:section-content :sl-order-card-content])
                               (grid/row
                                 (->> (grid/columns-in-row {:small 3 :medium 5})
                                      (css/add-classes [:sl-order-items-list]))
                                 (map (fn [oi]
                                        (let [sku (:order.item/parent oi)
                                              product (:store.item/_skus sku)]

                                          (grid/column
                                            nil
                                            (dom/div
                                              (css/add-class :sl-order-items-list-item--info)
                                              (photo/product-preview product {:transformation :transformation/thumbnail})
                                              (dom/p nil (dom/small nil (:order.item/title oi))
                                                     (dom/br nil)
                                                     (dom/small nil (:order.item/description oi)))
                                              ))
                                          ))
                                      (take 4 skus)))

                               (dom/div
                                 (css/add-class :sl-order-card-subtitle)
                                 (let [status (:order/status o)]
                                   (dom/div
                                     (css/add-class :order-status)
                                     (dom/p nil (dom/span nil "Status: ")
                                            (cond (#{:order.status/created :order.status/paid} status)
                                                  (dom/span (css/add-class :text-success) "New")
                                                  (#{:order.status/fulfilled} status)
                                                  (dom/span nil "Shipped")
                                                  :else
                                                  (dom/span nil (name status))))
                                     ))
                                 (dom/p (css/text-align :right)
                                        (dom/strong nil (str "Total: " (ui-utils/two-decimal-price (:order/amount o))))))
                               ))))
                       (sort-by :order/created-at > os))))
                 orders-by-month))
          (grid/row-column
            (css/add-class :empty-container)
            (dom/p (css/add-class :shoutout) "You haven't made any purchases yet")
            (button/button
              (button/sulo-dark (button/hollow {:href (routes/url :browse/all-items)})) (dom/span nil "Browse products"))))


        (callout/callout
          (css/add-classes [:section :content :featured])
          (grid/row-column
            nil
            (dom/div
              (css/add-class :section-title)
              (dom/h3 nil "More  products")))
          (grid/row
            (->>
              (grid/columns-in-row {:small 3 :medium 4 :large 7}))
            (map
              (fn [p]
                (grid/column
                  (css/add-class :new-arrival-item)
                  (ci/->ProductItem (om/computed p
                                                 {:current-route current-route}))))
              (take 7 featured-items))))
        ))))

(def ->OrderList (om/factory OrderList))