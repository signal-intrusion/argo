(ns argo.core
  (:require
    [clojure.core.match :refer [match]]
    [clojure.string :as str]
    [compojure.core :refer [ANY defroutes context routes]]
    [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
    [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.nested-params :refer [wrap-nested-params]]
    [ring.middleware.params :refer [wrap-params]]
    [taoensso.timbre :as timbre])
  (:import java.util.UUID))

(defn ok
  [data & [{:keys [status headers]}]]
  {:status (or status 200)
   :headers (merge {"Content-Type" "application/vnd.api+json"} headers)
   :body {:data data}})


(defn bad-req
  [errors & {:keys [status]}]
  {:status (or status 400)
   :headers {"Content-Type" "application/vnd.api+json"}
   :body {:errors errors}})

(defn x-to-api
  [type x id-key & [rels]]
  (when x
    (merge {:type type
            :id (str (get x id-key))
            :attributes (dissoc (apply dissoc x (vals rels)) id-key)
            :links {:self (str "/" type "/" (get x id-key))}}
           (when rels {:relationships (apply merge (map (fn [[k v]]
                                                          {k {:related (str "/" type "/" (get x id-key) "/" (name k))}})
                                                        rels))}))))

(defn wrap-pagination
  [default-limit max-limit]
  (fn [handler]
    (fn [req]
      (if (= :get (:request-method req))
        (let [page (-> req :params :page)]
          (if (nil? page)
            (handler (assoc req :page {:offset 0 :limit default-limit}))
            (let [offset (try (Integer/parseInt (:offset page "0")) (catch Throwable t t))
                  limit (try (Integer/parseInt (:limit page (str default-limit))) (catch Throwable t t))]
              (cond
                (instance? Throwable offset) (bad-req {})
                (instance? Throwable limit) (bad-req {})
                :else (handler (assoc req :page {:offset offset :limit limit}))))))
        (handler req)))))

(defn wrap-error [handler]
  (fn [req]
    (try (handler req)
         (catch Throwable t
           (let [id (str (UUID/randomUUID))]
             (timbre/error t id)
             {:status 500
              :headers {"Content-Type" "application/vnd.api+json"}
              :body {:errors [{:status "500" :id id :title "Internal server error."}]}})))))

(defmacro defapi
  [label api]
  (let [resources (:resources api)
        middleware (:middleware api)]
    `(def ~label
       (-> (routes ~@resources)
           ~@middleware
           (wrap-json-body {:keywords? true :bigdecimals? true})
           wrap-nested-params
           wrap-keyword-params
           wrap-params
           (wrap-defaults api-defaults)
           wrap-error
           wrap-json-response))))


(defn rel-req
  [func req]
  (let [{errors :errors
         status :status} (func req)]
    (if errors
      (bad-req errors :status status)
      {:status 204 :headers {"Content-Type" "application/vnd.api+json"}})))

(defmacro defresource
  [label resource]

  (let [typ (str label)
        path (str "/" label)
        req (gensym)
        id-key (:id-key resource :id)
        rels (:rels resource)
        get-many (:find resource)
        get-one (:get resource)
        create (:create resource)
        update (:update resource)
        delete (:delete resource)
        allowed-many (str/join ", " (concat ["OPTIONS"]
                                            (when get-many ["GET"])
                                            (when create ["POST"])))
        allowed-one (str/join ", " (concat ["OPTIONS"]
                                           (when get-one ["GET"])
                                           (when update ["PATCH"])
                                           (when delete ["DELETE"])))]

    `(defroutes ~label
       (context ~path []
          (ANY "/" []
            (fn [~req]
              (match (:request-method ~req)
                ~@(when get-many
                    `(:get (let [{data# :data
                                  errors# :errors
                                  status# :status
                                  total# :count} (~get-many ~req)]
                             (if errors#
                               (bad-req errors# :status status#)
                               (ok (map (fn [x#] (x-to-api ~typ x# ~id-key ~rels)) data#))))))

                ~@(when create
                    `(:post (let [{data# :data
                                   errors# :errors
                                   status# :status} (~create ~req)]
                              (if errors#
                                (bad-req errors# :status status#)
                                (ok (x-to-api ~typ data# ~id-key ~rels) :status 201))
                              )))

                :options {:headers {"Allowed" ~allowed-many}}
                :else {:status 405 :headers {"Allowed" ~allowed-many}})))

          (ANY "/:id" []
            (fn [~req]
              (match (:request-method ~req)
                ~@(when get-one
                    `(:get (let [{data# :data
                                  status# :status
                                  errors# :errors} (~get-one ~req)]
                             (if errors#
                               (bad-req errors# :status status#)
                               (ok (x-to-api ~typ data# ~id-key ~rels))))))

                ~@(when update
                    `(:patch (let [{data# :data
                                    status# :status
                                    errors# :errors} (~update ~req)]
                               (cond
                                 errors# (bad-req errors# :status status#)
                                 data# (ok (x-to-api ~typ data# ~id-key ~rels))
                                 :else {:status 204}))))

                ~@(when delete
                    `(:delete (let [{errors# :errors status# :status} (~delete ~req)]
                                (if errors#
                                  (bad-req errors# :status status#)
                                  {:status 204}))))

                :options {:headers {"Allow" ~allowed-one}}
                :else {:status 405 :headers {"Allow" ~allowed-one}})))

          ~@(when rels
              (map (fn [[rel handler]]
                     (let [{getf :get create :create update :update delete :delete} handler
                           allowed (str/join ", " (concat (when getf ["GET"])
                                                          (when create ["POST"])
                                                          (when update ["PATCH"])
                                                          (when delete ["DELETE"])
                                                          ["OPTIONS"]))
                           many? (sequential? (:type handler))
                           typ (name (if many? (-> handler :type first) (:type handler)))
                           path (str "/:id/" (name rel))]
                       `(ANY ~path []
                             (fn [~req]
                               (match (:request-method ~req)
                                      ~@(when getf
                                          (let [data (gensym)
                                                relations (gensym)]
                                            `(:get (let [{~data :data
                                                          errors# :errors
                                                          status# :status
                                                          total# :count
                                                          ~relations :rels} (~getf ~req)]
                                                     (if errors#
                                                       (bad-req errors# :status status#)
                                                       ~@(if many?
                                                           `((ok (map #(x-to-api ~typ % ~id-key ~relations) ~data)))
                                                           `((ok (x-to-api ~typ ~data ~id-key ~relations)))))))))
                                      ~@(when create
                                          `(:post (rel-req ~create ~req)))

                                      ~@(when update
                                          `(:patch (rel-req ~update ~req)))

                                      ~@(when delete
                                          `(:delete (rel-req ~delete ~req)))

                                      :options {:headers {"Allowed" ~allowed}}

                                      :else {:status 405 :headers {"Allowed" ~allowed}}))))) rels))))))