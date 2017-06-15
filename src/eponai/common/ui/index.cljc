(ns eponai.common.ui.index
  (:require
    [eponai.common.ui.common :as common]
    [eponai.common.ui.navbar :as nav]
    [eponai.common.ui.search-bar :as search-bar]
    [eponai.web.ui.photo :as photo]
    [eponai.common.ui.product-item :as pi]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    [taoensso.timbre :refer [debug error]]
    [eponai.common.ui.dom :as my-dom :refer [div a]]
    [eponai.common.ui.elements.css :as css]
    [eponai.client.routes :as routes]
    [eponai.common.ui.icons :as icons]
    [eponai.common.ui.elements.grid :as grid]
    [eponai.common.ui.router :as router]
    [eponai.web.ui.button :as button]
    [eponai.common.mixpanel :as mixpanel]))

(defn banner [{:keys [color align] :as opts} primary secondary]
  (let [align (or align :left)
        color (or color :default)]
    (dom/div #js {:className (str "banner " (name color))}
      (grid/row
        nil
        (grid/column
          (cond->> (->> (grid/column-size {:small 9 :medium 8})
                        (css/text-align align))
                   (= align :right)
                   (grid/column-offset {:small 3 :medium 4}))
          primary)
        (grid/column
          (css/align :right)
          secondary)))))

(defn collection-element [{:keys [href url title full? url-small photo-id]}]
  ;; Use the whole thing as hover elem
  (my-dom/a
    {:href    href
     :classes [:full :category-photo]}
    (photo/photo {:photo-id photo-id}
                 (photo/overlay
                   nil (my-dom/div
                         (->> (css/text-align :center))
                         (dom/span nil title))))))

