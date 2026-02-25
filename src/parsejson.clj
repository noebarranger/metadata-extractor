(ns parsejson
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.core.match :refer [match]]))

(defn load-ocr-result
  [file-name]
  (let [raw-data (with-open [r (io/reader file-name)]
                   (json/parse-stream r true))
        page (first (:pages raw-data))
        [height width] (:dimensions page)]
    {:width  width
     :height height
     :words  (doall
              (for [block (:blocks page)
                    line  (:lines block)
                    word  (:words line)]
                {:text (:value word)
                 :conf (:confidence word)
                 :bbox (:geometry word)}))}))

(defn load-ocr-result-from-stdin
  []
  (let [raw-data (json/parse-stream (clojure.java.io/reader *in*) true)
        page (first (:pages raw-data))
        [height width] (:dimensions page)]
    {:width  width
     :height height
     :words  (doall
              (for [block (:blocks page)
                    line  (:lines block)
                    word  (:words line)]
                {:text (:value word)
                 :conf (:confidence word)
                 :bbox (:geometry word)}))}))


(defn Parsing [data]
  (filter (fn [x] (< 0.5 (:conf x)))
          (:words data)))

(defn Grouping [words]
  (let [epsilon 0.05
        get-center-y (fn [w]
                       (let [y1 (get-in w [:bbox 0 1])
                             y2 (get-in w [:bbox 1 1])]
                         (/ (+ y1 y2) 2.0)))]
    (->> words
         (sort-by get-center-y)
         (partition-by (fn [w] (Math/round (/ (get-center-y w) epsilon))))
         (map (fn [line] (sort-by #(get-in % [:bbox 0 0]) line))))))

(defn Matching [json-sorted]
  (map (fn [line] (map :text line)) json-sorted))

{:merchant    "SUPER U"
 :date        "2024-03-15T14:30:00"
 :currency    "EUR"
 :total_ttc   60.00
 :total_ht    50.00
 :taxes       [{:rate 20.0 :amount 10.0}]
 :payment_method "CARTE"
 :items       [{:desc "TICKET CP" :qty 2 :price 60.00}]}

;;(load-ocr-result "resultat_ocr.json")
(defn rawjson-to-textold []
  (let [raw-json (load-ocr-result "resultat_ocr3.json")]
    (->> raw-json
         Parsing
         Grouping
         Matching)))


(defn rawjson-to-text []
  (let [raw-json (load-ocr-result-from-stdin)] ; Appel de la nouvelle fonction
    (->> raw-json
         Parsing
         Grouping
         Matching)))
(clojure.pprint/pprint (rawjson-to-text))

(defn- classify [s]
  (cond
    ;; Utilisation de \d pour les chiffres, supporte virgule ou point
    (re-matches #"\d+([,.]\d+)?" s) :num
    
    ;; \p{L} pour n'importe quelle lettre Unicode (accents inclus)
    (re-matches #"\p{L}+" s)       :word
    
    ;; Tout le reste est un séparateur (ponctuation, symboles)
    :else                          :sep))

(defn Lexing [lines-of-words]
  (let [token-pattern #"\d+(?:[,.]\d+)?|\p{L}+|[^\s]"]
    (for [line lines-of-words]   ; Pour chaque ligne
      (for [word line            ; Pour chaque mot
            piece (re-seq token-pattern (str word))] ; Pour chaque morceau trouvé
        {:t (classify piece)
         :v piece}))))            ; Fin map externe et let

(defn normalise-t-v [t v]
  (let [norm-v (match [t]
                 [:word] (-> v clojure.string/trim clojure.string/lower-case)
                 [:sep]  (clojure.string/trim v)
                 [:num]  (clojure.string/trim v)
                 :else   v)]
    {:t t :v norm-v}))

(defn normalisation [lines-of-values-words]
  (map (fn [line]
         (map (fn [{t :t, v :v}]
                (normalise-t-v t v))
              line))
       lines-of-values-words))

(defn has-word? [tokens target]
  (some (fn [token] (clojure.string/includes? (:v token) target)) tokens))


(defn detect-doc-type [tokens]
  (cond
    (has-word? tokens "facture") :invoice
    (has-word? tokens "devis")   :quote
    (has-word? tokens "ticket")  :receipt
    (has-word? tokens "ticket")  :receipt
    
    :else :unknown))

(defn match-Ticket [lines]
  (->> lines
       (map (fn [line]
              (let [v-line (vec line)]
                (match [v-line]
                  ;; Pattern 1 : ["subtotal" 60.000] (2 éléments)
                  [[{:v "subtotal"} {:t :num :v m}]]
                  ["SUBTOTAL" m]

                  ;; Pattern 2 : ["total" "tax" 5.455] (3 éléments avec n'importe quoi au milieu)
                  [[{:v "total"} _ {:t :num :v m}]]
                  ["TOTAL" m]

                  ;; Pattern 3 : ["total" "(" "qty" "2.00" "60.000"] (n-éléments)
                  ;; On utilise '& rest' pour capturer les lignes plus longues qui commencent par "total"
                  [[{:v "total"} & rest]]
                  (let [m (some #(when (= (:t %) :num) (:v %)) (reverse rest))]
                    ["TOTAL" m])

                  :else nil))))
       (remove nil?)))

(defn chunking [lines-of-values-words]
  (let [all-tokens (flatten lines-of-values-words)
        doctype    (detect-doc-type all-tokens)]
    ;; Le match doit être la dernière expression de la fonction
    (match [doctype]
      [:invoice] (do (println "Logique Facture") :todo-invoice)
      [:quote]   (do (println "Logique Devis")   :todo-quote)
      [:receipt] (match-Ticket lines-of-values-words)

      :else      (do (println "Logique générique") lines-of-values-words))))

(defn parse-float [s]
  (try
    (-> s
        (clojure.string/replace "," ".") ;; On harmonise la virgule en point
        (clojure.string/replace #"[^\d.]" "") ;; On vire tout ce qui n'est pas chiffre ou point
        Double/parseDouble)
    (catch Exception _ 0.0))) ;; En cas d'erreur OCR illisible, on met 0.0

(defn format-output [matches]
  (let [matches-map (into {} matches)] ;; Transforme [["TOTAL" "60.000"]] en {"TOTAL" "60.000"}
    {
     :total_ttc   (parse-float (get matches-map "TOTAL"))
     :subtotal    (parse-float (get matches-map "SUBTOTAL"))
     :currency    "EUR" }))

(defn text-matching-to-json []
  (let [raw-text (rawjson-to-text)
        ;; 1. Analyse
        extracted-data (->> raw-text
                            Lexing
                            ;;normalisation
                            ;;chunking
                            )
        ;; 2. Structuration
        final-map (format-output extracted-data)]

    ;; 3. Conversion JSON
    (json/generate-string final-map {:pretty true})))

(defn text-matching []
  (let [raw-text (rawjson-to-text)]
    (->>
     raw-text
     Lexing
     normalisation
     chunking
     )))

(clojure.pprint/pprint (text-matching))