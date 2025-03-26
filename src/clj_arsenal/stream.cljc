(ns clj-arsenal.stream
  (:require
   [clj-arsenal.basis :refer [m] :as b]
   [clj-arsenal.basis.protocols.dispose :refer [Dispose]]
   [clj-arsenal.log :refer [log]]
   [clj-arsenal.check :refer [check expect when-check]]
   #?@(:cljd
       [[cljd.core :refer [IWatchable IEquiv IHash IDeref IFn]]
        [cljd.flutter :refer [Subscribable]]]))
  (:import
   #?@(:cljd
       []

       :clj
       [java.io.Closeable
       [clojure.lang IDeref IRef IFn]])))

(when-check
  #?(:clj (set! *warn-on-reflection* true)))

(declare
  ^:private notify-watches! ^:private add-watch! ^:private remove-watch!
  ^:private stream-ref-equiv ^:private stream-ref-hash ^:private stream-ref-deref
  ^:private streamer-call ^:private streamer-dispose!)

(deftype ^:private StreamRef
  [!state k config]
  #?@(:cljs
      [IWatchable
       (-notify-watches
         [this old-val new-val]
         (notify-watches! this @(get-in @!state [::stream-states k ::watches]) old-val new-val))
       (-add-watch
         [this k f]
         (add-watch! this k f))
       (-remove-watch
         [this k]
         (remove-watch! this k))
       
       IEquiv
       (-equiv
         [this other]
         (stream-ref-equiv this other))
       
       IHash
       (-hash
         [this]
         (stream-ref-hash this))
       
       IDeref
       (-deref
         [this]
         (stream-ref-deref this))]

      :cljd
      [IWatchable
       (-notify-watches
         [this old-val new-val]
         (notify-watches! this @(get-in @!state [::stream-states k ::watches]) old-val new-val))
       (-add-watch
         [this k f]
         (add-watch! this k f))
       (-remove-watch
         [this k]
         (remove-watch! this k))

       Subscribable
       (-subscribe
         [this push!]
         (let [watch-key (gensym)]
           (add-watch this watch-key (fn [_ _ _ v] (push! v)))
           watch-key))
       (-call-with-immediate-value
         [this sub f!]
         (f! @this)
         true)
       (-unsubscribe
         [this sub]
         (remove-watch this sub))
       
       IEquiv
       (-equiv
         [this other]
         (stream-ref-equiv this other))
       
       IHash
       (-hash
         [this]
         (stream-ref-hash this))
       
       IDeref
       (-deref
         [this]
         (stream-ref-deref this))]
   
      :clj
      [Object
       (hashCode
         [this]
         (stream-ref-hash this))
       (equals
         [this other]
         (stream-ref-equiv this other))
       
       IRef
       (getValidator
         [this]
         nil)
       (getWatches
         [this]
         (get-in @!state [::stream-states k ::watches] {}))
       (addWatch
         [this k f]
         (add-watch! this k f))
       (removeWatch
         [this k]
         (remove-watch! this k))
       
       IDeref
       (deref
         [this]
         (stream-ref-deref this))]))

(defn- notify-watches!
  [^StreamRef stream-ref watches old-val new-val]
  (doseq [[watch-k watch-fn] watches]
    (m
      (watch-fn watch-k stream-ref old-val new-val)
      :catch b/err-any err 
      (log :error :msg "error stream watcher" :ex err :stream-k (.-k stream-ref) :watch-k watch-k))))

(defn- push-fn
  [!state stream-k]
  (fn push! [x]
    (swap! !state
      (fn [state]
        (if-not (get-in state [::stream-states stream-k])
          state
          (assoc-in state [::pending-stream-values stream-k] x))))))

(defn- boot-stream!
  [^StreamRef stream-ref]
  (let [boot-fn (::boot (.-config stream-ref))]
    (when-not (ifn? boot-fn)
      (throw (ex-info "no boot function given for stream" {:stream-k (.-k stream-ref) ::stream-config (.-config stream-ref)})))
    (boot-fn (push-fn (.-!state stream-ref) (.-k stream-ref)))
    nil))

