(ns eponai.fullstack.tests
  (:require [om.next :as om]
            [om.util]
            [eponai.common.database.pull :as pull]
            [eponai.common.parser :as parser]
            [eponai.common.format.date :as date]
            [eponai.common.format :as format]
            [eponai.common.database.transact :as transact]
            [eponai.client.utils :as utils]
            [taoensso.timbre :refer [info debug error]]
            [clojure.data :as diff]
            [datascript.core :as datascript]
            [eponai.fullstack.framework :as fw]
            [eponai.fullstack.jvmclient :refer [JvmRoot]]
            [clojure.test :as test]
            [eponai.fullstack.utils :as fs.utils]
            [clojure.walk :as walk]
            [aprint.dispatch :as adispatch]
            [datomic.api :as d])
  (:import (org.eclipse.jetty.server Server)
           (datomic.Entity)
           (datascript.impl.entity.Entity)))

(def days-from-now (comp date/date->long date/days-from-now))

(defn- app-state [reconciler]
  (fw/call-parser reconciler (om/get-query (or (om/app-root reconciler) JvmRoot))))

(defn db [client]
  (pull/db* (om/app-state client)))

(defn entity? [x]
  (or
    (instance? datascript.impl.entity.Entity x)
    (instance? datomic.Entity x)))

(defn entity-map
  [client lookup-ref]
  (->> (pull/entity* (db client) lookup-ref)
       (walk/prewalk #(cond->> %
                               (entity? %)
                               (into {:db/id (:db/id %)})))))

(defn equal-app-states? [clients]
  (let [app-states (map app-state clients)
        eq? (apply = app-states)]
    (if eq?
      eq?
      (run! (fn [[a b]]
              (when (not= a b)
                (let [[left right both] (vec (diff/diff a b))]
                  (error "App state NOT eq. diff: ")
                  (error "in left one: " left)
                  (error "in right one: " right)
                  (error "in both: " both))))
            (partition 2 1 app-states)))))

(defn new-transaction [client]
  (let [project-uuid (pull/find-with (pull/db* (om/app-state client))
                                     {:find-pattern '[?uuid .]
                                      :where        '[[_ :project/uuid ?uuid]]})]
    {:transaction/tags       #{{:tag/name "thailand"}}
     :transaction/date       {:date/ymd "2015-10-10"}
     :transaction/type       :transaction.type/expense
     :transaction/currency   {:currency/code "THB"}
     :transaction/title      "lunch"
     :transaction/project    {:project/uuid project-uuid}
     :transaction/uuid       (datascript/squuid)
     :transaction/amount     "180"
     :transaction/created-at 1}))

(defn has-transaction? [tx client]
  (pull/lookup-entity (db client)
                      [:transaction/uuid (:transaction/uuid tx)]))

(def get-transaction has-transaction?)

(defn has-edit?
  ([tx {:keys [edit-fn key-fn compare-fn] :as edit} client]
   {:pre [(map? tx) (map? edit) (some? edit-fn) (om/reconciler? client)]}
   ((or compare-fn =)
     (key-fn (entity-map client [:transaction/uuid (:transaction/uuid tx)]))
     (key-fn (edit-fn tx)))))

(defn ->edit [& args]
  (let [edit (zipmap [:edit-fn :key-fn ::parser/created-at :compare-fn] args)]
    (cond-> edit
            (::parser/created-at edit)
            (update ::parser/created-at + (days-from-now 1)))))

(defn is-running? [^Server server]
  (.isRunning server))

(defn stop-server! [server & {:keys [asserts]}]
  {:pre [(or (nil? asserts) (fn? asserts))]}
  {::fw/transaction #(do (.stop server)
                         (.join server))
   ::fw/asserts     #(do (assert (not (is-running? server)))
                         (when asserts (asserts)))})

(defn start-server! [server & {:keys [await asserts]}]
  {:pre [(vector? await)]}
  {::fw/transaction   #(.start server)
   ::fw/await-clients await
   ::fw/sync-clients! true
   ::fw/asserts       #(do (assert (is-running? server))
                           (when asserts (asserts)))})

