(ns tupelo.datomic
  (:refer-clojure :exclude [update partition])
  (:require [datomic.api      :as d]
            [tupelo.core      :refer [truthy? safe-> it-> spy spyx spyxx grab any?]]
            [tupelo.schema    :as ts]
            [schema.core      :as s] )
  (:use   clojure.pprint)
  (:import [java.util HashSet] )
  (:gen-class))

;---------------------------------------------------------------------------------------------------
; Notes:
;
; EAVT makes Datomic a database of *facts*.  EAV is just a database of *state*.
; Relation:   A set of maps (possibly shortened to a vector)
; Tuple:      A (fixed-length) vector (usually one of a group)
; Value:      A primitive value like "Joe" or 42
;
;---------------------------------------------------------------------------------------------------
; #todo
; - Verify that on update, retraction of old & assertion of new both get same tx/timestamp
; - Each entity should have an :entity/type attr, populated by ident-vals like :entity.type/person,
;   :entity.type/address, etc.
; - Each entity.type should have an entity.type.*/invariants list of functions which must always be
;   true (integrity constraints).
; 
; So a an Entity of type :entity.type/person looks like:
;              <name>          <type>      <constraints/invariants>
;             :person/name     String    #{ <english alphabet> fn.2 ... }
;             :person/email    String    #{ <email constraints> fn.2 ... }
;             :person/phone    long      #{ <us=10 digits> fn.2 ... }
;             :entity/type      attr      :entity.type/person
; 
; Does then [?eid :entity/_type  :entity.type/person] yield a list of all "person" entities?
; 
; 
;---------------------------------------------------------------------------------------------------
; Prismatic Schema type definitions
(s/set-fn-validation! true)   ; #todo add to Schema docs

(def special-attrvals
 "A map that defines the set of permissible values for use in attribute definition.

  User-defined attributes are special entities in Datomic. They are stored in the :db.part/db
  partition, and are defined by special attributes that are built-in to Datomic (this is analgous to
  the special forms that are built-in to Clojure). The root attributes are named by the following
  keywords (all in the 'db' namespace):

    :db/id
    :db/ident
    :db/valueType
    :db/cardinality
    :db/unique
    :db/doc
    :db/index
    :db/fulltext
    :db/isComponent
    :db/noHistory

  For each of these special attributes, this map defines the permissible values used for specifying
  user-defined attributes. Most special attributes are defined by a set of permissible keyword
  values. Permissible values for other special attributes are defined by a predicate function.  "
  { :db/valueType
      #{ :db.type/keyword   :db.type/string   :db.type/boolean  :db.type/long     :db.type/bigint 
         :db.type/float     :db.type/double   :db.type/bigdec   :db.type/bytes 
         :db.type/instant   :db.type/uuid     :db.type/uri      :db.type/ref }

    :db/cardinality   #{ :db.cardinality/one :db.cardinality/many }

    :db/unique        #{ :db.unique/value :db.unique/identity }

  ; #todo - document & enforce types & values for these attrs:
  ;   :db/ident #(keyword? %)
  ;   :db/doc #(string? %)
  ;   :db/index #{ true false }
  ;   :db/fulltext #{ true false }
  ;   :db/isComponent #{ true false }
  ;   :db/noHistory #{ true false }
  } )

;---------------------------------------------------------------------------------------------------
; Core functions

(s/defn new-partition :- ts/KeyMap
  "Returns the tx-data to create a new partition in the DB. Usage:

    (d/transact *conn* [
      (partition ident)
    ] )
  "
  [ident :- s/Keyword]
  (when-not (keyword? ident)
    (throw (IllegalArgumentException. (str "attribute ident must be keyword: " ident ))))
  { :db/id                    (d/tempid :db.part/db) ; The partition :db.part/db is built-in to Datomic
    :db.install/_partition    :db.part/db   ; ceremony so Datomic "installs" our new partition
    :db/ident                 ident } )     ; the "name" of our new partition

