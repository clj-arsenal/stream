(ns clj-arsenal.stream
  (:require
   [clj-arsenal.basis :refer [m] :as b]
   [clj-arsenal.basis.protocols.dispose :refer [Dispose]]
   [clj-arsenal.log :refer [log spy]]
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

(defprotocol StreamSpec
  (^:private -resolve-spec [ss streamer args]))

(deftype ^:private Streamer [handler !state opts flush-signal stop-fn]
  #?@(:cljs
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
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20]
        (streamer-call this [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20]))
       (-invoke [this x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20 args]
        (streamer-call this (into [x1 x2 x3 x4 x5 x6 x7 x8 x9 x10 x11 x12 x13 x14 x15 x16 x17 x18 x19 x20] args)))]

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

(deftype ^:private StreamRef
  [^Streamer streamer args-vec]
  #?@(:cljs
      [IWatchable
       (-notify-watches
         [this old-val new-val]
         (notify-watches! this old-val new-val))
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
         (notify-watches! this old-val new-val))
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
         (get-in @(.-!state streamer) [::stream-states args-vec ::watches] {}))
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
  [^StreamRef stream-ref old-val new-val]
  (let [!state (-> stream-ref ^Streamer (.-streamer) .-!state)]
    (doseq [[watch-k watch-fn] (get-in @!state [::stream-states (.-args-vec stream-ref) ::watches])]
      (m
        (watch-fn watch-k stream-ref old-val new-val)
        :catch b/err-any err 
        (log :error :ex err ::stream-args (.-args-vec stream-ref) :watch-k watch-k)))))

(defn- push-fn
  [!state stream-args-vec]
  (fn push! [x]
    (swap! !state
      (fn [state]
        (if-not (get-in state [::stream-states stream-args-vec])
          state
          (assoc-in state [::pending-stream-values stream-args-vec] x))))))

(defn- create-config
  [^StreamRef stream-ref]
  (let
    [args (.-args-vec stream-ref)
     [first-arg & rest-args] args
     streamer ^Streamer (.-streamer stream-ref)]
    (merge
      {::extra-lives (or (-> streamer .-opts :extra-lives) 1)}
      (if (satisfies? StreamSpec first-arg)
        (-resolve-spec first-arg streamer rest-args)
        (apply (.-handler streamer) args)))))

