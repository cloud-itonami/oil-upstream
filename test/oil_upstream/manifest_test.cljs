(ns oil_upstream.manifest-test
  "Cross-file invariants: the manifest, the cljc boundary and the DID document
  are three descriptions of one actor, and nothing checks that they agree.

  These assertions were carried over from `actor-manifest.test.ts`, which has
  never run in this repo — there is no package.json and no vitest here, so the
  suite it belongs to does not exist. ADR-2608260900 retires .ts/.tsx as an
  authoring surface, so they are re-expressed in cljs rather than revived, and
  the checks the TypeScript file could not make (manifest against cell-specs)
  are added, since that is the drift that actually breaks the actor."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [oil_upstream.murakumo :as m]))

(def repo-root
  "The repo root. nbb does not define `js/__filename`, so this is the working
  directory: run the suite from the repo root. Reading is guarded below rather
  than assumed — a suite that quietly reads nothing must not be able to pass."
  (path/resolve (js/process.cwd)))

(defn- read-json
  "Parse a tracked file, or refuse. Not finding the file is a third answer,
  not a clean one — see `run_tests.cljs` for why a pass has a floor under it."
  [rel]
  (let [p (path/join repo-root rel)]
    (when-not (fs/existsSync p)
      (throw (ex-info (str "refusing to report a pass: " rel " is not readable from "
                           repo-root " — run this suite from the repo root")
                      {:path p})))
    (js->clj (js/JSON.parse (fs/readFileSync p "utf8")))))

(def manifest (read-json "actor-manifest.jsonld"))
(def did-doc (read-json ".well-known/did.json"))

(def capability-vocabulary
  "The vocabulary the manifest may draw from. Carried over verbatim from the
  `VP` set in actor-manifest.test.ts."
  #{"graph.query" "graph.write" "graph.vectorSearch" "agent.chat" "agent.invoke"
    "identity.resolve" "browser.fetch" "signal.encrypt" "consent.check"
    "derive:social" "dmn.evaluate" "form.collect"})

