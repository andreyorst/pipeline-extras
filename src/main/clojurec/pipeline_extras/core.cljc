(ns pipeline-extras.core
  (:require
   [clojure.core.async
    :refer [<! >! go put! chan close! go-loop to-chan!
            #?@(:clj [>!! <!! thread])]]))

(defn- pipeline*
  ([n to xf from close? ex-handler type]
   (assert (pos? n))
   (let [ex-handler (or ex-handler
                        #?(:clj (fn [ex]
                                  (-> (Thread/currentThread)
                                      .getUncaughtExceptionHandler
                                      (.uncaughtException (Thread/currentThread) ex))
                                  nil)
                           :cljs nil))
         jobs (chan n)
         results (chan n)
         finishes (and (= type :async) (chan n))
         process #?(:clj (fn process [[v p :as job]]
                           (if (nil? job)
                             (do (close! results) nil)
                             (let [res (chan 1 xf ex-handler)]
                               (>!! res v)
                               (close! res)
                               (put! p res)
                               true)))
                    :cljs (fn process [[v p :as job]]
                            (if (nil? job)
                              (do (close! results) nil)
                              (let [res (chan 1 xf ex-handler)]
                                (go
                                  (>! res v)
                                  (close! res))
                                (put! p res)
                                true))))
         async (fn async [[v p :as job]]
                 (if (nil? job)
                   (do (close! results)
                       (close! finishes)
                       nil)
                   (let [res (chan 1)]
                     (xf v res)
                     (put! p res)
                     true)))]
     (dotimes [_ n]
       (case type
         #?@(:clj [(:blocking :compute)
                   (thread
                     (let [job (<!! jobs)]
                       (when (process job)
                         (recur))))]
             :cljs [:compute (go-loop []
                               (let [job (<! jobs)]
                                 (when (process job)
                                   (recur))))])
         :async (go-loop []
                  (let [job (<! jobs)]
                    (when (async job)
                      (<! finishes)
                      (recur))))))
     (go-loop []
       (let [v (<! from)]
         (if (nil? v)
           (close! jobs)
           (let [p (chan 1)]
             (>! results p)
             (>! jobs [v p])
             (recur)))))
     (go-loop []
       (let [p (<! results)]
         (if (nil? p)
           (when close? (close! to))
           (let [res (<! p)]
             (loop []
               (let [v (<! res)]
                 (when (and (not (nil? v)) (>! to v))
                   (recur))))
             (when finishes
               (>! finishes :done))
             (recur))))))))

(defn pipeline
  "Takes elements from the from channel and supplies them to the to
  channel, subject to the transducer xf, with parallelism n. Because
  it is parallel, the transducer will be applied independently to each
  element, not across elements, and may produce zero or more outputs
  per input.  Outputs will be returned in order relative to the
  inputs. By default, the to channel will be closed when the from
  channel closes, but can be determined by the close?  parameter. Will
  stop consuming the from channel if the to channel closes. Note this
  should be used for computational parallelism. If you have multiple
  blocking operations to put in flight, use pipeline-blocking instead,
  If you have multiple asynchronous operations to put in flight, use
  pipeline-async instead. See chan for semantics of ex-handler."
  ([n to xf from] (pipeline n to xf from true))
  ([n to xf from close?] (pipeline n to xf from close? nil))
  ([n to xf from close? ex-handler] (pipeline* n to xf from close? ex-handler :compute)))

#?(:clj
   (defn pipeline-blocking
     "Like pipeline, for blocking operations."
     ([n to xf from] (pipeline-blocking n to xf from true))
     ([n to xf from close?] (pipeline-blocking n to xf from close? nil))
     ([n to xf from close? ex-handler] (pipeline* n to xf from close? ex-handler :blocking))))

