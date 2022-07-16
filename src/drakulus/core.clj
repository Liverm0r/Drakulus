(ns drakulus.core
  "Drakulus is a library offering algorithms on weighted digraphs.

  Graph is represented as maps (almost like Adjacency list):

  {:1 {:2 5 :3 6}
   :2 {:3 4}
   :3 {:1 5}
   :4 {}}

  In the example above vertex :1 has two adjacent vertices — :2 and :3
  with weights on edges equal to 5 and 6 respectively."
  {:author "Artur Dumchev"}
  (:require
    [dorothy.core :as d]
    [dorothy.jvm :refer [show!]]
    [clojure.core.memoize :as memo]
    [clojure.data.priority-map :refer [priority-map-by]]))

;; # Random digraph, spanning tree generation

(def ^:private n->key (comp keyword str))

(defn- empty-graph [v-count]
  (into {} (for [i (range v-count)] [(n->key i) {}])))

(defn- make-spanning-tree [v-count max-weight]
  (loop [curr-v (n->key (rand-int v-count))
         visited #{curr-v}
         g (empty-graph v-count)]
    (if (= (count visited) v-count)
      g
      (let [adj-v (n->key (rand-int v-count))]
        (if (visited adj-v)
          (recur adj-v visited g)
          (recur adj-v
                 (conj visited adj-v)
                 (assoc-in g [curr-v adj-v] (rand-int max-weight))))))))

(defn- lazy-shuffle
  "Get vector, return lazy shuffled sequence; O(n)"
  [v]
  (lazy-seq
    (when (seq v)
      (let [idx (rand-int (count v))]
        (cons (nth v idx)
              (lazy-shuffle (pop (assoc v idx (peek v)))))))))

(defn- all-edges-comb-seq [v-count]
  (for [i (range v-count)
        j (range v-count)
        :when (not= i j)]
    [(n->key i) (n->key j)]))

(defn- random-edges-seq [v-count]
  (lazy-shuffle
    (vec (all-edges-comb-seq v-count))))

(defn- add-rand-weighted-edges [g max-value edges]
  (reduce (fn [g vs] (assoc-in g vs (rand-int max-value))) g edges))

(defn make-graph
  "Args:
  `v` — number of verticies
  `e` — number of directed edges (in range [v-1..v*(v-1)])

  Returns a random graph"
  [v e & {:keys [max-value] :or {max-value 100}}]
  (assert (<= (dec v) e (* v (dec v)))
          "count of edges must be in range [v-1..v*(v-1)]")
  (if (= (* (dec v) v) e)
    (add-rand-weighted-edges {} max-value (all-edges-comb-seq v))
    (let [g (make-spanning-tree v max-value)
          e (- e (dec v))] ;; v - 1 edges in the spanning tree
      (->> (random-edges-seq v)
           (filter (fn [[v1 v2]] (not (get-in g [v1 v2]))))
           (take e)
           (add-rand-weighted-edges g max-value)))))

(def ^:private compare-by-first #(compare (first %1) (first %2)))

;; # Distance/Path

(defn dijkstra
  "Args:
  `g` — graph,
  `v-start` — start vertex;
  `v-dest` — optional; returns result immediatelly when finds `v-dest`.

  Returns map of: vertex —> [distance path], where
  `distance` — sum of edges in between;
  `path` — verticies between v-start and other vertex, including both.

  Usage:
  (dijkstra {:1 {:2 10} :2 {}} :1 :2) ;=> {:1 [0 [:1]], :2 [10 [:1 :2]]}"
  [g v-start & [v-dest]]
  ;; both queue and result have map entries: vertex -> [distance path]
  (loop [queue (priority-map-by compare-by-first v-start [0 [v-start]])
         result {}]
    (if (contains? result v-dest)
        result
        (if-let [[curr-v [dist path]] (peek queue)]
          (recur
            (into (pop queue)                          ; removing curr-v
                  (for [[adj-v w] (get g curr-v)       ; check neighbours
                        :when (not (get result adj-v)) ; ignore visited
                        :let [[dist-old :as old] (get queue adj-v)]]
                    (if (and dist-old (< dist-old (+ w dist)))
                      [adj-v old]
                      [adj-v [(+ w dist) (conj path adj-v)]])))
            (assoc result curr-v (get queue curr-v)))
          result))))

(defn shortest-path [g v-start v-dest]
  (if-let [result (get (dijkstra g v-start v-dest) v-dest)]
    (second result)
    []))

(def ecc-count-edges-weight-fn (comp dec count second))
(def ecc-distance—weight-fn first)

(defn eccentricity
  "Args:
  `g` — graph,
  `v` — vertex and weight-fn
  `weight-fn` takes `[total-distance, [:as path]]`, returns number or ##Inf

  Returns max distance from `v` to any vertex, calculated by the `weight-fn`.
  ##Inf means there is no path from `v` to some vertex.

  Usage:
  (eccentricity {:1 {:2 1}, :2 {}} :1) ;=> 1
  (eccentricity {:1 {:2 7}, :2 {}} :1 ecc-distance—weight-fn) ;=> 7
  (eccentricity {:1 {:2 1}, :2 {}} :1 ecc-count-edges-weight-fn) ;=> 1"
  ([g v] (eccentricity g v ecc-count-edges-weight-fn))
  ([g v weight-fn]
   (let [distances (dissoc (dijkstra g v) v)]
     (if (empty? distances)
       ##Inf
       (apply max (map #(-> % second weight-fn) distances))))))

(def eccentricity-memo (memo/lru eccentricity :lru/threshold 512))

(defn eccentricities-by [g weight-fn select-fn]
  (apply select-fn (for [[v _] g] (eccentricity-memo g v weight-fn))))

(defn radius
  "Args: graph, weight-fn (check `eccentricity` function docs)
  Retruns min of all eccentricities"
  ([g] (radius g ecc-count-edges-weight-fn))
  ([g weight-fn] (eccentricities-by g weight-fn min)))

(defn diameter
  "Args: graph, weight-fn (check `eccentricity` function docs)
  Retruns max of all eccentricities"
  ([g] (diameter g ecc-count-edges-weight-fn))
  ([g weight-fn] (eccentricities-by g weight-fn max)))

;; # Visualization

(defn dorothy-digraph [g]
  (d/digraph (for [[v es] g
                   [v2 w] es]
               [v v2 {:weight w}])))

(defn show-graph!
  "graphviz needs to be installed on the system path"
  [g]
  (-> (dorothy-digraph g)
      d/dot
      (show! {:format :svg})))

(comment

  (make-spanning-tree 5 5)

  (def G (make-graph 8 9))

  (dijkstra G :1)

  (eccentricities-by G ecc-count-edges-weight-fn vector)

  (radius G)
  (diameter G)
  (eccentricity G :3 ecc-distance—weight-fn)

  (show-graph! G)
  (show-graph! (make-graph 9 11))

  (time
    (shortest-path G :1 :999))
  ,)
