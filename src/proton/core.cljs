(ns proton.core
  (:require [proton.lib.helpers :as helpers]
            [proton.lib.atom :as atom-env]
            [proton.lib.package_manager :as pm]
            [cljs.nodejs :as node]
            [clojure.string :as string :refer [lower-case upper-case]]
            [proton.layers.base :as layerbase]
            [proton.layers.core.core :as core-layer]
            [proton.layers.git.core :as git-layer]))

(node/enable-util-print!)

;; reference to atom shell API
(def ashell (node/require "atom"))

;; js/atom is not the same as require 'atom'.
(def commands (.-commands js/atom))
(def workspace (.-workspace js/atom))
(def keymaps (.-keymaps js/atom))
(def views (.-views js/atom))

;; get atom.CompositeDisposable so we can work with it
(def composite-disposable (.-CompositeDisposable ashell))

;; Initialise new composite-disposable so we can add stuff to it later
(def subscriptions (new composite-disposable))

(def command-tree (atom {}))
(def required-packages (atom []))

(def current-chain (atom []))
(defn ^:export chain [e]
  (let [letter (helpers/extract-keyletter-from-event e)
        key-code (helpers/extract-keycode-from-event e)]
      ;; check for ESC key
      (if (= key-code 27)
        (atom-env/deactivate-proton-mode!)
        (do
          ;; append new key to chain
          (swap! current-chain conj letter)
          ;; check if the current character sequence is a action
          (if (helpers/is-action? @command-tree @current-chain)
            (atom-env/eval-action! @command-tree @current-chain)
            ;; if not, continue chaining
            (let [extracted-chain (get-in @command-tree @current-chain)]
              (if (nil? extracted-chain)
                (atom-env/deactivate-proton-mode!)
                (atom-env/update-bottom-panel (helpers/tree->html extracted-chain)))))))))

(def enabled-layers [:core :git])
(defn init-layers []
  (atom-env/insert-process-step (str "Initialising layers: " enabled-layers))
  (println (str "Initialising layers: " enabled-layers))
  (doseq [layer enabled-layers]
    (println (layerbase/get-packages (keyword layer)))
    (swap! required-packages #(into [] (concat % (layerbase/get-packages (keyword layer)))))
    (swap! command-tree merge (layerbase/get-keybindings (keyword layer))))

  (println (str "Collected packages: " @ required-packages))
  (doseq [package @required-packages]
    (let [package (name package)]
      (if (not (pm/is-installed? package))
        (do
          (atom-env/insert-process-step (str "Installing " package))
          (println (str "Installing " package))
          (pm/install-package package)))))
  true)

(defn on-space []
  (reset! current-chain [])
  (atom-env/update-bottom-panel (helpers/tree->html @command-tree))
  (atom-env/activate-proton-mode!))

(defn ^:export activate [state]
  (.setTimeout js/window #(do (init-layers)) 10000)

  (.onDidMatchBinding keymaps #(if (= "space" (.-keystrokes %)) (on-space)))
  (.add subscriptions (.add commands "atom-text-editor.proton-mode" "proton:chain" chain)))

(defn ^:export deactivate [] (.log js/console "deactivating..."))

(defn noop [] nil)
(set! *main-cli-fn* noop)

;; We need to set module.exports to our core class.
;; Atom is using Proton.activate on this
(aset js/module "exports" proton.core)