(defui Index
  static om/IQuery
  (query [_]
    [{:proxy/navbar (om/get-query nav/Navbar)}
     :query/locations
     {:query/featured-items [:db/id
                             :store.item/name
                             :store.item/price
                             :store.item/created-at
                             {:store.item/photos [{:store.item.photo/photo [:photo/path :photo/id]}
                                                  :store.item.photo/index]}
                             {:store/_items [{:store/profile [:store.profile/name]}]}]}
     {:query/featured-stores [:db/id
                              {:store/profile [:store.profile/name
                                               {:store.profile/photo [:photo/path :photo/id]}]}
                              :store/created-at
                              :store/featured
                              :store/featured-img-src
                              {:store/items [:db/id {:store.item/photos [{:store.item.photo/photo [:photo/path :photo/id]}
                                                                         :store.item.photo/index]}]}]}
     {:query/featured-streams [:db/id :stream/title {:stream/store [:db/id {:store/profile [:store.profile/name {:store.profile/photo [:photo/path :photo/id]}]}]}]}
     {:query/auth [:db/id :user/email]}
     {:query/owned-store [:db/id
                          {:store/profile [:store.profile/name {:store.profile/photo [:photo/path]}]}
                          ;; to be able to query the store on the client side.
                          {:store/owners [{:store.owner/user [:db/id]}]}]}])
  Object
  (render [this]
    (let [{:keys       [proxy/navbar query/featured-items query/featured-streams]
           :query/keys [owned-store locations featured-stores]} (om/props this)]
      (debug "Items: " featured-items)
      (debug "Items: " featured-stores)

      (dom/div #js {:id "sulo-index" :className "sulo-page"}
        (common/page-container
          {:navbar navbar}
          (dom/div #js {:id "sulo-index-container" :onScroll #(debug "Did scroll page: " %)}

            (common/city-banner this locations)
            ;(my-dom/div
            ;  (css/add-class :intro-header)
            ;  (grid/row
            ;    (css/align :middle)
            ;    (grid/column
            ;      (grid/column-size {:small 12 :medium 6})
            ;      (my-dom/h1
            ;        (css/add-class :header)
            ;        (dom/i #js {:className "fa fa-map-marker"})
            ;        (dom/span nil locations)))
            ;    (grid/column
            ;      nil
            ;      (my-dom/div
            ;        (css/add-class :input-container)
            ;        (search-bar/->SearchBar {:ref             (str ::search-bar-ref)
            ;                                 :placeholder     "What are you looking for?"
            ;                                 :mixpanel-source "index"
            ;                                 :classes         [:drop-shadow]})
            ;        (button/button
            ;          (->> (button/expanded {:onClick (fn []
            ;                                            (let [search-bar (om/react-ref this (str ::search-bar-ref))]
            ;                                              (when (nil? search-bar)
            ;                                                (error "NO SEARCH BAR :( " this))
            ;                                              (search-bar/trigger-search! search-bar)))})
            ;               (css/add-classes [:drop-shadow]))
            ;          (dom/span nil "Search"))))))

            (dom/div
              (css/add-class :sections)

              (common/content-section {:href  (routes/url :live)
                                       :class "online-channels"}
                                      "Stores streaming right now"
                                      (grid/row
                                        (->>
                                          (grid/columns-in-row {:small 2 :medium 4}))
                                        ;(grid/column
                                        ;  (css/add-class :online-streams))
                                        (map (fn [c]
                                               (grid/column
                                                 (css/add-class :online-stream)
                                                 (common/online-channel-element c)))
                                             (if (<= 8 (count featured-streams))
                                               (take 8 featured-streams)
                                               (take 4 featured-streams))))
                                      "More streams")

              (common/content-section {:class "collections"}
                                      "Shop by collection"
                                      (div nil
                                           (grid/row
                                             (grid/columns-in-row {:small 1 :medium 2})
                                             (grid/column
                                               (->> (css/add-class :content-item)
                                                    (css/add-class :collection-item))
                                               (collection-element {:href     (routes/url :browse/category {:top-category "home"})
                                                                    :photo-id "static/home"
                                                                    :title    "Home"}))
                                             (grid/column
                                               (->> (css/add-class :content-item)
                                                    (css/add-class :collection-item))
                                               (collection-element {:href     (routes/url :browse/gender {:sub-category "women"})
                                                                    :photo-id "static/women"
                                                                    :title    "Women"}))
                                             (grid/column
                                               (->> (css/add-class :content-item)
                                                    (css/add-class :collection-item))
                                               (collection-element {:href     (routes/url :browse/gender {:sub-category "men"})
                                                                    :photo-id "static/men"
                                                                    :title    "Men"}))
                                             (grid/column
                                               (->> (css/add-class :content-item)
                                                    (css/add-class :collection-item))
                                               (collection-element {:href     (routes/url :browse/gender {:sub-category "unisex-kids"})
                                                                    :photo-id "static/kids"
                                                                    :title    "Kids"}))))
                                      ;(map (fn [s t]
                                      ;       (collection-element {:url (first (:store/featured-img-src s))
                                      ;                            :title t}))
                                      ;     featured-stores
                                      ;     ["Home" "Kids" "Women" "Men"])
                                      ""
                                      )

              (common/content-section
                {:href  (routes/url :live)
                 :class "new-brands"}
                "New stores"
                ;(grid/row-column
                ;  (css/text-align :center))
                ;(dom/div
                ;  (css/add-class :section-title)
                ;  (dom/h2 nil "New brands"))
                (my-dom/div
                  {:classes ["sulo-items-container"]}
                  (grid/row
                    (grid/columns-in-row {:small 2 :medium 4})
                    (map (fn [store]
                           (let [store-name (get-in store [:store/profile :store.profile/name])]
                             (grid/column
                               nil
                               (my-dom/div
                                 (->> (css/add-class :content-item)
                                      (css/add-class :stream-item))
                                 (my-dom/a
                                   {:href (routes/url :store {:store-id (:db/id store)})}
                                   (photo/store-photo store {:transformation :transformation/thumbnail-large}))
                                 (my-dom/div
                                   (->> (css/add-class :text)
                                        (css/add-class :header))
                                   (my-dom/a {:href (routes/url :store {:store-id (:db/id store)})}
                                             (my-dom/strong nil store-name)))))))
                         featured-stores)))
                "See more stores")

              (common/content-section {:href  (routes/url :browse/all-items)
                                       :class "new-arrivals"}
                                      "New products"
                                      (grid/row
                                        (->> (css/add-class :collapse)
                                             (grid/columns-in-row {:small 2 :medium 4 :large 5}))
                                        (map
                                          (fn [p]
                                            (grid/column
                                              (css/add-class :new-arrival-item)
                                              (pi/product-element {:open-url? true} p)))
                                          (take 5 featured-items)))
                                      "See more products")



              (common/sell-on-sulo this))))))))


(def ->Index (om/factory Index))

(router/register-component :index Index)