(defn- pipelines-of [kind]
  (filter #(= kind (get-in % ["trigger" "type"])) (get manifest "pipelines")))

(defn- nsid->legacy-cell
  "The manifest names an endpoint in dotted NSID form; cell-specs names the same
  endpoint in dashed legacy-cell form. This is the whole mapping between them."
  [nsid]
  (str/replace nsid "." "-"))

(def legacy-cells (set (map :legacy-cell (vals m/cell-specs))))

(def xrpc-nsids (map #(get-in % ["trigger" "nsid"]) (pipelines-of "xrpc")))

(def declared-subscriptions
  "The subscription surface the actor advertises, which is the top-level
  `triggers` block — not the pipeline list. The two are different sets here,
  and only this one is what a relay reads to decide what to deliver."
  (get-in manifest ["triggers" "subscribeRepos" "collections"]))

;; ── the manifest describes the actor this code implements ───────────────────

(deftest manifest-and-cljc-boundary-name-the-same-actor
  (is (= (get manifest "@id") m/actor-did)
      "the DID in the manifest and the DID the effects are signed with must agree"))

(deftest manifest-header-is-well-formed
  (is (= "https://etzhayyim.com/ns/actor/v1" (get manifest "@context")))
  (is (= "did:web:oil-upstream.etzhayyim.com" (get manifest "@id")))
  (is (= "k8s-langserver" (get manifest "runtime")))
  (is (= "01lupstr" (get manifest "nanoid")))
  (is (= "oil-upstream" (get manifest "name"))))

(deftest did-document-is-readable-and-self-consistent
  (let [id (get did-doc "id")]
    (is (string? id))
    (is (str/starts-with? id "did:web:"))
    (is (seq (get did-doc "service")))
    (is (every? #(str/starts-with? % (str id "#"))
                (map #(get % "id") (get did-doc "service")))
        "every service id must be a fragment of the document's own DID")))

(deftest every-declared-capability-is-in-the-vocabulary
  (let [caps (get manifest "capabilities")]
    (is (seq caps) "an actor with no capabilities would make this check vacuous")
    (doseq [c caps]
      (is (contains? capability-vocabulary c) (str c " is not a known capability")))))

(deftest no-pipeline-step-escapes-into-a-custom-function
  (let [steps (mapcat #(get % "steps") (get manifest "pipelines"))]
    (is (seq steps))
    (doseq [s steps]
      (is (not= "custom" (get s "fn"))
          (str "step " (get s "id") " must use a declared capability, not fn:custom"))
      (is (contains? capability-vocabulary (get s "fn"))
          (str "step " (get s "id") " uses " (get s "fn")
               ", which is outside the capability vocabulary")))))

(deftest every-pipeline-step-fn-is-a-capability-the-manifest-declared
  (testing "a step may not reach for authority the actor never claimed"
    (let [declared (set (get manifest "capabilities"))]
      (is (seq declared))
      (doseq [s (mapcat #(get % "steps") (get manifest "pipelines"))]
        (is (contains? declared (get s "fn"))
            (str "step " (get s "id") " uses " (get s "fn")
                 ", which this actor does not declare in `capabilities`"))))))

(deftest pipeline-inventory-is-what-the-actor-was-built-with
  (is (= 8 (count (get manifest "pipelines"))))
  (is (= 4 (count (get manifest "actors"))))
  (let [cron (first (filter #(= "0 */8 * * *" (get-in % ["trigger" "cron"]))
                            (pipelines-of "cron")))]
    (is (some? cron) "the eight-hourly reporting pipeline must exist")
    (is (= 5 (count (get cron "steps"))))
    (is (= "operatorStats" (get (nth (get cron "steps") 2) "id")))))

;; ── the manifest and the cell catalogue must cover each other ───────────────

(deftest every-xrpc-endpoint-has-a-cell-that-can-serve-it
  (is (= 5 (count xrpc-nsids)) "five xrpc endpoints are declared")
  (doseq [n xrpc-nsids]
    (is (contains? legacy-cells (nsid->legacy-cell n))
        (str "the manifest serves " n
             " but cell-specs has no cell named " (nsid->legacy-cell n)))))

(deftest every-subscribed-collection-has-a-cell-that-can-handle-it
  (testing "both subscription surfaces, since a record arriving on either is a write"
    (let [colls (concat declared-subscriptions
                        (mapcat #(get-in % ["trigger" "collections"])
                                (pipelines-of "subscribeRepos")))]
      (is (seq colls) "the actor subscribes to at least one upstream collection")
      (doseq [c colls]
        (is (contains? legacy-cells (nsid->legacy-cell c))
            (str "subscribed to " c " with no cell to handle it"))))))

(deftest the-pipeline-subscriptions-are-a-subset-of-the-advertised-ones
  ;; The two surfaces are allowed to differ in size — the top-level block is
  ;; what a relay reads, the pipeline list is what runs steps — but a pipeline
  ;; triggered by a collection the actor never advertised would never fire.
  (let [advertised (set declared-subscriptions)
        in-pipes   (set (mapcat #(get-in % ["trigger" "collections"])
                                (pipelines-of "subscribeRepos")))]
    (is (= 5 (count advertised)) "five collections are advertised to the relay")
    (is (seq in-pipes))
    (doseq [c in-pipes]
      (is (contains? advertised c)
          (str c " triggers a pipeline but is not advertised, so it will never arrive")))))

(deftest every-required-loop-and-collection-has-a-cell
  ;; `requiredLoops` and `requiredCollections` are obligations the standard
  ;; rule places on this actor. Nothing else in the repo checks that the
  ;; planner can actually plan for them; a loop with no cell simply never runs.
  (let [loops (get manifest "requiredLoops")
        colls (get manifest "requiredCollections")]
    (is (= 4 (count loops)))
    (is (= 2 (count colls)))
    (doseq [l loops]
      (is (contains? legacy-cells l)
          (str "requiredLoops names " l " but no cell implements it")))
    (doseq [c colls]
      (is (contains? legacy-cells (nsid->legacy-cell c))
          (str "requiredCollections names " c " but no cell implements it")))
    (is (= "per-did-kyumei-shinka-autonomy@2026-04-13" (get manifest "standardRule")))
    (is (= "required" (get manifest "standardStatus")))))

(deftest the-cell-catalogue-is-exactly-what-the-manifest-obliges
  ;; The strong form, and the reason the checks above are not enough on their
  ;; own: they are one-directional, so a cell nobody asked for is invisible to
  ;; them. Every cell must trace to an xrpc endpoint, an advertised
  ;; subscription, a required collection or a required loop — and the four
  ;; sources must not overlap, or the count would not be a bijection.
  ;; Measured 2026-08-31: 5 + 5 + 2 + 4 = 16 = (count cell-specs), disjoint.
  (let [xrpc  (set (map nsid->legacy-cell xrpc-nsids))
        subs  (set (map nsid->legacy-cell declared-subscriptions))
        req   (set (map nsid->legacy-cell (get manifest "requiredCollections")))
        loops (set (get manifest "requiredLoops"))
        sets  [["xrpc" xrpc] ["subscriptions" subs]
               ["requiredCollections" req] ["requiredLoops" loops]]
        obliged (reduce into #{} [xrpc subs req loops])]
    (doseq [[na a] sets [nb b] sets :when (not= na nb)]
      (is (empty? (set/intersection a b))
          (str na " and " nb " both claim " (pr-str (set/intersection a b))
               " — the obligation count is no longer a sum")))
    (is (= 16 (count obliged))
        "the manifest places sixteen distinct obligations on the planner")
    (is (= legacy-cells obliged)
        (str "cells with no obligation: " (pr-str (set/difference legacy-cells obliged))
             "; obligations with no cell: " (pr-str (set/difference obliged legacy-cells))))))

(deftest the-documented-xrpc-endpoints-are-still-the-ones-declared
  (let [nsids (set xrpc-nsids)]
    (doseq [n ["com.etzhayyim.apps.oilUpstream.registry.getField"
               "com.etzhayyim.apps.oilUpstream.registry.listFields"
               "com.etzhayyim.apps.oilUpstream.registry.listBasins"
               "com.etzhayyim.apps.oilUpstream.analytics.getProductionPressure"
               "com.etzhayyim.apps.oilUpstream.health"]]
      (is (contains? nsids n) (str n " is no longer served")))))

(deftest cells-that-serve-the-manifest-are-gated-like-every-other-cell
  (testing "an endpoint reachable from outside must not have a shorter gate list"
    (let [served (set (map nsid->legacy-cell xrpc-nsids))]
      (is (seq served))
      (doseq [[k spec] m/cell-specs
              :when (contains? served (:legacy-cell spec))]
        (is (= (vec m/common-gates) (vec (:required-gates spec)))
            (str k " is externally reachable and must require every gate"))
        (is (= :blocked (:status (m/cell-plan k {})))
            (str k " must refuse to serve an unattested request"))))))

(deftest hazard-the-served-did-document-does-not-claim-the-did-this-actor-stamps
  ;; NOT a guarantee -- a recorded divergence.
  ;;
  ;; `.well-known/did.json` identifies itself as
  ;;   did:web:etzhayyim.com:actor:oil-upstream
  ;; (commit f072718, "migrate did:web to etzhayyim.com scheme",
  ;;  ADR-2606231200 addendum 2026-07-02), while the planner and the manifest
  ;; both stamp
  ;;   did:web:oil-upstream.etzhayyim.com
  ;; and the document does not list that DID in alsoKnownAs either -- only the
  ;; `at://` handle built from the same host. A DID document whose `id` is not
  ;; the DID being resolved is not a resolution of that DID, so nothing can
  ;; verify the identity these records are written under from what this repo
  ;; serves. The sibling oil-trading and business-person repos carry the
  ;; identical split from the identical commit, so this is the migration's
  ;; shape, not a typo.
  ;;
  ;; Which side moves is an ownership decision, not this suite's. Pinned so
  ;; that whichever side moves, this test goes red and the other side has to
  ;; move with it. When they agree, delete this test and assert the equality.
  (is (= "did:web:etzhayyim.com:actor:oil-upstream" (get did-doc "id"))
      "the served DID document changed -- reconcile it with m/actor-did and delete this test")
  (is (not= m/actor-did (get did-doc "id"))
      "the divergence is resolved -- replace this test with an equality assertion")
  (is (not (some #{m/actor-did} (get did-doc "alsoKnownAs")))
      "the document now acknowledges the stamped DID -- tighten this test"))