(defn- add-watch!
  [^StreamRef stream-ref k f]
  (let [[old-state new-state] (swap-vals! (.-!state stream-ref) update-in [::stream-states (.-k stream-ref)]
                                (fn [state]
                                  (-> state
                                    (assoc-in [::watches k] f)
                                    (assoc
                                      ::lives-remaining (::extra-lives (.-config stream-ref))
                                      ::ref stream-ref
                                      ::config (.-config stream-ref)))))]
    (when (and
            (zero? (count (get-in old-state [::stream-states (.-k stream-ref) ::watches])))
            (pos? (count (get-in new-state [::stream-states (.-k stream-ref) ::watches]))))
      (boot-stream! stream-ref))
    nil))

(defn- remove-watch!
  [^StreamRef stream-ref k]
  (swap! (.-!state stream-ref) update-in [::stream-states (.-k stream-ref) ::watches] dissoc k)
  nil)

(defn- stream-ref-equiv
  [^StreamRef stream-ref other]
  (and (instance? StreamRef other)
    (= (.-k stream-ref) (.-k ^StreamRef other))
    (= (.-!state stream-ref) (.-!state ^StreamRef other))))

(defn- stream-ref-hash
  [^StreamRef stream-ref]
  (hash [(.-!state stream-ref) (.-k stream-ref)]))

(defn- stream-ref-deref
  [^StreamRef stream-ref]
  (let [value (get-in @(.-!state stream-ref) [::stream-states (.-k stream-ref) ::value] ::not-found)]
    (if (not= value ::not-found)
      value
      (when-some [snap-fn (::snap (.-config stream-ref))]
        (snap-fn)))))

(defn- prepare-flush-secondary
  [state opts]
  (let [equiv-fn (or (:equiv-fn opts) identical?)
        dirty-streams (persistent!
                        (reduce-kv
                          (fn [m k v]
                            (let [stream-state (get-in state [::stream-states k])
                                  old-value (if-some [existing (get m k)]
                                              (::value existing)
                                              (::value stream-state))]
                              (if (or (nil? stream-state) (equiv-fn v old-value))
                                m
                                (assoc! m k (assoc stream-state ::old-value old-value ::value v)))))
                          (transient (::dirty-streams state))
                          (::pending-stream-values state)))
        stream-states (persistent!
                        (reduce-kv
                          (fn [m k v]
                            (assoc! m k (assoc (get m k) ::value (::value v))))
                          (transient (::stream-states state))
                          dirty-streams))]
    (assoc state
      ::pending-stream-values {}
      ::dirty-streams dirty-streams
      ::stream-states stream-states)))

(defn- flush-secondary!
  [!state opts]
  (swap! !state prepare-flush-secondary opts)
  (let [[{dirty-streams ::dirty-streams} _] (swap-vals! !state assoc ::dirty-streams {})]
    (doseq [[stream-k stream-state] dirty-streams]
      (notify-watches!
        (->StreamRef !state stream-k (::config stream-state))
        (::watches stream-state)
        (::old-value stream-state)
        (::value stream-state)))
    (when (seq (::pending-stream-values @!state))
      (recur !state opts))))

(defn- prepare-flush
  [state opts]
  (let [equiv-fn (or (:equiv-fn opts) identical?)
        killed-streams (persistent!
                         (reduce
                           (fn [m k]
                             (let [stream-state (get-in state [::stream-states k])
                                   lives-remaining (::lives-remaining stream-state)]
                               (if (and (not (pos? lives-remaining)) (zero? (count (::watches stream-state))))
                                 (assoc! m k stream-state))))
                           (transient (::killed-streams state))
                           (::streams-to-kill state)))
        stream-states (persistent!
                        (reduce
                          (fn [m k]
                            (dissoc! m k))
                          (transient (::stream-states state))
                          (keys killed-streams)))
        dirty-streams (persistent!
                        (reduce-kv
                          (fn [m k v]
                            (let [stream-state (get stream-states k)
                                  old-value (if-some [existing (get m k)]
                                              (::value existing)
                                              (::value stream-state))]
                              (if (or (nil? stream-state) (equiv-fn v old-value))
                                m
                                (assoc! m k (assoc stream-state ::old-value old-value ::value v)))))
                          (transient (::dirty-streams state))
                          (::pending-stream-values state)))
        stream-states (persistent!
                        (reduce-kv
                          (fn [m k v]
                            (assoc! m k (assoc (get m k) ::value (::value v))))
                          (transient stream-states)
                          dirty-streams))]
    (assoc state
      ::pending-stream-values {}
      ::dirty-streams dirty-streams
      ::stream-states stream-states
      ::killed-streams killed-streams)))