(defn create-transaction! [server clients client tx]
  (fn []
    {::fw/transaction (fn [] [client `[(transaction/create ~tx)]])
     ::fw/asserts     (if (is-running? server)
                        ;; Server is running before creation, assert everyone
                        ;; gets the transaction.
                        (fn []
                          (assert (every? (partial has-transaction? tx) clients))
                          (assert (equal-app-states? clients)))
                        ;; Server was not running. Only the client gets the transaction.
                        (fn []
                          (assert (test/is (has-transaction? tx client)))
                          (assert (test/is (not-any? (partial has-transaction? tx)
                                                     (remove #(= client %) clients))))))}))

(defn edit-transaction! [server clients client tx {:keys [edit-fn ::parser/created-at] :as edit}]
  {:pre [(om/reconciler? client)
         (map? tx)
         (and (map? edit) (some? (:edit-fn edit)))]}
  (fn []
    (let [tx (entity-map client [:transaction/uuid (:transaction/uuid tx)])]
      {::fw/transaction [client `[(transaction/edit ~(assoc {:old tx :new (edit-fn tx)}
                                                       ::parser/created-at created-at))]]
       ::fw/assert      (if (is-running? server)
                          #(assert (every? (partial has-edit? tx edit) clients))
                          #(do (assert (has-edit? tx edit client))
                               (assert (not-any? (partial has-edit? tx edit)
                                                 (remove (partial = client) clients)))))})))

(defmethod print-method om.next.Reconciler [x writer]
  (print-method (str "[Reconciler id=[" (get-in x [:config :id-key]) "]]") writer))

(defmethod adispatch/color-dispatch om.next.Reconciler [x]
  (adispatch/color-dispatch [(str "Reconciler" :id-key (get-in x [:config :id-key]))]))

(defn set-amount [& [x]]
  (fn [tx]
    (assoc tx :transaction/amount (str (or x 4711)))))

(def get-amount #(-> % :transaction/amount bigdec))

(defn test-system-setup [server clients]
  {:label   "System setup should always have a running server."
   :actions [{::fw/transaction [(rand-nth clients) []]
              ::fw/asserts     #(do (assert (.isRunning ^Server server))
                                    (assert (equal-app-states? clients)))}]})