(s/defn new-attribute    :- ts/KeyMap
  "Returns the tx-data to create a new attribute in the DB.  Usage:

    (d/transact *conn* [
      (attribute ident value-type & options)
    ] )

   The first 2 params are required. Other params are optional and will use normal Datomic default
   values (false or nil) if omitted. An attribute is assumed to be :db.cardinality/one unless
   otherwise specified.  Optional values are:

      :db.unique/value
      :db.unique/identity
      :db.cardinality/one     <- assumed by default
      :db.cardinality/many
      :db/index
      :db/fulltext
      :db/isComponent
      :db/noHistory
      :db/doc                 <- *** currently unimplemented ***
  "
  [ ident       :- s/Keyword
    value-type  :- s/Any
   & options ]  ; #todo type spec?
  (when-not (keyword? ident)
    (throw (IllegalArgumentException. (str "attribute ident must be keyword: " ident ))))
  (when-not (truthy? (safe-> special-attrvals :db/valueType value-type))
    (throw (IllegalArgumentException. (str "attribute value-type invalid: " ident ))))
  (let [base-specs    { :db/id                  (d/tempid :db.part/db)
                        :db.install/_attribute  :db.part/db ; Datomic ceremony to "install" the new attribute
                        :db/cardinality         :db.cardinality/one   ; default value for most attrs
                        :db/ident               ident
                        :db/valueType           value-type }
        option-specs  (into (sorted-map)
                        (for [it options]
                          (cond 
                            (= it :db.unique/value)         {:db/unique :db.unique/value}
                            (= it :db.unique/identity)      {:db/unique :db.unique/identity}
                            (= it :db.cardinality/one)      {:db/cardinality :db.cardinality/one}
                            (= it :db.cardinality/many)     {:db/cardinality :db.cardinality/many}
                            (= it :db/index)                {:db/index true}
                            (= it :db/fulltext)             {:db/fulltext true}
                            (= it :db/isComponent)          {:db/isComponent true}
                            (= it :db/noHistory)            {:db/noHistory true}
                            (string? it)                    {:db/doc it})))
        tx-specs      (into base-specs option-specs)
  ]
    tx-specs
  ))

; #todo need test
(s/defn new-entity  :- ts/KeyMap
  "Returns the tx-data to create a new entity in the DB. Usage:

    (d/transact *conn* [
      (new-entity attr-val-map)                 ; default partition -> :db.part/user 
      (new-entity partition attr-val-map)       ; user-specified partition
    ] )

   where attr-val-map is a Clojure map containing attribute-value pairs to be added to the new
   entity."
  ( [ attr-val-map    :- ts/KeyMap ]
   (new-entity :db.part/user attr-val-map))
  ( [ -partition      :- s/Keyword
      attr-val-map    :- ts/KeyMap ]
    (into {:db/id (d/tempid -partition) } attr-val-map)))

; #todo need test
(s/defn new-enum :- ts/KeyMap   ; #todo add namespace version
  "Returns the tx-data to create a new enumeration entity in the DB. Usage:

    (d/transact *conn* [
      (new-enum ident)
    ] )

  where ident is the (keyword) name for the new enumeration entity.  "
  [ident :- s/Keyword]
  (when-not (keyword? ident)
    (throw (IllegalArgumentException. (str "attribute ident must be keyword: " ident ))))
  (new-entity {:db/ident ident} ))

; #todo  -  document entity-spec as EID or refspec in all doc-strings
; #todo  -  document use of "ident" in all doc-strings
(s/defn update :- ts/KeyMap
  "Returns the tx-data to update an existing entity  Usage:

    (d/transact *conn* [
      (update entity-spec attr-val-map)
    ] )

   where attr-val-map is a Clojure map containing attribute-value pairs to be added to the new
   entity.  For attributes with :db.cardinality/one, the previous value will be (automatically)
   retracted prior to the insertion of the new value. For attributes with :db.cardinality/many, the
   new value will be accumulated into the current set of values.  "
  [entity-spec    :- ts/EntitySpec
   attr-val-map   :- ts/KeyMap ]
    (into {:db/id entity-spec} attr-val-map))