(defn- flush!
  [!state opts]
  (swap! !state prepare-flush opts)
  (let [[{dirty-streams ::dirty-streams killed-streams ::killed-streams} _] (swap-vals! !state assoc ::dirty-streams {} ::killed-streams {})]
    (doseq [[stream-k stream-state] killed-streams
            :let [kill-fn (get-in stream-state [::config ::kill])]
            :when (ifn? kill-fn)]
      (m kill-fn
        :catch b/err-any err
        (log :error :msg "error in stream kill function" :ex err :stream-k stream-k)))
    (doseq [[stream-k stream-state] dirty-streams]
      (notify-watches!
        (->StreamRef !state stream-k (::config stream-state))
        (::watches stream-state)
        (::old-value stream-state)
        (::value stream-state)))
    (when (seq (::pending-stream-values @!state))
      (flush-secondary! !state opts))
    
    (when-some [after-flush (:after-flush opts)]
      (after-flush))))

(deftype ^:private Streamer [handler !state opts flush-signal stop-fn]
  #?@(:cljs
      [IFn
       (-invoke
        ([this]
         (streamer-call this []))
        ([this x]
         (streamer-call this [x]))
        ([this x1 x2]
         (streamer-call this [x1 x2]))
        ([this x1 x2 x3]
         (streamer-call this [x1 x2 x3]))
        ([this x1 x2 x3 x4]
         (streamer-call this [x1 x2 x3 x4]))
        ([this x1 x2 x3 x4 x5]
         (streamer-call this [x1 x2 x3 x4 x5]))
        ([this x1 x2 x3 x4 x5 x6]
         (streamer-call this [x1 x2 x3 x4 x5 x6]))
        ([this x1 x2 x3 x4 x5 x6 x7]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20]))
        ([this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20 args]
         (streamer-call this (into [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20] args))))]

      :cljd
      [IFn
       (-invoke [this]
        (streamer-call this []))
       (-invoke [this x]
        (streamer-call this [x]))
       (-invoke [this x1 x2]
        (streamer-call this [x1 x2]))
       (-invoke [this x1 x2 x3]
        (streamer-call this [x1 x2 x3]))
       (-invoke [this x1 x2 x3 x4]
        (streamer-call this [x1 x2 x3 x4]))
       (-invoke [this x1 x2 x3 x4 x5]
        (streamer-call this [x1 x2 x3 x4 x5]))
       (-invoke [this x1 x2 x3 x4 x5 x6]
        (streamer-call this [x1 x2 x3 x4 x5 x6]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9]))
       (-invoke-more [this x1 x2 x3 x4 x5 x6 x7  x8 x9 rest]
         (streamer-call this (into [x1 x2 x3 x4 x5 x6 x7 x8 x9] rest)))
       (-apply [this more]
         (streamer-call this more))]
   
      :clj
      [Closeable
       (close
         [this]
         (b/dispose! this))
       
       IFn
       (invoke
         [this]
         (streamer-call this []))
       (invoke
         [this x]
         (streamer-call this [x]))
       (invoke
         [this x1 x2]
         (streamer-call this [x1 x2]))
       (invoke
         [this x1 x2 x3]
         (streamer-call this [x1 x2 x3]))
       (invoke
         [this x1 x2 x3 x4]
         (streamer-call this [x1 x2 x3 x4]))
       (invoke
         [this x1 x2 x3 x4 x5]
         (streamer-call this [x1 x2 x3 x4 x5]))
       (invoke
         [this x1 x2 x3 x4 x5 x6]
         (streamer-call this [x1 x2 x3 x4 x5 x6]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20]
         (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20]))
       (invoke
         [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20 args]
         (streamer-call this (into [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20] args)))
       (applyTo
         [this args]
         (streamer-call this args))])
  Dispose
  (-dispose! [this] (streamer-dispose! this)))

(defprotocol StreamSpec
  (^:private -resolve-spec [ss streamer args]))