(defn test-create-transaction [server [client1 :as clients]]
  (let [tx (new-transaction client1)]
    {:label   "created transactions should sync"
     :actions [(create-transaction! server clients client1 tx)
               {::fw/transaction [client1 `[(transaction/create ~tx)]]
                ::fw/asserts     (fn []
                                   (assert (every? (partial has-transaction? tx) clients))
                                   (assert (equal-app-states? clients)))}]}))

(defn test-create-transaction-offline [server [client1 :as clients]]
  (let [tx (new-transaction client1)]
    {:label   "Creating transaction offline should sync when client/server goes online"
     :actions [(stop-server! server)
               (create-transaction! server clients client1 tx)
               (start-server! server :await [client1]
                              :asserts #(assert (every? (partial has-transaction? tx) clients)) )]}))

(defn test-edit-transaction [server [client1 :as clients]]
  (let [tx (new-transaction client1)]
    {:label   "edit transaction: Last made edit should persist"
     :actions [(create-transaction! server clients client1 tx)
               (edit-transaction! server clients client1 tx {:edit-fn (set-amount) :key-fn get-amount})]}))

(defn test-edit-transaction-offline [server [client1 client2 :as clients]]
  (let [tx (new-transaction client1)
        c1-edit {:key-fn get-amount ::parser/created-at (days-from-now 2) :edit-fn (set-amount 10)}
        c2-edit {:key-fn get-amount ::parser/created-at (days-from-now 3) :edit-fn (set-amount 20)}]
    {:label   "Last edit should persist"
     :actions [(create-transaction! server clients client1 tx)
               (stop-server! server :asserts #(assert (every? (partial has-transaction? tx) clients)))
               (edit-transaction! server clients client1 tx c1-edit)
               (edit-transaction! server clients client2 tx c2-edit)
               (start-server! server
                              :await [client1 client2]
                              :asserts
                              #(do (assert (not-any? (partial has-edit? tx c1-edit) clients))
                                   (assert (every? (partial has-edit? tx c2-edit) clients))))]}))

(defn- create+edit-offline-test [label & edit-params]
  {:pre [(some? label)]}
  (fn [server [client1 :as clients]]
    (let [tx (new-transaction client1)
          edit (zipmap [:edit-fn :key-fn :compare-fn] edit-params)]
      {:label   label
       :actions [(stop-server! server)
                 (create-transaction! server clients client1 tx)
                 (edit-transaction! server clients client1 tx edit)
                 (start-server! server
                                :await [client1]
                                :asserts #(do (assert (every? (partial has-edit? tx edit) clients))))]})))

(def test-create+edit-amount-offline
  (create+edit-offline-test "create+edit amount offline"
                            (set-amount)
                            get-amount))

(def test-create+edit-title-offline
  (create+edit-offline-test "create+edit title offline"
                            #(assoc % :transaction/title "title")
                            :transaction/title
                            #(test/is (= "title" % %2))))

(def test-create+edit-category-offline
  (create+edit-offline-test "create+edit category offline"
                            #(assoc % :transaction/category {:category/name "category"})
                            (comp :category/name :transaction/category)
                            (partial = "category")))

(defn new-project []
  {:project/uuid (d/squuid)
   :project/name "fullstack test project"})

(defn has-project? [project client]
  (pull/lookup-entity (db client) [:project/uuid (:project/uuid project)]))

(defn test-edit-transaction-offline-to-new-offline-project
  [server [client1 :as clients]]
  (let [tx (new-transaction client1)
        proj (new-project)
        uuid (:project/uuid proj)
        edit {:edit-fn    #(assoc % :transaction/project {:project/uuid uuid})
              :key-fn     #(-> % :transaction/project :project/uuid)
              :compare-fn (partial = uuid)}]
    {:label   (str "Can create a transaction and project offline, edit the transaction"
                   " to belong to the new project, then sync.")
     :actions [(stop-server! server)
               (create-transaction! server clients client1 tx)
               {::fw/transaction [client1 `[(project/save ~proj)]]
                ::fw/asserts     #(do (assert (has-project? proj client1))
                                      (assert (not-any? (partial has-project? proj)
                                                        (remove (partial = client1) clients))))}
               (edit-transaction! server clients client1 tx edit)
               (start-server! server
                              :await [client1]
                              :asserts #(do (assert (every? (partial has-transaction? tx) clients))
                                            (assert (every? (partial has-project? proj) clients))
                                            (assert (every? (partial has-edit? tx edit) clients))))]}))

(defn assert=
  ([a b] (assert= nil a b))
  ([expected a b]
   (letfn [(diff-str [label a b]
             (when (not= a b)
               (str "Diff" (when label (str " " label)) ": " (diff/diff a b) " ")))]
     (if (nil? expected)
       (assert (test/is (= a b))
               (diff-str nil a b))
       (assert (test/is (= a b expected))
               (str (diff-str "(a b)" a b)
                    (diff-str "(a expected)" a expected)
                    (diff-str "(b expected)" b expected)))))
   true))

(defn compose-edits [edits]
  (let [edit-maps (sort-by ::parser/created-at edits)]
    {:edit-fn    (fn [x]
                   (let [all-edits (apply juxt (map :edit-fn edit-maps))
                         edit-res (all-edits x)]
                     (reduce utils/deep-merge x edit-res)))
     :key-fn     (or (some->> edit-maps
                              (map :key-fn)
                              (filter some?)
                              (seq)
                              (apply juxt))
                     ::no-key-fn)
     :compare-fn assert=}))

(defn edits->asserts [edits assert-per-client]
  (fn [clients tx]
    (let [composed-edits (compose-edits edits)]
      (when (= ::no-key-fn (:key-fn composed-edits))
        (assert (some? assert-per-client)
                (str "There's no :key-fn in edits and there's no asserts-per-client."
                     " We need to compare something. Either pass a :key-fn or an assert fn."
                     " edits: " edits)))
      (assert (every? (partial has-transaction? tx) clients))
      (assert (every? (partial has-edit? tx composed-edits) clients))
      (when assert-per-client
        (assert-per-client clients tx)))))