(s/defn retract-value :- ts/Vec4
  "Returns the tx-data to retract an attribute-value pair for an entity. Usage:

    (d/transact *conn* [
      (retract-value entity-spec attribute value)
    ] )

   where the attribute-value pair must exist for the entity or the retraction will fail.  " ; #todo verify
  [entity-spec  :- ts/EntitySpec
   attribute    :- s/Keyword
   value        :- s/Any ]
  [:db/retract entity-spec attribute value] )

(s/defn retract-entity :- ts/Vec2
  "Returns the tx-data to retract all attribute-value pairs for an entity, as well as all references
   to the entity by other entities. Usage:
   
    (d/transact *conn* [
      (retract-entity entity-spec)
    ] )
   
  If the retracted entity refers to any other entity through an attribute with :db/isComponent=true,
  the referenced entity will be recursively retracted as well."
  [entity-spec  :- ts/EntitySpec ]
  [:db.fn/retractEntity entity-spec] )

; #todo need test
(s/defn transact :- s/Any  ; #todo
  "Like (d/transact [...] ), but does not require wrapping everything in a Clojure vector. Usage:
   
    (t/transact *conn*
      (t/new-entity ident)
      (t/update entity-spec-1 attr-val-map-1)
      (t/update entity-spec-2 attr-val-map-2))
   "
  [conn & tx-specs]
  (d/transact conn tx-specs))

;---------------------------------------------------------------------------------------------------
; Query