(defn- streamer-call
  [^Streamer streamer args]
  (let [stream-k (vec args)
        !state (.-!state streamer)
        opts (.-opts streamer)]
    (or (get-in @!state [::stream-states stream-k ::ref])
      (let
        [stream-config
         (if (satisfies? StreamSpec (first args))
           (-resolve-spec (first args) streamer (rest args))
           (apply (.-handler streamer) args))]
        (if (instance? StreamRef stream-config)
          stream-config
          (->StreamRef !state (vec args)
            (cond-> stream-config
              (nil? (::extra-lives stream-config))
              (assoc ::extra-lives (or (:extra-lives opts) 2)))))))))

(defn- streamer-dispose!
  [^Streamer streamer]
  (b/notifier-unlisten (.-flush-signal streamer) streamer)
  ((.-stop-fn streamer))
  nil)

(defn streamer "
Creates a streamer.  Calling the streamer returns a stream reference,
which can be watched and derefed like a built-in reference type.

The `handler` takes all args passed to the streamer call, and should
return a map of `{::boot boot-fn ::kill ?kill-fn ::snap ?snap-fn ::extra-lives ?extra-lives}`.

Options are:
- `:flush-signal` - a custom signal to trigger flushes
- `:extra-lives` - the default number of flushes to go after a stream's watch count
   reaches zero, before killing it.
" [handler & {:as opts}]
  (let [!state (atom {::stream-states {} ::streams-to-kill {} ::pending-stream-values {} ::dirty-streams {} ::killed-streams {}})
        flush-clock (b/clock 20)
        stop-fn (fn [] (b/dispose! flush-clock))
        streamer (->Streamer handler !state opts flush-clock stop-fn)]
    (b/notifier-listen flush-clock streamer #(flush! !state opts))
    streamer))

(defn args-spec
  [& args]
  (reify
    StreamSpec
    (-resolve-spec
      [_ streamer _]
      (apply (.-handler ^Streamer streamer) args))))

(defn derive-spec
  [deps f & {:keys [on-boot on-kill extra-lives]}]
  {:pre [(or (map? deps) (fn? deps))]}
  (reify
    StreamSpec
    (-resolve-spec
      [_ streamer args]
      (let
        [deps (if (fn? deps) (apply deps streamer args) deps)
         deps (update-vals deps #(if (satisfies? StreamSpec %) (streamer %) %))
         !deps-vals (volatile! {})
         !value (volatile! {})
         watch-key (gensym)]
        {::extra-lives extra-lives

         ::boot
         (fn [push!]
           (let
             [dep-vals (update-vals deps deref)
              value (apply f dep-vals args)]
             (vreset! !deps-vals dep-vals)
             (vreset! !value value)
             (push! value))

           (doseq [[k w] deps]
             (add-watch w watch-key
               (fn [_ _ _ v]
                 (let
                   [deps-vals (vswap! !deps-vals assoc k v)
                    value (apply f deps-vals args)]
                   (vswap! !value value)
                   (push! value)))))
           (when (ifn? on-boot)
             (on-boot @!deps-vals)))

         ::kill
         (fn []
           (doseq [w (vals deps)]
             (remove-watch w watch-key))
           (when (ifn? on-kill)
             (on-kill @!deps-vals)))}))))

(check ::simple
  (let [inc-signal (b/signal)
        flush-signal (b/signal)
        !sync (atom nil)
        !flush-count (atom 0)
        stream (streamer
                 (fn [k & args]
                   (case k
                     :counter
                     (let [!counter (atom (first args))]
                       {::boot
                        (fn [push!]
                          (push! @!counter)
                          (b/notifier-listen inc-signal ::listen
                            (fn []
                              (push! (swap! !counter inc)))))

                        ::kill
                        (fn []
                          (b/notifier-unlisten inc-signal ::listen))

                        ::snap
                        (fn []
                          @!counter)})))
                 :flush-signal flush-signal
                 :after-flush #(swap! !flush-count inc))
        s (stream :counter 0)]

    (expect = @s 0)

    (add-watch s ::watch
      (fn [_ _ old-val new-val]
        (reset! !sync [old-val new-val])))

    (flush-signal)
    (expect = @s 0)
    (expect = @!sync [nil 0])
    (expect = @!flush-count 1)

    (inc-signal)
    (flush-signal)

    (expect = @s 1)
    (expect = @!sync [0 1])
    (expect = @!flush-count 2)

    (inc-signal)
    (flush-signal)

    (expect = @s 2)
    (expect = @!sync [1 2])
    (expect = @!flush-count 3)))