(defn create-two-client-edit-test [label actions & [assert-per-client]]
  (fn [server clients]
    (let [edits (map second (filter vector? actions))
          asserts (edits->asserts edits assert-per-client)
          tx (new-transaction (first clients))]
      {:label   label
       :actions (-> [(create-transaction! server clients (first clients) tx)]
                    (into (map (fn [action]
                                 (cond
                                   (= ::sync! action)
                                   (fn []
                                     {::fw/transaction   (constantly nil)
                                      ::fw/sync-clients! true})

                                   (= ::stop-server! action)
                                   (stop-server! server)

                                   (and (map? action) (= ::start-server! (ffirst action)))
                                   (start-server! server
                                                  :await (mapv #(nth clients %) (:await action)))
                                   ;; Edit:
                                   (vector? action)
                                   (fn []
                                     (let [[client-idx edit] action
                                           client (nth clients client-idx)]
                                       (dissoc ((edit-transaction! server clients client tx edit))
                                               ::fw/asserts)))
                                   :else
                                   (throw (ex-info (str "Unknown action: " action)
                                                   {:action  action
                                                    :actions actions})))))
                          actions)
                    (conj {::fw/transaction []
                           ::fw/asserts     #(asserts clients tx)}))})))


(def test-two-client-edit-amount
  (create-two-client-edit-test "amount test"
                               [[0 (->edit (set-amount 10) get-amount 3000)]
                                [1 (->edit (set-amount 20) get-amount 2000)]]
                               (fn [clients tx]
                                 (every? #(assert= (bigdec 10) (get-amount (get-transaction tx %)))
                                         clients))))

(defn add-tag [name]
  #(update % :transaction/tags (fnil conj #{}) {:tag/name name}))

(defn remove-tag [name]
  (fn [tx]
    (update tx :transaction/tags (fn [tags] (->> tags (into #{} (remove #(= name (:tag/name %)))))))))

(defn clients-tags-are-equal [clients tx]
  (apply assert=
         (map (comp set get-tag-names (partial get-transaction tx))
              (take 2 clients))))

(def test-two-client-edit-tags-1
  (create-two-client-edit-test "tag test adds"
                               [[0 (->edit (add-tag "lunch") nil 3000)]
                                [1 (->edit (add-tag "dinner") nil 2000)]]
                               clients-tags-are-equal))

(def test-two-client-edit-tags-2
  (create-two-client-edit-test "tag test add and remove"
                               [[0 (->edit (add-tag "foo") nil 3000)]
                                [1 (->edit (remove-tag "foo") nil 4000)]]
                               clients-tags-are-equal))

(def test-two-client-edit-tags-offline+sync
  (create-two-client-edit-test "tag test"
                               [::stop-server!
                                [0 (->edit (add-tag "lunch") nil 3000)]
                                [1 (->edit (add-tag "lunch") nil 2000)]
                                [1 (->edit (remove-tag "lunch") nil 4000)]
                                {::start-server! {:await [0 1]}}
                                ;; Why do we need to sync here again?
                                ::sync!]
                               clients-tags-are-equal))


(defn run []
  (fs.utils/with-less-loud-logger
    #(do (fw/run-tests (->> [
                             test-system-setup
                             test-create-transaction
                             test-edit-transaction
                             test-create-transaction-offline
                             test-edit-transaction-offline
                             test-create+edit-amount-offline
                             test-create+edit-title-offline
                             test-create+edit-category-offline
                             test-edit-transaction-offline-to-new-offline-project
                             test-two-client-edit-amount
                             test-two-client-edit-tags-1
                             test-two-client-edit-tags-2
                             test-two-client-edit-tags-offline+sync
                             ;; test-edit-tags with retracts and adds on different clients.
                            ]
                            ;; (filter (partial = test-edit-transaction))
                            ; (reverse)
                            ; (take 1)
                           ))
        nil)))