(defn pipeline-async
  "Takes elements from the from channel and supplies them to the to
  channel, subject to the async function af, with parallelism n. af
  must be a function of two arguments, the first an input value and
  the second a channel on which to place the result(s). The
  presumption is that af will return immediately, having launched some
  asynchronous operation whose completion/callback will put results on
  the channel, then close! it. Outputs will be returned in order
  relative to the inputs. By default, the to channel will be closed
  when the from channel closes, but can be determined by the close?
  parameter. Will stop consuming the from channel if the to channel
  closes. See also pipeline, pipeline-blocking."
  ([n to af from] (pipeline-async n to af from true))
  ([n to af from close?] (pipeline* n to af from close? nil :async)))

(defn pipeline-unordered*
  [n to xf from close? ex-handler type]
  (let [closes (to-chan! (repeat (dec n) :close))
        ex-handler (or ex-handler
                       #?(:clj (fn [ex]
                                 (-> (Thread/currentThread)
                                     .getUncaughtExceptionHandler
                                     (.uncaughtException (Thread/currentThread) ex))
                                 nil)
                          :cljs nil))
        process #?(:clj (fn [v p]
                          (let [res (chan 1 xf ex-handler)]
                            (>!! res v)
                            (put! p (<!! res))
                            (close! res)
                            (close! p)))
                   :cljs (fn [v p]
                           (let [res (chan 1 xf ex-handler)]
                             (go
                               (>! res v)
                               (put! p (<! res))
                               (close! res)
                               (close! p)))))]
    (dotimes [_ n]
      (go-loop []
        (if-some [v (<! from)]
          (let [c (chan 1)]
            (case type
              #?@(:clj [(:blocking :compute)
                        (thread (process v c))]
                  :cljs [:compute (go (process v c))])
              :async (go (xf v c)))
            (when (loop []
                    (if-some [res (<! c)]
                      (when (>! to res)
                        (recur))
                      true))
              (recur)))
          (when (and close?
                     (nil? (<! closes)))
            (close! to)))))))

(defn pipeline-unordered
  "Takes elements from the from channel and supplies them to the to
  channel, subject to the transducer xf, with parallelism n. Because
  it is parallel, the transducer will be applied independently to each
  element, not across elements, and may produce zero or more outputs
  per input.  Outputs will be returned in order of completion. By
  default, the to channel will be closed when the from channel closes,
  but can be determined by the close?  parameter. Will stop consuming
  the from channel if the to channel closes. Note this should be used
  for computational parallelism. If you have multiple blocking
  operations to put in flight, use pipeline-blocking instead, If you
  have multiple asynchronous operations to put in flight, use
  pipeline-async instead. See chan for semantics of ex-handler."
  ([n to xf from] (pipeline-unordered n to xf from true))
  ([n to xf from close?] (pipeline-unordered n to xf from close? nil))
  ([n to xf from close? ex-handler] (pipeline-unordered* n to xf from close? ex-handler :compute)))

#?(:clj
   (defn pipeline-blocking-unordered
     "Like pipeline, for blocking operations."
     ([n to xf from] (pipeline-blocking-unordered n to xf from true))
     ([n to xf from close?] (pipeline-blocking-unordered n to xf from close? nil))
     ([n to xf from close? ex-handler] (pipeline-unordered* n to xf from close? ex-handler :blocking))))

(defn pipeline-async-unordered
  "Takes elements from the from channel and supplies them to the to
  channel, subject to the async function af, with parallelism n. af
  must be a function of two arguments, the first an input value and
  the second a channel on which to place the result(s). The
  presumption is that af will return immediately, having launched some
  asynchronous operation whose completion/callback will put results on
  the channel, then close! it. Outputs will be returned in order
  of completion. By default, the to channel will be closed
  when the from channel closes, but can be determined by the close?
  parameter. Will stop consuming the from channel if the to channel
  closes. See also pipeline, pipeline-blocking."
  ([n to af from] (pipeline-async-unordered n to af from true))
  ([n to af from close?] (pipeline-unordered* n to af from close? nil :async)))
