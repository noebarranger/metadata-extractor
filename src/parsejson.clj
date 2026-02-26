(ns parsejson
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.core.match :refer [match]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]))

;; --- OCR Loading Logic ---

(defn load-ocr-result
  [file-name]
  (if (.exists (io/file file-name))
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
                   :bbox (:geometry word)}))})
    (do
      (println "[CLOJURE] ERREUR : Le fichier" file-name "est absent.")
      nil)))

;; --- Processing Pipeline ---

(defn Parsing [data]
  (filter (fn [x] (< 0.5 (:conf x)))
          (:words data)))
(defn Grouping-Robust [words]
  (let [threshold 0.02
        get-center-y (fn [w]
                       (let [y1 (get-in w [:bbox 0 1])
                             y2 (get-in w [:bbox 1 1])]
                         (/ (+ y1 y2) 2.0)))]
    (->> (sort-by get-center-y words)
         (reduce (fn [lines w]
                   (let [last-line (last lines)
                         last-y (when last-line (get-center-y (first last-line)))]
                     (if (and last-y (< (Math/abs (- (get-center-y w) last-y)) threshold))
                       (conj (vec (butlast lines)) (conj last-line w))
                       (conj lines [w]))))
                 [])
         (map (fn [line] (sort-by #(get-in % [:bbox 0 0]) line))))))

(defn Matching [json-sorted]
  (map (fn [line] (map :text line)) json-sorted))

(defn- classify [s]
  (cond
    (re-matches #"\d+([,.]\d+)?" s) :num
    (re-matches #"\p{L}+" s)       :word
    :else                          :sep))

(defn Lexing [lines-of-words]
  (let [token-pattern #"\d+(?:[,.]\d+)?|\p{L}+|[^\s]"]
    (for [line lines-of-words]
      (for [word line
            piece (re-seq token-pattern (str word))]
        {:t (classify piece)
         :v piece}))))

(defn normalise-t-v [t v]
  (let [norm-v (match [t]
                 [:word] (-> v str/trim str/lower-case)
                 [:sep]  (str/trim v)
                 [:num]  (str/trim v)
                 :else   v)]
    {:t t :v norm-v}))

(defn normalisation [lines-of-values-words]
  (map (fn [line]
         (map (fn [{t :t, v :v}]
                (normalise-t-v t v))
              line))
       lines-of-values-words))

;; --- Logic & Matching ---

(defn has-word? [tokens target]
  (some (fn [token] (str/includes? (:v token) target)) tokens))

(defn detect-doc-type [tokens]
  (cond
    (has-word? tokens "facture") :invoice
    (has-word? tokens "email")   :mail
    (has-word? tokens "ticket")  :receipt
    :else :unknown))
(defn match-mail [lines]
  (->> lines
       (map (fn [line]
              (let [v-line (vec line)]
                (match [v-line]
                  ;; --- Patterns existants ---
                  [[{:v "subtotal"} {:t :num :v m}]] ["SUBTOTAL" m]
                  [[{:v "total"} _ {:t :num :v m}]]   ["TOTAL" m]

                  ;; --- Nouveau Pattern : Email ---
                  ;; On cherche n'importe quel token qui contient un "@"
                  [[& tokens]]
                  (if-let [email (some #(when (clojure.string/includes? (:v %) "@") (:v %)) tokens)]
                    ["EMAIL" email]
                    ;; On continue le matching si ce n'est pas un mail
                    (match [v-line]
                      [[{:v "total"} & rest]]
                      (let [m (some #(when (= (:t %) :num) (:v %)) (reverse rest))]
                        ["TOTAL" m])
                      :else nil))

                  :else nil))))
       (remove nil?)))


(defn match-Ticket [lines]
  (->> lines
       (map (fn [line]
              (let [v-line (vec line)]
                (match [v-line]
                  [[{:v "subtotal"} {:t :num :v m}]] ["SUBTOTAL" m]
                  [[{:v "total"} _ {:t :num :v m}]]   ["TOTAL" m]
                  [[{:v "total"} & rest]]
                  (let [m (some #(when (= (:t %) :num) (:v %)) (reverse rest))]
                    ["TOTAL" m])
                  :else nil))))
       (remove nil?)))

(defn chunking [lines-of-values-words]
  (let [all-tokens (flatten lines-of-values-words)
        doctype    (detect-doc-type all-tokens)]
    (match [doctype]
      [:invoice] (do (println "Logique Facture") :todo-invoice)
      [:quote]   (do (println "Logique Devis")   :todo-quote)
      [:receipt] (match-Ticket lines-of-values-words)
      :else      (match-mail lines-of-values-words))))

;; --- Main Entry Point ---

(defn run-extraction [file-path]
  (if-let [raw-json (load-ocr-result file-path)]
    (do
      (println "[CLOJURE] Données chargées. Traitement en cours...")
      (->> raw-json
           Parsing
           Grouping-Robust
           Matching))
    (println "[CLOJURE] Annulation du traitement.")))



(defn demo-pipeline [file-path]
  (let [raw-data (load-ocr-result file-path)
        ;; Liste des étapes avec leur nom et la fonction associée
        steps [["1. PARSING (Filtre)"     Parsing]
               ["2. GROUPING (Lignes)"   Grouping-Robust]
               ["3. MATCHING (Texte)"    Matching]
               ["4. LEXING (Tokens)"     Lexing]
               ["5. NORMALISATION"       normalisation]
               ["6. CHUNKING (Final)"    chunking]]]

    (reduce (fn [data [label f]]
              (let [next-data (f data)]
                (println (str "\n--- " label " ---"))
                ;; On affiche les 2 premiers éléments (lignes ou mots) pour la clarté
                (clojure.pprint/pprint (take 2 next-data))
                next-data))
            raw-data
            steps)))

(defn -main [& args]
  (demo-pipeline "resultat_ocr3.json"))


(defn -main [& args]
  (let [file-path "resultat_ocr3.json"]
    (println "[CLOJURE] Démarrage du parsing...")
    (if-let [result (run-extraction file-path)]
      (do
        (println "[CLOJURE] Résultat final :")
        (clojure.pprint/pprint result))
      (System/exit 1))))

(.exists (clojure.java.io/file "resultat_ocr3.json"))

;; 2. Teste le chargement brut
(def raw (load-ocr-result "resultat_ocr3.json"))
(count (:words raw)) ; Devrait afficher un nombre > 0

;; 3. Teste l'extraction
(run-extraction "resultat_ocr3.json")