; #todo need checks to stop collection result (:find [?e ...])
; #todo and scalar result (:find [?e .])
(defmacro query* ; #todo remember 'with'
  ; returns a HashSet of datomic entity objects
  "Base function for improved API syntax for datomic/q query (Entity API)"
  [& args]
  (let [args-map    (apply hash-map args)
      ; _ (println args-map)
        let-vec     (grab :let args-map)
        let-map     (apply hash-map let-vec)
      ; _ (println let-map)
        let-syms    (keys let-map)
      ; _ (println let-syms)
        let-srcs    (vals let-map)
      ; _ (println let-srcs)
        find-vec    (grab :find args-map)
        _ (println \newline find-vec)
        where-vec   (grab :where args-map)
      ; _ (println where-vec)
  ]
    (when-not (vector? let-vec)
      (throw (IllegalArgumentException. (str "query*: value for :let must be a vector; received=" let-vec))))
    (when-not (vector? find-vec)
      (throw (IllegalArgumentException. (str "query*: value for :find must be a vector; received=" find-vec))))
    (when-not (vector? where-vec)
      (throw (IllegalArgumentException. (str "query*: value for :where must be a vector; received=" where-vec))))

   `(d/q  '{:find   ~find-vec
            :where  ~where-vec 
            :in     [ ~@let-syms ] }
        ~@let-srcs)
  ))

; Usage sample
#_(td/query   :let    [$      (d/db *conn*) 
                       ?name  "Mephistopheles"]
              :find   [?e]
              :where  [ [?e :person/name ?name] ] )

(defmacro query
  "Returns a TupleSet #{ [s/Any] } of query results, where each tuple is unique."
  [& args]
  (println "query" args)
  `(into #{} 
      (for [tuple# (query* ~@args) ]
        (into [] tuple#))))

(defmacro query-set
  "Returns a Set #{s/Any} of query results, where each item is unique."
  [ & args ]
  (println "query-set" args)
  `(into #{}
      (for [tuple# (query* ~@args)]
        (do 
          (assert (= 1 (count tuple#)) 
                  "query-set: tuple must hold only one item")
          (first tuple#)))))

(defn contains-pull?
  "Returns true if a sequence of symbols includes 'pull'"
  [args]
  (println \newline "contains-pull?" args)
  (let [args-map    (apply hash-map args)
        find-vec    (flatten [ (grab :find args-map) ] ) ]
    (spyxx find-vec)
    (doseq [item find-vec]
      (do (print " ") (pr item) ))
    (newline)
    (any? #(= 'pull %) find-vec)))

(defmacro query-pull
  "Returns a TupleList [Tuple] of query results, where items may be duplicated. Intended only for
   use with the Datomic Pull API"
  [ & args ]  ; #todo add check for pull api presence, else exception
  '(do 
      (println "query-pull" args)
      (assert (tupelo.datomic/contains-pull? args)
              "query-pull: Only intended for queries using the Datomic Pull API")
      (println "query-pull: past assert")
      (into []
          (for [tuple# (query* ~@args)]
            (into [] tuple#)))))

(do
  (println "----------------------------------------------------------------------------------------")
  (println "query-pull expand")
  (println (macroexpand
             '(td/query-pull  :let    [$ db-val]
                              :find   [ (pull ?c [*]) ]
                              :where  [ [?c :community/name] ] ))))

(defmacro query-tuple
  "Returns a single Tuple [s/Any] of query results"
  [ & args ]
  (println "query-tuple" args)
  `(let [result-set# (query* ~@args) ]
      (assert (= 1 (count result-set#))
              "query-tuple: result-set must hold only one tuple")
      (into [] (first result-set#))))

(defmacro query-scalar
  "Returns a scalar query result"
  [ & args ]
  (println "query-scalar:" args)
  `(let [tuple# (query-tuple ~@args) ] ; retrieve the single-tuple result
      (assert (= 1 (count tuple#))
              "query-scalar: result-set must be a single scalar item")
      (first tuple#)))

; #todo: write blog post/forum letter about this testing technique
(defn t-query
  "Test the query macro, returns true on success."
  []
  (let [expanded-result
          (macroexpand-1 '(tupelo.datomic/query*  :let    [a  (src 1)  
                                                           b  val-2]
                                                  :find   [?e]
                                                  :where  [ [?e :person/name ?name] ] ))
  ]
    (= expanded-result
       '(datomic.api/q (quote {:find [?e], 
                               :where [[?e :person/name ?name]], 
                               :in [a b]}) 
                       (src 1) val-2) )))


;---------------------------------------------------------------------------------------------------
; Informational functions

; (defn find-tuples   ...)       -> TupleSet
; (defn find-scalars  ...)       -> Set
; (defn find-one      ...)       -> scalar

(s/defn result-set :- ts/TupleSet
  "Returns a TupleSet (hash-set of tuples) built from the output of a Datomic query using the Entity API"
  [raw-resultset :- ts/Set]
  (into #{} raw-resultset))

(s/defn result-only :- [s/Any]
  "Returns a single tuple result built from the output of a Datomic query using the Entity API"
  [raw-resultset :- ts/Set]
  (let [rs          (result-set raw-resultset)
        num-tuples  (count rs) ]
    (when-not (= 1 num-tuples)
      (throw (IllegalStateException. 
               (format "TupleSet must have exactly one tuple; count = %d" num-tuples))))
    (first rs)))

(s/defn result-scalar :- s/Any
  "Returns a single scalar result built from the output of a Datomic query using the Entity API"
  [raw-resultset :- ts/Set]
  (let [tuple       (result-only raw-resultset)
        tuple-len   (count tuple) ]
    (when-not (= 1 tuple-len)
      (throw (IllegalStateException. 
               (format "TupleSet must be one tuple of one element; tuple-len = %d" ))))
    (first tuple)))

; #todo - need test
(s/defn entity-map :- ts/KeyMap
  "Returns a map of an entity's attribute-value pairs. A simpler, eager version of datomic/entity."
  [db-val         :- datomic.db.Db
   entity-spec    :- ts/EntitySpec ]
  (into {} (d/entity db-val entity-spec)))

; #todo - need test
(s/defn eid->ident :- s/Keyword
  "Returns the keyword ident value given an EID value"
  [db-val     :- s/Any  ; #todo
   eid-val    :- ts/Eid]
  (d/q '{:find  [?ident .]
         :in    [$ ?eid]
         :where [ [?eid :db/ident ?ident] ] }
       db-val eid-val ))

; #todo - need test
(s/defn datom-map :- ts/DatomMap
  "Returns a plain of Clojure map of an datom's attribute-value pairs. 
   A datom map is structured as:

      { :e        entity id (eid)
        :a        attribute eid
        :v        value
        :tx       transaction eid
        :added    true/false (assertion/retraction) }
   "
  [datom :- s/Any]  ; #todo
  { :e            (:e     datom)
    :a      (long (:a     datom)) ; must cast Integer -> Long
    :v            (:v     datom)  ; #todo - add tests to catch changes
    :tx           (:tx    datom)
    :added        (:added datom) } )

; #todo - need test
; #todo - make non-lazy?
(s/defn datoms :- [ ts/DatomMap ]
  "Returns a lazy sequence of Clojure maps of an datom's attribute-value pairs. 
   A datom map is structured as:

      { :e        entity id (eid)
        :a        attribute eid
        :v        value
        :tx       transaction eid
        :added    true/false (assertion/retraction) }

   Like (d/datoms ...), but returns a seq of plain Clojure maps.  "
  [db             :- s/Any
   index          :- s/Keyword
   & components ]  ; #todo
  (for [datom (apply d/datoms db index components) ]
    (datom-map datom)))

; #todo - need test
(s/defn tx-datoms :- s/Any
  "Returns a vector of datom-maps from a TxResult"
  [db-val     :- s/Any  ; #todo
   tx-result  :- ts/TxResult ]
  (let [tx-data       (:tx-data tx-result)  ; a seq of datoms
        fn-datom      (fn [arg]
                        (let [datom1  (datom-map arg)
                              attr-eid    (:a datom1)
                              attr-ident  (eid->ident db-val attr-eid)
                              datom2  (assoc datom1 :a attr-ident)
                        ]
                          datom2 ))
        tx-datoms      (mapv fn-datom tx-data)
    ]
      tx-datoms ))

; #todo - need test
(s/defn partition-name :- s/Keyword
  "Returns the name of a DB partition (its :db/ident value)"
  [db-val       :- datomic.db.Db
   entity-spec  :- ts/EntitySpec ]
  (d/ident db-val (d/part entity-spec)))

; #todo - need test
(s/defn is-transaction? :- s/Bool
  "Returns true if an entity is a transaction (i.e. it is in the :db.part/tx partition)"
  [db-val   :- s/Any
   eid      :- ts/Eid ]
  (= :db.part/tx (partition-name db-val eid)))

; #todo - need test
(s/defn transactions :- [ ts/KeyMap ]
  "Returns a lazy sequence of entity-maps for all DB transactions"
  [db-val :- s/Any]
  ; Transactions are entities which always have a :db/txInstant attribute.
  (let [candidate-eids    (map :e (datoms db-val :aevt :db/txInstant))
            ; All transaction entities must have attr :db/txInstant
        tx-eids           (filter #(is-transaction? db-val %) candidate-eids) 
            ; filter in case any user entities have attr :db/txInstant
        result            (map #(entity-map db-val %) tx-eids) ]
    result))

; #todo need test
(s/defn eids :- [ts/Eid]
  "Returns a collection of the EIDs created in a transaction."
  [tx-result :- ts/TxResult]
  (vals (grab :tempids tx-result)))

(s/defn txid  :- ts/Eid
  "Returns the EID of a transaction"
  [tx-result :- ts/TxResult]
  (let [datoms  (grab :tx-data tx-result)
        txids   (mapv :tx datoms) ] 
    (assert (apply = txids))  ; all datoms in tx have same txid
    (first txids)))           ; we only need the first datom

;---------------------------------------------------------------------------------------------------
; Pull stuff
; #todo:  pull-one
; #todo:  pull-many
; #todo:  pull-deep (pull-recursive) ; need a limit?

