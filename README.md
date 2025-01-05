Reactive derived state built on Clojure/Script/Dart built-in
observability interfaces.

```clojure
(require '[clj-arsenal.stream :as stream])

(def !state (atom {:foo 1}))

(def stream
  (stream/streamer
    (fn [stream-kind & args]
      (case stream-kind
        :state-path
        (let [watch-key (gensym)]
          {::stream/boot
           (fn [push!]
             (push! (get-in @!state path))
             (add-watch !state watch-key
               (fn [_ _ old-val new-val]
                 (when (not= (get-in old-val path) (get-in new-val path))
                   (push! (get-in new-val path))))))
          
          ::stream/kill
          (fn []
            (remove-watch !state watch-key))
          
          ::stream/snap
          (fn []
            (get-in @!state path))})
      ;; other stream kinds
      ))))

(def my-stream (stream :state-path [:foo]))

@my-stream ; -> 1

(add-watch my-stream
 (fn [_ _ _ v]
   (prn :v v)))
; >> :v 1

(swap! !state update :foo inc)
; >> :v 2

@my-stream ; -> 2
```

The streamer (i.e `stream`) returns a reference to a stream, which
is identified by the args passed to the function.  So multiple calls
to with the same args, will be backed by the same stream state.

A stream reference is watchable and derefable.  If the stream has any
number of watches then it 'boots', meaning it has some backing state,
and will update watches when it changes.  When the watch count drops
to zero, the stream is killed, removing its associated state.

Dereferencing a stream ref returns the current value of the stream
if it's booted; otherwise `::stream/snap` is called (if provided)
to get a snapshot of the stream's backing data.  If a snap function
isn't provided, then dereferencing an unbooted stream yields `nil`.

Streams are 'flushed' on a periodic basis; meaning newly pushed
values don't immediately propegate to watchers.  By default this
happens every 20 milliseconds; but this can be customized by
passing a `:flush-signal` option to `streamer`; the streamer will
flush anytime this signal is called.

When a booted stream reaches a watch count of zero, it isn't killed
immediately.  Instead, by default, it'll be killed on the next flush.
However this grace period can be extended at either the streamer or
the stream level.  Adding an `:extra-lives` (in integer) option to
the streamer options sets the default grace period for all streams.
Setting a `::stream/extra-lives` key in the stream map customizes
only a specific stream.