(defn- add-watch!
  [^StreamRef stream-ref k f]
  (let
    [streamer ^Streamer (.-streamer stream-ref)
     !state (.-!state streamer)
     args-vec (.-args-vec stream-ref)

     [old-state new-state]
     (swap-vals! !state update-in [::stream-states args-vec]
       (fn [{old-watches ::watches :as stream-state}]
         (let
           [new-watches (assoc old-watches k f)]
           (cond
             (some? stream-state)
             (assoc stream-state
               ::lives-remaining (get-in stream-state [::config ::extra-lives])
               ::watches new-watches)

             :else
             (let
               [default-extra-lives 
                (-> stream-ref ^Streamer (.-streamer) .-opts :extra-lives (or 1))

                config
                (update (create-config stream-ref) ::extra-lives
                  #(or % default-extra-lives))]
               (assoc stream-state
                 ::config config
                 ::lives-remaining (::extra-lives config)
                 ::watches new-watches))))))
     
     old-stream-state (get-in old-state [::stream-states args-vec])
     new-stream-state (get-in new-state [::stream-states args-vec])]

    (when-not old-stream-state
      (let [boot-fn (get-in new-stream-state [::config ::boot])]
        (when (ifn? boot-fn)
          (boot-fn
            {:push! (push-fn !state (.-args-vec stream-ref))
             :stream streamer}))))
    nil))

(defn- remove-watch!
  [^StreamRef stream-ref k]
  (let [!state (-> stream-ref .-streamer .-!state)]
    (swap! !state
      (fn [state]
        (if-not (contains? (get-in state [::stream-states (.-args-vec stream-ref) ::watches]) k)
          state
          (update-in state [::stream-states (.-args-vec stream-ref) ::watches] dissoc k)))))
  nil)

(defn- stream-ref-equiv
  [^StreamRef stream-ref other]
  (and (instance? StreamRef other)
    (= (.-args-vec stream-ref) (.-args-vec ^StreamRef other))
    (= (.-streamer stream-ref) (.-streamer ^StreamRef other))))

(defn- stream-ref-hash
  [^StreamRef stream-ref]
  (hash (.-args-vec stream-ref)))

(defn- stream-ref-deref
  [^StreamRef stream-ref]
  (let
    [streamer ^Streamer (.-streamer stream-ref)
     !state (.-!state streamer)
     stream-state (get-in @!state [::stream-states (.-args-vec stream-ref)] {})
     stream-value (get stream-state ::value ::not-found)]
    (if (not= stream-value ::not-found)
      stream-value
      (let [{snap-fn ::snap default-value ::default}
            (or (::config stream-state)
              (create-config stream-ref))]
        (cond
          (ifn? snap-fn)
          (snap-fn {:stream streamer})
          
          :else
          default-value)))))

(defn- prepare-flush-secondary
  [state opts]
  (let
    [equiv-fn (or (:equiv-fn opts) identical?)

     dirty-streams
     (persistent!
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

     stream-states
     (persistent!
       (reduce-kv
         (fn [m k v]
           (cond-> m
             (not (get-in v [::config ::ephemeral]))
             (assoc! k (assoc (get m k) ::value (::value v)))))
         (transient (::stream-states state))
         dirty-streams))]
    (assoc state
      ::pending-stream-values {}
      ::dirty-streams dirty-streams
      ::stream-states stream-states)))

(defn- flush-secondary!
  [^Streamer streamer opts]
  (swap! (.-!state streamer) prepare-flush-secondary opts)
  (let
    [[{dirty-streams ::dirty-streams} _]
     (swap-vals! (.-!state streamer) assoc ::dirty-streams {})]
    (doseq [[args-vec stream-state] dirty-streams]
      (notify-watches!
        (->StreamRef streamer args-vec)
        (::old-value stream-state)
        (::value stream-state)))
    (when (seq (::pending-stream-values @(.-!state streamer)))
      (recur streamer opts))))

(defn- prepare-flush
  [state opts]
  (let
    [equiv-fn (or (:equiv-fn opts) identical?)
     stream-states (::stream-states state)

     dirty-streams
     (persistent!
       (reduce-kv
         (fn [!dirty-streams k v]
           (let
             [stream-state (get stream-states k)

              old-value (get-in !dirty-streams [k ::value] ::not-found)
              old-value (if (= ::not-found old-value) (get stream-state k ::not-found) old-value)]
             (if (or (nil? stream-state) (equiv-fn v old-value))
               !dirty-streams
               (assoc! !dirty-streams k
                 (cond-> (assoc stream-state ::value v)
                   (not= ::not-found old-value)
                   (assoc ::old-value old-value))))))
         (transient (::dirty-streams state))
         (::pending-stream-values state)))

     [stream-states killed-streams]
     (map persistent!
       (reduce-kv
         (fn [[!stream-states !killed-streams] k v]
           (let
             [lives-remaining (::lives-remaining v)
              config (::config v)
              extra-lives (::extra-lives config)
              ephemeral (::ephemeral config)
              num-watches (count (::watches v))

              new-value
              (if ephemeral
                ::not-found
                (get-in dirty-streams [k ::value] ::not-found))]
             (cond
               (zero? num-watches)
               (if (pos? lives-remaining)
                 [(assoc! !stream-states k
                    (cond-> (update v ::lives-remaining dec)
                      (not= new-value ::not-found)
                      (assoc ::value new-value)))
                  !killed-streams]
                 [(dissoc! !stream-states k)
                  (assoc! !killed-streams k v)])

               :else
               [(assoc! !stream-states k
                  (cond-> v
                    (not= new-value ::not-found)
                    (assoc ::value new-value)

                    (< lives-remaining extra-lives)
                    (assoc ::lives-remaining extra-lives)))
                !killed-streams])))
         [(transient stream-states)
          (transient (::killed-streams state))]
         (::stream-states state)))]
    (assoc state
      ::pending-stream-values {}
      ::dirty-streams dirty-streams
      ::stream-states stream-states
      ::killed-streams killed-streams)))

(defn- flush!
  [^Streamer streamer opts]
  (swap! (.-!state streamer) prepare-flush opts)
  (let
    [[{dirty-streams ::dirty-streams killed-streams ::killed-streams} _]
     (swap-vals! (.-!state streamer) assoc ::dirty-streams {} ::killed-streams {})]
    (doseq
      [[args-vec stream-state] killed-streams
       :let [kill-fn (get-in stream-state [::config ::kill])]
       :when (ifn? kill-fn)]
      (m
        (kill-fn {})
        :catch b/err-any err
        (log :error :ex err ::stream-args args-vec)))
    (doseq
      [[args-vec stream-state] dirty-streams]
      (notify-watches!
        (->StreamRef streamer args-vec)
        (::old-value stream-state)
        (::value stream-state)))
    (when (seq (::pending-stream-values (.-!state streamer)))
      (flush-secondary! streamer opts))
    
    (when-some [after-flush (:after-flush opts)]
      (after-flush))))

(defn- streamer-call
  [^Streamer streamer args]
  (->StreamRef streamer (vec args)))

(defn- streamer-dispose!
  [^Streamer streamer]
  (b/notifier-unlisten (.-flush-signal streamer) streamer)
  (when-some [stop-fn (.-stop-fn streamer)]
    (stop-fn))
  nil)

(def ^:private init-streamer-state
  {::stream-states {} ::streams-to-kill {} ::pending-stream-values {} ::dirty-streams {} ::killed-streams {}})

(defn streamer "
Creates a streamer.  Calling the streamer returns a stream reference,
which can be watched and derefed like a built-in reference type.

The `handler` takes all args passed to the streamer call, and should
return a map of `{::boot boot-fn ::kill ?kill-fn ::snap ?snap-fn ::extra-lives ?extra-lives ::default ?default-value}`.

Options are:
- `:flush-signal` - a custom signal to trigger flushes
- `:extra-lives` - the default number of flushes to go after a stream's watch count
   reaches zero, before killing it.
" [handler & {:as opts}]
  (let
    [!state (atom init-streamer-state)

     [flush-signal streamer]
     (if-some [flush-signal (:flush-signal opts)]
       [flush-signal (->Streamer handler !state opts flush-signal nil)]
       (let
         [flush-clock (b/clock 20)
          stop-fn (fn [] (b/dispose! flush-clock))]
         [flush-clock (->Streamer handler !state opts flush-clock stop-fn)]))]
    (b/notifier-listen flush-signal streamer #(flush! streamer opts))
    streamer))

(defn args-spec
  [& args]
  (reify
    StreamSpec
    (-resolve-spec
      [_ streamer extra-args]
      (if (satisfies? StreamSpec (first args))
        (-resolve-spec (first args) streamer (concat (rest args) extra-args))
        (apply (.-handler ^Streamer streamer) (concat args extra-args))))))

(defn derive-spec
  [deps f & {:keys [on-boot on-kill extra-lives]}]
  {:pre [(or (map? deps) (fn? deps))]}
  (reify
    StreamSpec
    (-resolve-spec
      [_ streamer args]
      (let
        [deps (if (fn? deps) (apply deps args) deps)
         deps (update-vals deps #(apply streamer %))
         !deps-vals (volatile! {})
         !derived (atom ::placeholder)
         watch-key (gensym)]
        {::extra-lives extra-lives

         ::boot
         (fn [{:keys [push!]}]
           (add-watch !derived watch-key
             (fn [_ _ old-val new-val]
               (when (not= old-val new-val)
                 (when (and (vector? old-val) (-> old-val meta :stream))
                   (remove-watch (apply streamer old-val) watch-key))
                 (cond
                   (and (vector? new-val) (-> new-val meta :stream))
                   (add-watch (apply streamer new-val) watch-key
                     (fn [_ _ _ v]
                       (push! v)))
                   
                   :else
                   (push! new-val)))))
           (let
             [dep-vals (update-vals deps deref)
              derived (apply f dep-vals args)]
             (vreset! !deps-vals dep-vals)
             (reset! !derived derived))

           (doseq [[k w] deps]
             (add-watch w watch-key
               (fn [_ _ old-val new-val]
                 (when (not= old-val new-val)
                   (let
                     [deps-vals (vswap! !deps-vals assoc k new-val)
                      derived (apply f deps-vals args)]
                     (reset! !derived derived))))))
           (when (ifn? on-boot)
             (apply on-boot @!deps-vals args)))

         ::snap
         (fn [_]
           (let
             [derived @!derived
              derived (if (= derived ::placeholder) (apply f (update-vals deps deref) args) derived)]
             (if (and (vector? derived) (-> derived meta :stream))
               @(apply streamer derived)
               derived)))

         ::kill
         (fn [_]
           (doseq [w (vals deps)]
             (remove-watch w watch-key))
           (reset! !derived nil)
           (remove-watch !derived watch-key)
           (when (ifn? on-kill)
             (apply on-kill @!deps-vals args)))}))))

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
