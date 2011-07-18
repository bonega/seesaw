;  Copyright (c) Dave Ray, 2011. All rights reserved.

;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this 
;   distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:doc 
"Core functions and macros for Seesaw. Although there are many more 
  Seesaw namespaces, usually what you want is in here. Most functions 
  in other namespaces have a core wrapper which adds additional 
  capability or makes them easier to use."
      :author "Dave Ray"}
  seesaw.core
  (:use [seesaw util meta to-widget])
  (:require [seesaw color font border invoke timer selection 
             event selector icon action cells table graphics cursor])
  (:import [javax.swing 
             SwingConstants UIManager ScrollPaneConstants
             BoxLayout
             JDialog JFrame JComponent Box JPanel JScrollPane JSplitPane JToolBar JTabbedPane
             JLabel JTextField JTextArea JTextPane
             AbstractButton JButton ButtonGroup
             JOptionPane]
           [javax.swing.text JTextComponent StyleConstants]
           [java.awt Component FlowLayout BorderLayout GridLayout 
              GridBagLayout GridBagConstraints
              Dimension]))

(declare to-widget)
(declare popup-option-handler)

(def #^{:macro true :doc "Alias for seesaw.invoke/invoke-now"} invoke-now #'seesaw.invoke/invoke-now)
(def #^{:macro true :doc "Alias for seesaw.invoke/invoke-later"} invoke-later #'seesaw.invoke/invoke-later)

(defn native!
  "Set native look and feel and other options to try to make things look right.
  This function must be called very early, like before any other Seesaw or Swing
  calls!
  
  Note that on OSX, you can set the application name in the menu bar (usually
  displayed as the main class name) by setting the -Xdock:<name-of-your-app>
  parameter to the JVM at startup. Sorry, I don't know of a way to do it 
  dynamically.

  See:

  http://developer.apple.com/library/mac/#documentation/Java/Conceptual/Java14Development/07-NativePlatformIntegration/NativePlatformIntegration.html
  "
  []
  (System/setProperty "apple.laf.useScreenMenuBar" "true")
  (UIManager/setLookAndFeel (UIManager/getSystemLookAndFeelClassName)))

(defn assert-ui-thread
  "Verify that the current thread is the Swing UI thread and throw
  IllegalStateException if it's not. message is included in the exception
  message.

  Returns nil.

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/SwingUtilities.html#isEventDispatchThread%28%29
  "
  [message]
  (when-not (javax.swing.SwingUtilities/isEventDispatchThread)
    (throw (IllegalStateException. 
             (str "Expected UI thread, but got '"
                  (.. (Thread/currentThread) getName)
                  "' : "
                  message)))))

; TODO make a macro for this. There's one in contrib I think, but I don't trust contrib.

; alias timer/timer for convenience
(def ^{:doc (str "Alias of seesaw.timer/timer:\n" (:doc (meta #'seesaw.timer/timer)))} timer seesaw.timer/timer)

; alias event/listen for convenience
(def ^{:doc (str "Alias of seesaw.event/listen:\n" (:doc (meta #'seesaw.event/listen)))} listen seesaw.event/listen)

; alias action/action for convenience
(def ^{:doc (str "Alias of seesaw.action/action:\n" (:doc (meta #'seesaw.action/action)))} action seesaw.action/action)


; TODO protocol or whatever when needed
(defn- to-selectable
  [target]
  (cond
    (instance? javax.swing.ButtonGroup target) target
    :else (to-widget target)))

(defn selection 
  "Gets the selection of a widget. target is passed through (to-widget)
  so event objects can also be used. The default behavior is to return
  a *single* selection value, even if the widget supports multiple selection.
  If there is no selection, returns nil.

  options is an option map which supports the following flags:

    multi? - If true the return value is a seq of selected values rather than
      a single value.

  Examples:

  (def t (table))
  (listen t :selection 
    (fn [e]
      (let [selected-rows (selection t {:multi? true})]
        (println \"Currently selected rows: \" selected-rows))))
  
  See:
    (seesaw.core/selection!)
    (seesaw.selection/selection)
  "
  ([target] (selection target {}))
  ([target options] (seesaw.selection/selection (to-selectable target) options)))

(defn selection!
  "Sets the selection on a widget. target is passed through (to-widget)
  so event objects can also be used. The arguments are the same as
  (selection). By default, new-selection is a single new selection value.
  If new-selection is nil, the selection is cleared.

  options is an option map which supports the following flags:

    multi? - if true new-expression is a list of values to selection,
      the same as the list returned by (selection).

  Always returns target.

  See: 
    (seesaw.core/selection)
    (seesaw.selection/selection!)
  "
  ([target new-selection] (selection! target {} new-selection))
  ([target opts new-selection] (seesaw.selection/selection! (to-selectable target) opts new-selection)))

(def icon seesaw.icon/icon)
(def ^{:private true} make-icon icon)

;*******************************************************************************
; Widget coercion prototcol

(defn ^java.awt.Component to-widget 
  "Try to convert the input argument to a widget based on the following rules:

    nil -> nil
    java.awt.Component -> return argument unchanged
    java.util.EventObject -> return the event source
    java.awt.Dimension -> return Box/createRigidArea
    java.swing.Action    -> return a button using the action
    :separator -> create a horizontal JSeparator
    :fill-h -> Box/createHorizontalGlue
    :fill-v -> Box/createVerticalGlue
    [:fill-h n] -> Box/createHorizontalStrut with width n
    [:fill-v n] -> Box/createVerticalStrut with height n
    [width :by height] -> create rigid area with given dimensions
    A java.net.URL -> a label with the image located at the url
    Anything else -> a label with the text from passing the object through str

   If create? is false, will return nil for all rules (see above) that
   would create a new widget. The default value for create? is false
   to avoid inadvertently creating widgets all over the place.

  See:
    (seeseaw.to-widget)
  "
  ([v]         (to-widget v false))
  ([v create?] (when v (to-widget* v create?))))

;*******************************************************************************
; Widget construction stuff

(defmacro with-widget
  "This macro allows a Seesaw widget 'constructor' function to be applied to
  a sub-class of the widget type it usually produces. For example (listbox)
  always returns an instance of exactly JList. Suppose you're using SwingX
  and want to use the Seesaw goodness of (listbox), but want to get a 
  JXList. That's what this macro is for:

    (with-widget org.jdesktop.swingx.JXList
      (listbox :id :my-list :model ...))

  This will return a new instance of JXList, with the usual Seesaw listbox
  options applied.

  The factory argument can be one of the following:

    A class literal - .newInstance is used to create a new instance of
                      the class.

    A function - The function is called with no arguments. It should
                 return a sub-class of the expected class.

    An existing instance - The instance is modified and returned.

  If the instance in any of these cases is not a sub-class of the
  type usually created by the constructor function, an IllegalArgumentException
  is thrown. For example:

    (with-widget JLabel (listbox ...)) ==> IllegalArgumentException

  Returns a fully initialized instance of the class created by the
  provided factory.
  "
  [factory form]
  ; Just tack on an additional ::with parameter, used by (construct)
  ; and otherwise ignored. Originally this was a thread binding, but
  ; that failed when there were more widget constructors embedded in 
  ; the form.
  `~(concat form [::with factory]))

(declare construct-impl)
(defn- construct 
  "Use the ::with option to create a new widget, ensuring the
   result is consistent with the given expected class. If there's no 
   ::with option, just fallback to a default instance of the expected
   class.
  
  Returns an instance of the expected class, or throws IllegalArgumentException
  if the result using ::with isn't consistent with expected-class."
  ([factory-class opts] 
    (construct-impl 
      (or (::with (if (map? opts) opts (apply hash-map opts))) factory-class)
      factory-class)))

(defn- construct-impl
  ([factory expected-class]
    (cond
      (instance? expected-class factory) 
        factory

      (class? factory) 
        (construct-impl #(.newInstance ^Class factory) expected-class)

      (fn? factory)
        (let [result (factory)]
          (if (instance? expected-class result)
            result
            (throw (IllegalArgumentException. 
                     (str (class result) " is not an instance of " expected-class)))))

      :else 
        (throw (IllegalArgumentException. 
                 (str "Factory or instance " factory 
                      " is not consistent with expected type " expected-class))))))

;*******************************************************************************
; Generic widget stuff

(declare show-modal-dialog)
(declare to-root)
(declare is-modal-dialog?)

(defprotocol Showable
  (visible! [this v])
  (visible? [this]))

(extend-protocol Showable
  java.awt.Component
    (visible! [this v] (doto this (.setVisible (boolean v))))
    (visible? [this] (.isVisible this))
  java.awt.Dialog
    (visible! [this v]
      (if (and v (is-modal-dialog? this))
        (show-modal-dialog this)
        (doto this (.setVisible false))))
  java.util.EventObject
    (visible! [this v] (visible! (.getSource this) v))
    (visible? [this] (visible? (.getSource this))))

(defn show!
  "Show a frame, dialog or widget.
   
   If target is a modal dialog, the call will block and show! will return the
   dialog's result. See (seesaw.core/return-from-dialog).

   Returns its input.

  See:
    http://download.oracle.com/javase/6/docs/api/java/awt/Window.html#setVisible%28boolean%29
  "
  [targets]
  (if (is-modal-dialog? targets)
    (visible! targets true)
    (do
      (doseq [target (to-seq targets)]
        (visible! target true))
      targets)))

(defn hide!
  "Hide a frame, dialog or widget.
   
   Returns its input.

  See:
    http://download.oracle.com/javase/6/docs/api/java/awt/Window.html#setVisible%28boolean%29
  "
  [targets]
  (doseq [target (to-seq targets)]
    (visible! target false))
  targets)

(defn pack!
  "Pack a frame or window, causing it to resize to accommodate the preferred
  size of its contents.

  Returns its input.

  See:
    http://download.oracle.com/javase/6/docs/api/java/awt/Window.html#pack%28%29 
  "
  [targets]
  (doseq [#^java.awt.Window target (map to-root (to-seq targets))]
    (.pack target))
  targets)

(defn dispose!
  "Dispose the given frame, dialog or window. target can be anything that can
  be converted to a root-level object with (to-root).

  Returns its input. 

  See:
   http://download.oracle.com/javase/6/docs/api/java/awt/Window.html#dispose%28%29 
  "
  [targets]
  (doseq [#^java.awt.Window target (map to-root (to-seq targets))]
    (.dispose target))
  targets)

(defn repaint!
  "Request a repaint of one or a list of widget-able things.

  Example:

    ; Repaint just one widget
    (repaint! my-widget)

    ; Repaint all widgets in a hierarcy
    (repaint! (select [:*] root))

  Returns targets.
  "
  [targets]
  (doseq [^java.awt.Component target (map to-widget (to-seq targets))]
    (.repaint target))
  targets)

(defn- handle-structure-change [^JComponent container]
  "Helper. Revalidate and repaint a container after structure change"
  (doto container
    .revalidate
    .repaint))

;*******************************************************************************
; move!

(defprotocol ^{:private true} Movable
  (move-to! [this x y])
  (move-by! [this dx dy])
  (move-to-front! [this])
  (move-to-back! [this]))

; A protocol impl can't have a partial implementation, so these are
; here for re-use.
(defn- move-component-to! [^java.awt.Component this x y]
  (let [old-loc (.getLocation this)
        x (or x (.x old-loc))
        y (or y (.y old-loc))]
    (doto this (.setLocation x y))))

(defn- move-component-by! [^java.awt.Component this dx dy]
  (let [old-loc (.getLocation this)
        x (.x old-loc)
        y (.y old-loc)]
    (doto this (.setLocation (+ x dx) (+ y dy)))))

(extend-protocol Movable
  java.util.EventObject
    (move-to! [this x y]   (move-to! (.getSource this) x y))
    (move-by! [this dx dy] (move-by! (.getSource this) dx dy))
    (move-to-front! [this] (move-to-front! (.getSource this)))
    (move-to-back! [this]  (move-to-back! (.getSource this)))
  java.awt.Component
    (move-to! [this x y]   (move-component-to! this x y))
    (move-by! [this dx dy] (move-component-by! this dx dy))
    (move-to-front! [this] 
      (do
        (doto (.getParent this)
          (.setComponentZOrder this 0)
          handle-structure-change)
        this))
    (move-to-back! [this]
      (let [parent (.getParent this)
            n      (.getComponentCount parent)]
        (doto parent 
          (.setComponentZOrder this (dec n))
          handle-structure-change)
        this))
  java.awt.Window
    (move-to! [this x y]   (move-component-to! this x y))
    (move-by! [this dx dy] (move-component-by! this dx dy))
    (move-to-front! [this] (doto this .toFront))
    (move-to-back! [this] (doto  this .toBack)))
    
(defn move!
  "Move a widget relatively or absolutely. target is a 'to-widget'-able object,
  type is :by or :to, and loc is a two-element vector or instance of 
  java.awt.Point. The how type parameter has the following interpretation:

    :to The absolute position of the widget is set to the given point
    :by The position of th widget is adjusted by the amount in the given point
        relative to its current position.
    :to-front Move the widget to the top of the z-order in its parent.

  Returns target.

  Examples:

    ; Move x to the point (42, 43)
    (move! x :to [42, 43])

    ; Move x to y position 43 while keeping x unchanged
    (move! x :to [:*, 43])

    ; Move x relative to its current position. Assume initial position is (42, 43).
    (move! x :by [50, -20])
    ; ... now x's position is [92, 23]

  Notes: 
    For widgets, this function will generally only have an affect on widgets whose container
    has a nil layout! This function has similar functionality to the :bounds
    and :location options, but is a little more flexible and readable.

  See:
    (seesaw.core/xyz-panel)
    http://download.oracle.com/javase/6/docs/api/java/awt/Component.html#setLocation(int, int)
  "
  [target how & [loc]]
  (check-args (#{:by :to :to-front :to-back} how) "Expected :by, :to, :to-front, :to-back in move!")
  (case how
    (:to :by)
      (let [[x y] (cond 
                    (instance? java.awt.Point loc) (let [^java.awt.Point loc loc] [(.x loc) (.y loc)]) 
                    (instance? java.awt.Rectangle loc) (let [^java.awt.Rectangle loc loc] [(.x loc) (.y loc)])
                    (= how :to) (replace {:* nil} loc)
                    :else loc)]
        (case how
          :to      (move-to! target x y)
          :by      (move-by! target x y)))
    :to-front
      (move-to-front! target)
    :to-back
      (move-to-back! target)))

(defn width [w]
  "Returns the width of the given widget in pixels"
  (.getWidth (to-widget w)))

(defn height [w]
  "Returns the height of the given widget in pixels"
  (.getHeight (to-widget w)))

(defn- add-widget 
  ([c w] (add-widget c w nil))
  ([^java.awt.Container c w constraint] 
    (let [w* (to-widget w true)]
      (check-args (not (nil? w*)) (str "Can't add nil widget. Original was (" w ")"))
      (.add c w* constraint)
      w*)))

(defn- add-widgets
  [^java.awt.Container c ws]
  (.removeAll c)
  (doseq [w ws]
    (add-widget c w))
  (handle-structure-change c))

(defn id-of 
  "Returns the id of the given widget if the :id property was specified at
   creation. The widget parameter is passed through (to-widget) first so
   events and other objects can also be used. The id is always returned as
   a string, even it if was originally given as a keyword.

  Returns the id as a string, or nil.
  
  See:
    (seesaw.core/select).
  "
  [w] 
  (seesaw.selector/id-of (to-widget w)))

(def ^{:doc "Deprecated. See (seesaw.core/id-of)"} id-for id-of)

(def ^{:private true} h-alignment-table 
  (constant-map SwingConstants :left :right :leading :trailing :center ))

(def ^{:private true} v-alignment-table
  (constant-map SwingConstants :top :center :bottom))

(let [table (constant-map SwingConstants :horizontal :vertical)]
  (defn- orientation-table [v]
    (or (table v)
        (throw (IllegalArgumentException. 
                (str ":orientation must be either :horizontal or :vertical. Got " v " instead."))))))

(defn- bounds-option-handler [^java.awt.Component target v]
  (cond
    ; TODO to-rect protocol?
    (= :preferred v)
      (bounds-option-handler target (.getPreferredSize target))
    (instance? java.awt.Rectangle v) (.setBounds target v)
    (instance? java.awt.Dimension v) 
      (let [loc (.getLocation target)
            v   ^java.awt.Dimension v]
        (.setBounds target (.x loc) (.y loc) (.width v) (.height v)))
    :else
      (let [old       (.getBounds target)
            [x y w h] (replace {:* nil} v)]
        (.setBounds target 
            (or x (.x old))     (or y (.y old)) 
            (or w (.width old)) (or h (.height old))))))


;*******************************************************************************
; Widget configuration stuff
(defprotocol ConfigureWidget (config* [target args]))

(defn config!
  "Applies properties in the argument list to one or more targets. For example:

    (config! button1 :enabled? false :text \"I' disabled\")

  or:

    (config! [button1 button2] :enabled? false :text \"We're disabled\")
 
  Targets may be actual widgets, or convertible to widgets with (to-widget).
  For example, the target can be an event object.

  Returns the input targets."
  [targets & args]
  (doseq [target (to-seq targets)]
    (config* target args))
  targets)

;*******************************************************************************
; Default options

; We define a few protocols for various setters that existing on multiple Swing
; types, but don't have a common interface. This lets us avoid reflection.
(defprotocol ^{:private true} SetIcon (set-icon [this v]))

(extend-protocol SetIcon
  javax.swing.JLabel (set-icon [this v] (.setIcon this (make-icon v)))
  javax.swing.AbstractButton (set-icon [this v] (.setIcon this (make-icon v))))

(defprotocol ^{:private true} Text 
  (set-text [this v])
  (get-text [this]))

(extend-protocol Text
  javax.swing.JLabel 
    (set-text [this v] (.setText this (str v)))
    (get-text [this] (.getText this))
  javax.swing.AbstractButton 
    (set-text [this v] (.setText this (str v)))
    (get-text [this] (.getText this))
  javax.swing.text.AbstractDocument 
    (set-text [this v] (.replace this 0 (.getLength this) (str v) nil))
    (get-text [this] (.getText this 0 (.getLength this)))
  javax.swing.text.JTextComponent 
    (set-text [this v] (.setText this (str v)))
    (get-text [this] (.getText this)))

(defprotocol ^{:private true} SetAction (set-action [this v]))
(extend-protocol SetAction
  javax.swing.AbstractButton (set-action [this v] (.setAction this v))
  javax.swing.JTextField (set-action [this v] (.setAction this v))
  javax.swing.JComboBox (set-action [this v] (.setAction this v)))


(declare paint-option-handler)
(def ^{:private true} default-options {
  ::with       (fn [c v]) ; ignore ::with option inserted by (with-widget)
  :id          seesaw.selector/id-of!
  :class       seesaw.selector/class-of!
  :listen      #(apply seesaw.event/listen %1 %2)
  :opaque?     #(.setOpaque ^JComponent %1 (boolean %2))
  :enabled?    #(.setEnabled ^java.awt.Component %1 (boolean %2))
  :focusable?  #(.setFocusable ^java.awt.Component %1 (boolean %2))
  :background  #(do
                  (.setBackground ^JComponent %1 (seesaw.color/to-color %2))
                  (.setOpaque ^JComponent %1 true))
  :foreground  #(.setForeground ^JComponent %1 (seesaw.color/to-color %2))
  :border      #(.setBorder ^JComponent %1 (seesaw.border/to-border %2))
  :font        #(.setFont ^JComponent %1 (seesaw.font/to-font %2))
  :tip         #(.setToolTipText ^JComponent %1 (str %2))
  :cursor      #(.setCursor ^java.awt.Component %1 (apply seesaw.cursor/cursor (to-seq %2)))
  :visible?    #(.setVisible ^java.awt.Component %1 (boolean %2))
  :items       #(add-widgets %1 %2)
  :preferred-size #(.setPreferredSize ^JComponent %1 (to-dimension %2))
  :minimum-size   #(.setMinimumSize ^JComponent %1 (to-dimension %2))
  :maximum-size   #(.setMaximumSize ^JComponent %1 (to-dimension %2))
  :size           #(let [d (to-dimension %2)]
                     (doto ^JComponent %1 
                       (.setPreferredSize d)
                       (.setMinimumSize d)
                       (.setMaximumSize d)))
  :location   #(move! %1 :to %2)
  :bounds     bounds-option-handler
  :popup      #(popup-option-handler %1 %2)
  :paint      #(paint-option-handler %1 %2)

  ; TODO reflection - these properties aren't generally applicable to
  ; any kind of widget so move them out
  :icon        set-icon
  :action      set-action
  :text        set-text
  :model       #(.setModel %1 %2)
})

(extend-protocol ConfigureWidget
  java.util.EventObject
    (config* [target args] (config* (to-widget target false) args))

  java.awt.Component
    (config* [target args] (reapply-options target args default-options))

  javax.swing.JComponent
    (config* [target args] (reapply-options target args default-options))

  javax.swing.Action
    (config* [target args] (reapply-options target args default-options))

  java.awt.Window
    (config* [target args] (reapply-options target args default-options)))

(defn apply-default-opts
  "only used in tests!"
  ([p] (apply-default-opts p {}))
  ([^javax.swing.JComponent p {:as opts}]
    (apply-options p opts default-options)))

;*******************************************************************************
; ToDocument

; TODO ToDocument protocol
(defn ^javax.swing.text.AbstractDocument to-document
  [v]
  (let [w (to-widget v)]
    (cond
      (instance? javax.swing.text.Document v)       v
      (instance? javax.swing.event.DocumentEvent v) (.getDocument ^javax.swing.event.DocumentEvent v)
      (instance? JTextComponent w)                  (.getDocument ^JTextComponent w))))

;*******************************************************************************
; Abstract Panel
(defn abstract-panel
  [layout custom-opts opts]
  (let [^JPanel p (construct JPanel opts)
        layout (if (fn? layout) (layout p) layout)]
    (doto p
      (.setLayout layout)
      (apply-options opts (merge default-options custom-opts)))))

;*******************************************************************************
; Null Layout

(defn xyz-panel
  "Creates a JPanel on which widgets can be positioned arbitrarily by client
  code. No layout manager is installed. 

  Initial widget positions can be given with their :bounds property. After
  construction they can be moved with the (seesaw.core/move!) function.

  Examples:

    ; Create a panel with a label positions at (10, 10) with width 200 and height 40.
    (xyz-panel :items [(label :text \"The Black Lodge\" :bounds [10 10 200 40]))

    ; Move a widget up 50 pixels and right 25 pixels
    (move! my-label :by [25 -50])

  Notes:
    This function is compatible with (seesaw.core/with-widget).

  See:
    (seesaw.core/move!)
  "
  { :seesaw {:class 'javax.swing.JPanel }}
  [& opts]
  (abstract-panel nil {} opts))

;*******************************************************************************
; Border Layout

(def ^{:private true}  border-layout-dirs 
  (constant-map BorderLayout :north :south :east :west :center))

(defn- border-panel-items-handler
  [panel items]
  (doseq [[w dir] items]
    (add-widget panel w (border-layout-dirs dir))))

(def ^{:private true} border-layout-options 
  (merge
    { :hgap  #(.setHgap ^BorderLayout (.getLayout ^java.awt.Container %1) %2)
      :vgap  #(.setVgap ^BorderLayout (.getLayout ^java.awt.Container %1) %2) 
      :items border-panel-items-handler }
    (reduce 
      (fn [m [k v]] (assoc m k #(add-widget %1 %2 v)))
      {} 
      border-layout-dirs)))

(defn border-panel
  "Create a panel with a border layout. In addition to the usual options, 
  supports:
    
    :north  widget for north position (passed through to-widget)
    :south  widget for south position (passed through to-widget)
    :east   widget for east position (passed through to-widget)
    :west   widget for west position (passed through to-widget)
    :center widget for center position (passed through to-widget)
 
    :hgap   horizontal gap between widgets
    :vgap   vertical gap between widgets

  The :items option is a list of widget/direction pairs which can be used
  if you don't want to use the direction options directly. For example, both
  of these are equivalent:

    (border-panel :north \"North\" :south \"South\")

  is the same as:

    (border-panel :items [[\"North\" :north] [\"South\" :south]])

  This is for consistency with other containers.

  See:
  
    http://download.oracle.com/javase/6/docs/api/java/awt/BorderLayout.html
  "
  { :seesaw {:class 'javax.swing.JPanel }}
  [& opts]
  (abstract-panel (BorderLayout.) border-layout-options opts))

;*******************************************************************************
; Card

(def ^{:private true} card-panel-options {
  :items (fn [panel items]
           (doseq [[w id] items]
             (add-widget panel w (name id))))
  :hgap #(.setHgap ^java.awt.CardLayout (.getLayout ^java.awt.Container %1) %2) 
  :vgap #(.setVgap ^java.awt.CardLayout (.getLayout ^java.awt.Container %1) %2) 
})

(defn card-panel
  "Create a panel with a card layout. Options:

    :items A list of pairs with format [widget, identifier]
           where identifier is a string or keyword.

  See: 

    (seesaw.core/show-card!)
    http://download.oracle.com/javase/6/docs/api/java/awt/CardLayout.html
  "
  { :seesaw {:class 'javax.swing.JPanel }}
  [& opts]
  (abstract-panel (java.awt.CardLayout.) card-panel-options opts))

(defn show-card! 
  "Show a particular card in a card layout. id can be a string or keyword.
   panel is returned.

  See:

    (seesaw.core/card-panel)
    http://download.oracle.com/javase/6/docs/api/java/awt/CardLayout.html
  "
  [^java.awt.Container panel id]
  (.show ^java.awt.CardLayout (.getLayout panel) panel (name id))
  panel)

;*******************************************************************************
; Flow

(def ^{:private true} flow-align-table
  (constant-map FlowLayout :left :right :leading :trailing :center))

(def ^{:private true} flow-panel-options {
  :hgap #(.setHgap ^FlowLayout (.getLayout ^java.awt.Container %1) %2)
  :vgap #(.setVgap ^FlowLayout (.getLayout ^java.awt.Container %1) %2)
  :align #(.setAlignment ^FlowLayout (.getLayout ^java.awt.Container %1) (get flow-align-table %2 %2))
  :align-on-baseline? #(.setAlignOnBaseline ^FlowLayout (.getLayout ^java.awt.Container %1) (boolean %2))
})

(defn flow-panel
  "Create a panel with a flow layout. Options:

    :items  List of widgets (passed through to-widget)
    :hgap   horizontal gap between widgets
    :vgap   vertical gap between widgets
    :align  :left, :right, :leading, :trailing, :center
    :align-on-baseline? 

  See http://download.oracle.com/javase/6/docs/api/java/awt/FlowLayout.html 
  "
  { :seesaw {:class 'javax.swing.JPanel }}
  [& opts]
  (abstract-panel (FlowLayout.) flow-panel-options opts))

;*******************************************************************************
; Boxes

(def ^{:private true} box-layout-dir-table {
  :horizontal BoxLayout/X_AXIS 
  :vertical   BoxLayout/Y_AXIS 
})

(defn box-panel
  { :seesaw {:class 'javax.swing.JPanel }}
  [dir & opts]
  (abstract-panel #(BoxLayout. % (dir box-layout-dir-table)) {} opts))

(defn horizontal-panel 
  "Create a panel where widgets are arranged horizontally. Options:

    :items List of widgets (passed through to-widget)

  See http://download.oracle.com/javase/6/docs/api/javax/swing/BoxLayout.html 
  "
  { :seesaw {:class 'javax.swing.JPanel }}
  [& opts] (apply box-panel :horizontal opts))

(defn vertical-panel
  "Create a panel where widgets are arranged vertically Options:

    :items List of widgets (passed through to-widget)

  See http://download.oracle.com/javase/6/docs/api/javax/swing/BoxLayout.html 
  "
  { :seesaw {:class 'javax.swing.JPanel }}
  [& opts] (apply box-panel :vertical opts))

;*******************************************************************************
; Grid

(def ^{:private true} grid-panel-options {
  :hgap #(.setHgap ^GridLayout (.getLayout ^java.awt.Container %1) %2)
  :vgap #(.setVgap ^GridLayout (.getLayout ^java.awt.Container %1) %2)
})

(defn grid-panel
  "Create a panel where widgets are arranged horizontally. Options:
    
    :rows    Number of rows, defaults to 0, i.e. unspecified.
    :columns Number of columns.
    :items   List of widgets (passed through to-widget)
    :hgap    horizontal gap between widgets
    :vgap    vertical gap between widgets

  Note that it's usually sufficient to just give :columns and ignore :rows.

  See http://download.oracle.com/javase/6/docs/api/java/awt/GridLayout.html 
  "
  { :seesaw {:class 'javax.swing.JPanel }}
  [& {:keys [rows columns] 
      :as opts}]
  (let [columns (or columns (if rows 0 1))
        layout  (GridLayout. (or rows 0) columns 0 0)]
    (abstract-panel layout 
                    (merge default-options grid-panel-options)
                    (dissoc opts :rows :columns))))

;*******************************************************************************
; Form aka GridBagLayout

(def ^{:private true} gbc-fill 
  (constant-map GridBagConstraints :none :both :horizontal :vertical))

(def ^{:private true} gbc-grid-xy (constant-map GridBagConstraints :relative))

(def ^{:private true} gbc-grid-wh
  (constant-map GridBagConstraints :relative :remainder))

(def ^{:private true} gbc-anchors 
  (constant-map GridBagConstraints
    :north :south :east :west 
    :northwest :northeast :southwest :southeast :center
    
    :page-start :page-end :line-start :line-end 
    :first-line-start :first-line-end :last-line-start :last-line-end
  
    :baseline :baseline-leading :baseline-trailing
    :above-baseline :above-baseline-leading :above-baseline-trailing
    :below-baseline :below-baseline-leading :below-baseline-trailing)) 

(defn- gbc-grid-handler [^GridBagConstraints gbc v]
  (let [x (.gridx gbc)
        y (.gridy gbc)]
    (condp = v
      :next (set! (. gbc gridx) (inc x))
      :wrap    (do 
                 (set! (. gbc gridx) 0)
                 (set! (. gbc gridy) (inc y))))
    gbc))

(def ^{:private true} grid-bag-constraints-options {
  :grid       gbc-grid-handler
  :gridx      #(set! (. ^GridBagConstraints %1 gridx)      (get gbc-grid-xy %2 %2))
  :gridy      #(set! (. ^GridBagConstraints %1 gridy)      (get gbc-grid-xy %2 %2))
  :gridwidth  #(set! (. ^GridBagConstraints %1 gridwidth) (get gbc-grid-wh %2 %2))
  :gridheight #(set! (. ^GridBagConstraints %1 gridheight) (get gbc-grid-wh %2 %2))
  :fill       #(set! (. ^GridBagConstraints %1 fill)       (get gbc-fill %2 %2))
  :ipadx      #(set! (. ^GridBagConstraints %1 ipadx)      %2)
  :ipady      #(set! (. ^GridBagConstraints %1 ipady)      %2)
  :insets     #(set! (. ^GridBagConstraints %1 insets)     %2)
  :anchor     #(set! (. ^GridBagConstraints %1 anchor)     (gbc-anchors %2))
  :weightx    #(set! (. ^GridBagConstraints %1 weightx)    %2)
  :weighty    #(set! (. ^GridBagConstraints %1 weighty)    %2)
})

(defn realize-grid-bag-constraints
  "*INTERNAL USE ONLY. DO NOT USE.*

  Turn item specs into [widget constraint] pairs by successively applying
  options to GridBagConstraints"
  [items]
  (second
    (reduce
      (fn [[^GridBagConstraints gbcs result] [widget & opts]]
        (apply-options gbcs opts grid-bag-constraints-options)
        (vector (.clone gbcs) (conj result [widget gbcs]))) 
      [(GridBagConstraints.) []]
      items)))

(defn- add-grid-bag-items
  [^java.awt.Container panel items]
  (.removeAll panel)
  (doseq [[widget constraints] (realize-grid-bag-constraints items)]
    (when widget
      (add-widget panel widget constraints)))
  (handle-structure-change panel))

(def ^{:private true} form-panel-options {
  :items add-grid-bag-items
})

(defn form-panel
  "*Don't use this. GridBagLaout is an abomination* I suggest using Seesaw's
  MigLayout (seesaw.mig) or JGoogies Forms (seesaw.forms) support instead.

  A panel that uses a GridBagLayout. Also aliased as (grid-bag-panel) if you
  want to be reminded of GridBagLayout. The :items property should be a list
  of vectors of the form:

      [widget & options]

  where widget is something widgetable and options are key/value pairs
  corresponding to GridBagConstraints fields. For example:

    [[\"Name\"         :weightx 0]
     [(text :id :name) :weightx 1 :fill :horizontal]]

  This creates a label/field pair where the field expands.
  
  See http://download.oracle.com/javase/6/docs/api/java/awt/GridBagLayout.html 
  "
  { :seesaw {:class 'javax.swing.JPanel }}
  [& opts]
  (abstract-panel (GridBagLayout.) form-panel-options opts))

(def grid-bag-panel form-panel)

;*******************************************************************************
; Labels

(def ^{:private true} label-options {
  :halign      #(.setHorizontalAlignment ^javax.swing.JLabel %1 (h-alignment-table %2))
  :valign      #(.setVerticalAlignment ^javax.swing.JLabel %1 (v-alignment-table %2)) 
  :h-text-position #(.setHorizontalTextPosition ^javax.swing.JLabel %1 (h-alignment-table %2))
  :v-text-position #(.setVerticalTextPosition ^javax.swing.JLabel %1 (v-alignment-table %2))
})

(defn label 
  "Create a label. Supports all default properties. Can take two forms:

      (label \"My Label\")  ; Single text argument for the label

  or with full options:

      (label :id :my-label :text \"My Label\" ...)

  Additional options:

    :h-text-position Horizontal text position, :left, :right, :center, etc.
    :v-text-position Horizontal text position, :top, :center, :bottom, etc.

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JLabel.html
  "
  { :seesaw {:class 'javax.swing.JLabel }}
  [& args]
  (case (count args) 
    0 (label :text "")
    1 (label :text (first args))
    (apply-options (construct JLabel args) args (merge default-options label-options))))


;*******************************************************************************
; Buttons

(def ^{:private true} button-group-options {
  :buttons #(doseq [b %2] (.add ^javax.swing.ButtonGroup %1 b))
})

(defn button-group
  "Creates a button group, i.e. a group of mutually exclusive toggle buttons, 
  radio buttons, toggle-able menus, etc. Takes the following options:

    :buttons A sequence of buttons to include in the group. They are *not*
             passed through (to-widget), i.e. they must be button or menu 
             instances.

  The mutual exclusion of the buttons in the group will be maintained automatically.
  The currently \"selected\" button can be retrieved and set with (selection) and
  (selection!) as usual.

  Note that a button can be added to a group when the button is created using the
  :group option of the various button and menu creation functions.

  Examples:

    (let [bg (button-group)]
      (flow-panel :items [(radio :id :a :text \"A\" :group bg)
                          (radio :id :b :text \"B\" :group bg)]))

    ; now A and B are mutually exclusive

    ; Check A
    (selection bg (select root [:#a]))

  Returns an instance of javax.swing.ButtonGroup

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/ButtonGroup.html
  "
  [& opts]
  (apply-options (ButtonGroup.) opts button-group-options))

(def ^{:private true} button-options {
  :halign    #(.setHorizontalAlignment ^javax.swing.AbstractButton %1 (h-alignment-table %2))
  :valign    #(.setVerticalAlignment ^javax.swing.AbstractButton %1 (v-alignment-table %2)) 
  :selected? #(.setSelected ^javax.swing.AbstractButton %1 (boolean %2))
  :group     #(.add ^javax.swing.ButtonGroup %2 %1)
  :margin    #(.setMargin ^javax.swing.AbstractButton %1 (to-insets %2))
})

(defn- apply-button-defaults
  ([button args] (apply-button-defaults button args {}))
  ([button args custom-options]
    (apply-options button args (merge default-options button-options custom-options))))

  
(defn button 
  { :seesaw {:class 'javax.swing.JButton }} 
  [& args] 
  (apply-button-defaults (construct javax.swing.JButton args) args))

(defn toggle   
  { :seesaw {:class 'javax.swing.JToggleButton }} 
  [& args] 
  (apply-button-defaults (construct javax.swing.JToggleButton args) args))

(defn checkbox 
  { :seesaw {:class 'javax.swing.JCheckBox }} 
  [& args] 
  (apply-button-defaults (construct javax.swing.JCheckBox args) args))

(defn radio    
  { :seesaw {:class 'javax.swing.JRadioButton }} 
  [& args] 
  (apply-button-defaults (construct javax.swing.JRadioButton args) args))

;*******************************************************************************
; Text widgets
(def ^{:private true} text-options {
  :halign      #(.setHorizontalAlignment ^javax.swing.JTextField %1 (h-alignment-table %2))
  :editable?   #(.setEditable ^javax.swing.text.JTextComponent %1 (boolean %2))
  ; TODO split into single/multi options since some of these will fail if
  ; multi-line? is false  
  ; TODO reflection - setColumns can apply to JTextField for JTextArea
  :columns     #(cond 
                  (instance? JTextField %1) (.setColumns ^JTextField %1 %2)
                  (instance? JTextArea %1) (.setColumns ^JTextArea %1 %2)
                  :else (throw (IllegalArgumentException. (str ":columns is not supported on " (class %1))))) 
  :rows        #(.setRows    ^javax.swing.JTextArea %1 %2)
  :wrap-lines? #(doto ^javax.swing.JTextArea %1 (.setLineWrap (boolean %2)) (.setWrapStyleWord (boolean %2)))
  :tab-size    #(.setTabSize ^javax.swing.JTextArea %1 %2)
})

(defn text
  "Create a text field or area. Given a single argument, creates a JTextField 
  using the argument as the initial text value. Otherwise, supports the 
  following additional properties:

    :text         Initial text content
    :multi-line?  If true, a JTextArea is created (default false)
    :editable?    If false, the text is read-only (default true)
    :wrap-lines?  If true (and :multi-line? is true) lines are wrapped. 
                  (default false)
    :tab-size     Tab size in spaces. Defaults to 8. Only applies if :multi-line? 
                  is true.
    :rows         Number of rows if :multi-line? is true (default 0).

  To listen for document changes, use the :listen option:

    (text :listen [:document #(... handler ...)])

  or attach a listener later with (listen):
    
    (text :id :my-text ...)
        ...
    (listen (select root [:#my-text]) :document #(... handler ...))

  Given a single widget or document (or event) argument, retrieves the
  text of the argument. For example:

      user=> (def t (text \"HI\"))
      user=> (text t)
      \"HI\"

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JTextArea.html 
    http://download.oracle.com/javase/6/docs/api/javax/swing/JTextField.html 
  " 
  { :seesaw {:class 'javax.swing.JTextField }} ;TODO!
  [& args]
  ; TODO this is crying out for a multi-method or protocol
  (let [one?        (= (count args) 1)
        [arg0 arg1] args
        as-doc      (to-document arg0)
        as-widget   (to-widget arg0)
        multi?      (or (coll? arg0) (seq? arg0))]
    (cond
      (and one? (nil? arg0)) (throw (IllegalArgumentException. "First arg must not be nil"))
      (and one? as-doc)      (get-text as-doc) 
      (and one? as-widget)   (get-text as-widget)
      (and one? multi?)      (map #(text %) arg0)
      one?                   (text :text arg0)

      :else (let [{:keys [multi-line?] :as opts} args
                  t (if multi-line? (construct JTextArea opts) (construct JTextField opts))]
              (apply-options t 
                (dissoc opts :multi-line?)
                (merge default-options text-options))))))

(defn text!
  "Set the text of widget(s) or document(s). targets is an object that can be
  turned into a widget or document, or a list of such things. value is the new
  text value to be applied. Returns targets.

  Example:

      user=> (def t (text \"HI\"))
      user=> (text! t \"BYE\")
      user=> (text t)
      \"BYE\"

  "
  [targets value]
  (let [as-doc      (to-document targets)
        as-widget   (to-widget targets)
        multi?      (or (coll? targets) (seq? targets))]
    (cond
      (nil? targets) (throw (IllegalArgumentException. "First arg must not be nil"))
      as-doc      (set-text as-doc value)
      as-widget   (set-text as-widget value)
      multi?      (doseq [w targets] (text! w value))))
  targets)

(defn- add-styles [^JTextPane text-pane styles]
  (doseq [[id & options] styles]
    (let [style (.addStyle text-pane (name id) nil)]
      (doseq [[k v] (partition 2 options)]
        (case k
          :font       (.addAttribute style StyleConstants/FontFamily (seesaw.font/to-font v))
          :size       (.addAttribute style StyleConstants/FontSize v)
          :color      (.addAttribute style StyleConstants/Foreground (seesaw.color/to-color v))
          :background (.addAttribute style StyleConstants/Background (seesaw.color/to-color v))
          :bold       (.addAttribute style StyleConstants/Bold (boolean v))
          :italic     (.addAttribute style StyleConstants/Italic (boolean v))
          :underline  (.addAttribute style StyleConstants/Underline (boolean v))
          (throw (IllegalArgumentException. (str "Option " k " is not supported in :styles"))))))))

(def ^{:private true} styled-text-options {
  :text        #(.setText ^JTextPane %1 %2)
  :wrap-lines? #(put-meta! %1 :wrap-lines? (boolean %2))
  :styles      #(add-styles %1 %2)
})

(defn styled-text 
  "Create a text pane. 
  Supports the following options:

    :text         text content.
    :wrap-lines?  If true wraps lines.
                  This only works if the styled text is wrapped
                  in (seesaw.core/scrollable). Doing so will cause
                  a grey area to appear to the right of the text.
                  This can be avoided by calling 
                    (.setBackground (.getViewport s) java.awt.Color/white)
                  on the scrollable s.
    :styles       Define styles, should be a list of vectors of form:
                  [identifier & options]
                  Where identifier is a string or keyword
                  Options supported:
                    :font        See (seesaw.font/to-font)
                    :size        An integer.
                    :color       See (seesaw.color/to-color)
                    :background  See (seesaw.color/to-color)
                    :bold        bold if true.
                    :italic      italic if true.
                    :underline   underline if true.

  See:
    (seesaw.core/style-text!) 
    http://download.oracle.com/javase/6/docs/api/javax/swing/JTextPane.html
  "
  { :seesaw {:class 'javax.swing.JTextPane}
    :arglists '([& args]) }
  [& {:as opts}]
  (let [pane (proxy [JTextPane] []
               (getScrollableTracksViewportWidth []
                 (get-meta this :wrap-lines?)))]
    (apply-options pane 
      opts (merge default-options styled-text-options))))

(defn style-text!
  "Style a JTextPane
  id identifies a style that has been added to the text pane. 
  
  See:
    
    (seesaw.core/text)
    http://download.oracle.com/javase/tutorial/uiswing/components/editorpane.html
  "
  [^JTextPane target id ^Integer start ^Integer length]
  (check-args (instance? JTextPane target) "style-text! only applied to styled-text widgets")
  (.setCharacterAttributes (.getStyledDocument target)
                            start length (.getStyle target (name id)) true)
  target)

;*******************************************************************************
; JPasswordField

(def ^{:private true} password-options (merge {
  :echo-char #(.setEchoChar ^javax.swing.JPasswordField %1 %2)
} text-options))

(defn password
  "Create a password field. Options are the same as single-line text fields with
  the following additions:

    :echo-char The char displayed for the characters in the password field

  Returns an instance of JPasswordField.

  Example:

    (password :echo-char \\X)

  Notes:
    This function is compatible with (seesaw.core/with-widget).

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JPasswordField.html
  "
  { :seesaw {:class 'javax.swing.JPasswordField }}
  [& opts]
  (let [pw (construct javax.swing.JPasswordField opts)]
    (apply-options pw opts (merge password-options default-options))))

(defn with-password*
  "Retrieve the password of a password field and passes it to the given handler
  function as an array or characters. Upon completion, the array is zero'd out
  and the value returned by the handler is returned.

  This is the 'safe' way to access the password. The (text) function will work too
  but that method is discouraged, at least by the JPasswordField docs.

  Example:

    (with-password* my-password-field
      (fn [password-chars]
        (... do something with chars ...)))

  See:
    (seesaw.core/password)
    (seesaw.core/text)
    http://download.oracle.com/javase/6/docs/api/javax/swing/JPasswordField.html
  "
  [^javax.swing.JPasswordField field handler]
  (let [chars (.getPassword field)]
    (try
      (handler chars)
      (finally
        (java.util.Arrays/fill chars \0)))))

;*******************************************************************************
; JEditorPane

(def ^{:private true} editor-pane-options {
  :page         #(.setPage ^javax.swing.JEditorPane %1 (to-url %2))
  :content-type #(.setContentType ^javax.swing.JEditorPane %1 (str %2))
  :editor-kit   #(.setEditorKit ^javax.swing.JEditorPane %1 %2)
})

(defn editor-pane
  "Create a JEditorPane. Custom options:

    :page         A URL (string or java.net.URL) with the contents of the editor
    :content-type The content-type, for example \"text/html\" for some crappy
                  HTML rendering.
    :editor-kit   The EditorKit. See Javadoc.

  Notes:
    This function is compatible with (seesaw.core/with-widget).

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JEditorPane.html
  "
  { :seesaw {:class 'javax.swing.JEditorPane }}
  [& opts]
  (apply-options 
    (construct javax.swing.JEditorPane opts) 
    opts 
    (merge default-options text-options)))

;*******************************************************************************
; Listbox

(defn- to-list-model [xs]
  (if (instance? javax.swing.ListModel xs)
    xs
    (let [model (javax.swing.DefaultListModel.)]
      (doseq [x xs]
        (.addElement model x))
      model)))

(def ^{:private true} listbox-options {
  :model    (fn [lb m] ((:model default-options) lb (to-list-model m)))
  :renderer #(.setCellRenderer ^javax.swing.JList %1 (seesaw.cells/to-cell-renderer %1 %2))
})

(defn listbox
  "Create a list box (JList). Additional options:

    :model A ListModel, or a sequence of values with which a DefaultListModel
           will be constructed.
    :renderer A cell renderer to use. See (seesaw.cells/to-cell-renderer).

  Notes:
    This function is compatible with (seesaw.core/with-widget).

    Retrieving and setting the current selection of the list box is fully 
    supported by the (selection) and (selection!) functions.

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JList.html 
  "
  { :seesaw {:class 'javax.swing.JList }}
  [& args]
  (apply-options (construct javax.swing.JList args) args (merge default-options listbox-options)))

;*******************************************************************************
; JTable

(defn- to-table-model [v]
  (cond
    (instance? javax.swing.table.TableModel v) v
    :else (apply seesaw.table/table-model v)))

(def ^{:private true} table-options {
  :model      #(.setModel ^javax.swing.JTable %1 (to-table-model %2))
  :show-grid? #(.setShowGrid ^javax.swing.JTable %1 (boolean %2))
  :fills-viewport-height? #(.setFillsViewportHeight ^javax.swing.JTable %1 (boolean %2))
})

(defn table
  "Create a table (JTable). Additional options:

    :model A TableModel, or a vector. If a vector, then it is used as
           arguments to (seesaw.table/table-model).

  Example:

    (table 
      :model [:columns [:age :height]
              :rows    [{:age 13 :height 45}
                        {:age 45 :height 13}]])

  Notes:
    This function is compatible with (seesaw.core/with-widget).

  See:
    seesaw.table/table-model 
    seesaw.examples.table
    http://download.oracle.com/javase/6/docs/api/javax/swing/JTable.html"
  { :seesaw {:class 'javax.swing.JTable }}
  [& args]
  (apply-options 
    (doto ^javax.swing.JTable (construct javax.swing.JTable args)
      (.setFillsViewportHeight true)) args (merge default-options table-options)))

;*******************************************************************************
; JTree

(def ^{:private true} tree-options {
  :editable?   #(.setEditable ^javax.swing.JTree %1 (boolean %2))
  :renderer #(.setCellRenderer ^javax.swing.JTree %1 (seesaw.cells/to-cell-renderer %1 %2))
  :expands-selected-paths? #(.setExpandsSelectedPaths ^javax.swing.JTree %1 (boolean %2))
  :large-model?            #(.setLargeModel ^javax.swing.JTree %1 (boolean %2))
  :root-visible?           #(.setRootVisible ^javax.swing.JTree %1 (boolean %2))
  :row-height              #(.setRowHeight ^javax.swing.JTree %1 %2)
  :scrolls-on-expand?      #(.setScrollsOnExpand ^javax.swing.JTree %1 (boolean %2))
  :shows-root-handles?     #(.setShowsRootHandles ^javax.swing.JTree %1 (boolean %2))
  :toggle-click-count      #(.setToggleClickCount ^javax.swing.JTree %1 %2)
  :visible-row-count       #(.setVisibleRowCount ^javax.swing.JTree %1 %2)
})

(defn tree
  "Create a tree (JTree). Additional options:

  Notes:
    This function is compatible with (seesaw.core/with-widget).

  See:
  
    http://download.oracle.com/javase/6/docs/api/javax/swing/JTree.html
  "
  { :seesaw {:class 'javax.swing.JTree }}
  [& args]
  (apply-options (construct javax.swing.JTree args) args (merge default-options tree-options)))

;*******************************************************************************
; Combobox

(defn- to-combobox-model [xs]
  (if (instance? javax.swing.ComboBoxModel xs)
    xs
    (let [model (javax.swing.DefaultComboBoxModel.)]
      (doseq [x xs]
        (.addElement model x))
      (when (seq xs)
        (.setSelectedItem model (first xs)))
      model)))

(def ^{:private true} combobox-options {
  :editable?   #(.setEditable ^javax.swing.JComboBox %1 (boolean %2))
  :model    (fn [lb m] ((:model default-options) lb (to-combobox-model m)))
  :renderer #(.setRenderer ^javax.swing.JComboBox %1 (seesaw.cells/to-cell-renderer %1 %2))
})

(defn combobox
  "Create a combo box (JComboBox). Additional options:

    :model Instance of ComboBoxModel, or sequence of values used to construct
           a default model.
    :renderer Cell renderer used for display. See (seesaw.cells/to-cell-renderer).

  Note that the current selection can be retrieved and set with the (selection) and
  (selection!) functions.

  Notes:
    This function is compatible with (seesaw.core/with-widget).

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JComboBox.html
  "
  { :seesaw {:class 'javax.swing.JComboBox }}
  [& args]
  (apply-options (construct javax.swing.JComboBox args) args (merge default-options combobox-options)))

;*******************************************************************************
; Scrolling

(def ^{:private true} hscroll-table {
  :as-needed  ScrollPaneConstants/HORIZONTAL_SCROLLBAR_AS_NEEDED
  :never      ScrollPaneConstants/HORIZONTAL_SCROLLBAR_NEVER
  :always     ScrollPaneConstants/HORIZONTAL_SCROLLBAR_ALWAYS 
})
(def ^{:private true} vscroll-table {
  :as-needed  ScrollPaneConstants/VERTICAL_SCROLLBAR_AS_NEEDED
  :never      ScrollPaneConstants/VERTICAL_SCROLLBAR_NEVER
  :always     ScrollPaneConstants/VERTICAL_SCROLLBAR_ALWAYS 
})

(def ^{:private true} scrollable-options {
  :hscroll #(.setHorizontalScrollBarPolicy ^JScrollPane %1 (hscroll-table %2))
  :vscroll #(.setVerticalScrollBarPolicy ^JScrollPane %1 (vscroll-table %2))
})

(defn scrollable 
  "Wrap target in a JScrollPane and return the scroll pane.

  The first argument is always the widget that should be scrolled. It's followed
  by zero or more options *for the scroll pane*.

  Additional Options:

    :hscroll - Controls appearance of horizontal scroll bar. 
               One of :as-needed (default), :never, :always
    :vscroll - Controls appearance of vertical scroll bar.
               One of :as-needed (default), :never, :always

  Examples:

    ; Vanilla scrollable
    (scrollable (listbox :model [\"Foo\" \"Bar\" \"Yum\"]))

    ; Scrollable with some options on the JScrollPane
    (scrollable (listbox :model [\"Foo\" \"Bar\" \"Yum\"]) :id :#scrollable :border 5)

  Notes:
    This function is compatible with (seesaw.core/with-widget).
    This function is not compatible with (seesaw.core/paintable). TODO.
  
  See http://download.oracle.com/javase/6/docs/api/javax/swing/JScrollPane.html
  "
  [target & opts]
  (let [^JScrollPane sp (construct JScrollPane opts)]
    (.setViewportView sp (to-widget target true))
    (apply-options sp opts (merge default-options scrollable-options))))

;*******************************************************************************
; Splitter

(defn- divider-location-proportional!
  [^javax.swing.JSplitPane splitter value]
  (if (.isShowing splitter)
    (if (and (> (.getWidth splitter) 0) (> (.getHeight splitter) 0))
      (.setDividerLocation splitter (double value))
      (.addComponentListener splitter
        (proxy [java.awt.event.ComponentAdapter] []
          (componentResized [e]
            (.removeComponentListener splitter this)
            (divider-location-proportional! splitter value)))))
    (.addHierarchyListener splitter
      (reify java.awt.event.HierarchyListener
        (hierarchyChanged [this e]
          (when (and (not= 0 (bit-and (.getChangeFlags e) java.awt.event.HierarchyEvent/SHOWING_CHANGED))
                   (.isShowing splitter))
            (.removeHierarchyListener splitter this)
            (divider-location-proportional! splitter value)))))))

(defn- divider-location! 
  "Sets the divider location of a splitter. Value can be one of cases:

    integer - Treated as an absolute pixel size
    double or rational - Treated as a percentage of the splitter's size

  Use the :divider-location property to set this at creation time of a
  splitter.

  Returns the splitter.

  Notes:

    This function fixes the well known limitation of JSplitPane that it will
    basically ignore proportional sizes if the splitter isn't visible yet.

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JSplitPane.html#setDividerLocation%28double%29
    http://blog.darevay.com/2011/06/jsplitpainintheass-a-less-abominable-fix-for-setdividerlocation/
  "
  [^javax.swing.JSplitPane splitter value]
  (cond
    (integer? value) (.setDividerLocation splitter ^Integer value)
    (ratio?   value) (divider-location! splitter (double value))
    (float?   value) (divider-location-proportional! splitter value)
    :else (throw (IllegalArgumentException. (str "Expected integer or float, got " value))))
  splitter)

(def ^{:private true} splitter-options {
  :divider-location divider-location!
})

(defn splitter
  "
  Create a new JSplitPane. This is a lower-level function. Usually you want
  (seesaw.core/top-bottom-split) or (seesaw.core/left-right-split). But here's
  the additional options any three of these functions can take:

    :divider-location The initial divider location. See (seesaw.core/divider-location!).

  Notes:
    This function is not compatible with (seesaw.core/paintable). TODO.

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JSplitPane.html
  "
  { :seesaw {:class 'javax.swing.JSplitPane }}
  [dir left right & opts]
  (apply-options
    (doto ^JSplitPane (construct JSplitPane opts)
      (.setOrientation (dir {:left-right JSplitPane/HORIZONTAL_SPLIT
                             :top-bottom JSplitPane/VERTICAL_SPLIT}))
      (.setLeftComponent (to-widget left true))
      (.setRightComponent (to-widget right true)))
    opts
    (merge default-options splitter-options)))

(defn left-right-split 
  "Create a left/right (horizontal) splitpane with the given widgets. See
  (seesaw.core/splitter) for additional options. Options are given after
  the two widgets.
  
  Notes:
    This function is compatible with (seesaw.core/with-widget).
    This function is not compatible with (seesaw.core/paintable). TODO.
  
  See:
    (seesaw.core/splitter)
    http://download.oracle.com/javase/6/docs/api/javax/swing/JSplitPane.html
  "
  { :seesaw {:class 'javax.swing.JSplitPane }}
  [left right & args] (apply splitter :left-right left right args))

(defn top-bottom-split 
  "Create a top/bottom (vertical) split pane with the given widgets. See
  (seesaw.core/splitter) for additional options. Options are given after
  the two widgets.
  
  Notes:
    This function is compatible with (seesaw.core/with-widget).
    This function is not compatible with (seesaw.core/paintable). TODO.
  
  See:
    (seesaw.core/splitter)
    http://download.oracle.com/javase/6/docs/api/javax/swing/JSplitPane.html
  "
  { :seesaw {:class 'javax.swing.JSplitPane }}
  [top bottom & args] (apply splitter :top-bottom top bottom args))

;*******************************************************************************
; Separator
(def ^{:private true} separator-options {
  :orientation #(.setOrientation ^javax.swing.JSeparator %1 (orientation-table %2))
})

(defn separator
  "Create a separator.

  Notes:
    This function is compatible with (seesaw.core/with-widget).
  
  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JSeparator.html
  "
  { :seesaw {:class 'javax.swing.JSeparator }}
  [& opts]
  (apply-options (construct javax.swing.JSeparator opts) opts (merge default-options separator-options)))

;*******************************************************************************
; Menus

(def ^{:private true} menu-item-options {
  :key #(.setAccelerator ^javax.swing.JMenuItem %1 (seesaw.keystroke/keystroke %2))
})

(defn menu-item          [& args] (apply-button-defaults (javax.swing.JMenuItem.) args menu-item-options))
(defn checkbox-menu-item [& args] (apply-button-defaults (javax.swing.JCheckBoxMenuItem.) args))
(defn radio-menu-item    [& args] (apply-button-defaults (javax.swing.JRadioButtonMenuItem.) args))

(defn- ^javax.swing.JMenuItem to-menu-item
  [item]
  ; TODO this sucks
  (if (instance? javax.swing.Action item) 
    (javax.swing.JMenuItem. ^javax.swing.Action item)
    (if-let [^javax.swing.Icon icon (make-icon item)]
      (javax.swing.JMenuItem. icon)
      (if (instance? String item)
        (javax.swing.JMenuItem. ^String item)))))

(def ^{:private true} menu-options {
  :items (fn [^javax.swing.JMenu menu items] 
            (doseq [item items] 
              (if-let [menu-item (to-menu-item item)]
                (.add menu menu-item)
                (if (= :separator item)
                  (.addSeparator menu)
                  (.add menu (to-widget item true))))))
})

(defn menu 
  "Create a new menu. Additional options:

    :items Sequence of menu item-like things (actions, icons, JMenuItems, etc)
  
  Notes:
    This function is compatible with (seesaw.core/with-widget).
  
  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JMenu.html"
  { :seesaw {:class 'javax.swing.JMenu }}
  [& opts]
  (apply-button-defaults (construct javax.swing.JMenu opts) opts menu-options))

(def ^{:private true} popup-options {
  ; TODO reflection - duplicate of menu-options 
  :items (fn [^javax.swing.JPopupMenu menu items] 
            (doseq [item items] 
              (if-let [menu-item (to-menu-item item)]
                (.add menu menu-item)
                (if (= :separator item)
                  (.addSeparator menu)
                  (.add menu (to-widget item true)))))) 
})

(defn popup 
  "Create a new popup menu. Additional options:

    :items Sequence of menu item-like things (actions, icons, JMenuItems, etc)

  Note that in many cases, the :popup option is what you want if you want to
  show a context menu on a widget. It handles all the yucky mouse stuff and
  fixes various eccentricities of Swing.
  
  Notes:
    This function is compatible with (seesaw.core/with-widget).
  
  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JPopupMenu.html"
  { :seesaw {:class 'javax.swing.JPopupMenu }}
  [& opts]
  (apply-options (construct javax.swing.JPopupMenu opts) opts (merge default-options popup-options)))


(defn- ^javax.swing.JPopupMenu make-popup [target arg event]
  (cond
    (instance? javax.swing.JPopupMenu arg) arg
    (fn? arg)                              (popup :items (arg event))
    :else (throw (IllegalArgumentException. (str "Don't know how to make popup with " arg)))))

(defn- popup-option-handler
  [^java.awt.Component target arg]
  (listen target :mouse 
    (fn [^java.awt.event.MouseEvent event]
      (when (.isPopupTrigger event)
        (let [p (make-popup target arg event)]
          (.show p (to-widget event) (.x (.getPoint event)) (.y (.getPoint event))))))))

  
;(def ^{:private true} menubar-options {
  ;:items (fn [mb items] (doseq [i items] (.add mb i)))
;})

(defn menubar
  "Create a new menu bar, suitable for the :menubar property of (frame). 
  Additional options:

    :items Sequence of menus, see (menu).
  
  Notes:
    This function is compatible with (seesaw.core/with-widget).
  
  See:
    (seesaw.core/frame)
    http://download.oracle.com/javase/6/docs/api/javax/swing/JMenuBar.html
  "
  { :seesaw {:class 'javax.swing.JMenuBar }}
  [& opts]
  (apply-options (construct javax.swing.JMenuBar opts) opts default-options))

;*******************************************************************************
; Toolbars


(defn- insert-toolbar-separators 
  "Replace :separator with JToolBar$Separator instances"
  [items]
  (map #(if (= % :separator) (javax.swing.JToolBar$Separator.) %) items))

(def ^{:private true} toolbar-options {
  :orientation #(.setOrientation ^javax.swing.JToolBar %1 (orientation-table %2))
  :floatable? #(.setFloatable ^javax.swing.JToolBar %1 (boolean %2))
  ; Override default :items handler
  :items     #(add-widgets %1 (insert-toolbar-separators %2))
})

(defn toolbar
  "Create a JToolBar. The following properties are supported:
 
    :floatable?  Whether the toolbar is floatable.
    :orientation Toolbar orientation, :horizontal or :vertical
    :items       Normal list of widgets to add to the toolbar. :separator
                 creates a toolbar separator.

  Notes:
    This function is compatible with (seesaw.core/with-widget).
  
  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JToolBar.html
  "
  { :seesaw {:class 'javax.swing.JToolBar }}
  [& opts]
  (apply-options (construct javax.swing.JToolBar opts) opts (merge default-options toolbar-options)))

;*******************************************************************************
; Tabs

(def ^{:private true} tab-placement-table
  (constant-map SwingConstants :bottom :top :left :right))

(def ^{:private true} tab-overflow-table {
  :scroll JTabbedPane/SCROLL_TAB_LAYOUT
  :wrap   JTabbedPane/WRAP_TAB_LAYOUT
})

(defn- add-to-tabbed-panel 
  [^javax.swing.JTabbedPane tp tab-defs]
  (doseq [{:keys [title content tip icon]} tab-defs]
    (let [title-cmp (try-cast Component title)
          index     (.getTabCount tp)]
      (cond-doto tp
        true (.addTab (when-not title-cmp (str title)) (make-icon icon) (to-widget content true) (str tip))
        title-cmp (.setTabComponentAt index title-cmp))))
  tp)

(def ^{:private true} tabbed-panel-options {
  :placement #(.setTabPlacement ^javax.swing.JTabbedPane %1 (tab-placement-table %2))
  :overflow  #(.setTabLayoutPolicy ^javax.swing.JTabbedPane %1 (tab-overflow-table %2))
  :tabs      add-to-tabbed-panel
})

(defn tabbed-panel
  "Create a JTabbedPane. Supports the following properties:

    :placement Tab placement, one of :bottom, :top, :left, :right.
    :overflow  Tab overflow behavior, one of :wrap, :scroll.
    :tabs      A list of tab descriptors. See below

  A tab descriptor is a map with the following properties:

    :title     Title of the tab or a component to be displayed.
    :tip       Tab's tooltip text
    :icon      Tab's icon, passed through (icon)
    :content   The content of the tab, passed through (to-widget) as usual.

  Returns the new JTabbedPane.

  Notes:
    This function is compatible with (seesaw.core/with-widget).
  
  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JToolBar.html
  "
  { :seesaw {:class 'javax.swing.JTabbedPane }}
  [& opts]
  (apply-options (construct javax.swing.JTabbedPane opts) opts (merge default-options tabbed-panel-options)))

;*******************************************************************************
; Canvas

(def ^{:private true} paint-property "seesaw-paint")

(defn- paint-option-handler [^java.awt.Component c v]
  (cond 
    (nil? v) (paint-option-handler c {:before nil :after nil :super? true})
    (fn? v)  (paint-option-handler c {:after v})
    (map? v) (do (put-meta! c paint-property v) (.repaint c))
    :else (throw (IllegalArgumentException. "Expect map or function for :paint property"))))

(defn- paint-component-impl [^javax.swing.JComponent this ^java.awt.Graphics2D g]
  (let [{:keys [before after super?] :or {super? true}} (get-meta this paint-property)]
    (seesaw.graphics/anti-alias g)
    (when before (seesaw.graphics/push g (before this g)))
    (when super? (proxy-super paintComponent g))
    (when after  (seesaw.graphics/push g (after this g)))))


(defmacro ^{:doc "*INTERNAL USE ONLY* See (seesaw.core/paintable)"} 
  paintable-proxy 
  [class]
  `(proxy [~class] []
    (paintComponent [g#] (@#'seesaw.core/paint-component-impl ~'this g#))))

(defmacro paintable 
  "*Experimental. Subject to change*

  Macro that generates a paintable widget, i.e. a widget that can be drawn on
  by client code. target is a Swing class literal indicating the type that will
  be constructed or a Seesaw widget constructor funcation. In either case,
  options should contain a :paint option with one of the following values:

    nil - disables painting. The widget will be filled with its background
      color unless it is not opaque.

    (fn [c g]) - a paint function that takes the canvas and a Graphics2D as 
      arguments. Called after super.paintComponent.

    {:before fn :after fn :super? bool} - a map with :before and :after functions which
      are called before and after super.paintComponent respectively. If super?
      is false, the super.paintComponent is not called.

  All other options will be passed along to the given Seesaw widget function 
  as usual and will be applied to the generated class.

  If target is a class literal then generic widget options (like :id) are 
  supported, but no widget-type-specific options will be honored.

  Notes:
    If you just want a panel to draw on, use (seesaw.core/canvas). This macro is
    intended for customizing the appearance of existing widget types.

    Also note that some customizations are also possible and maybe easier with
    the creative use of borders. 
    
  Examples:

    ; Create a raw JLabel and paint over it.
    (paintable javax.swing.JLabel :paint (fn [c g] (.fillRect g 0 0 20 20))

    ; Create a border panel with some labels and a painted background
    (paintable border-panel :north \"North\" :south \"South\"
      :paint (fn [g c] (.drawLine 0 0 (.getWidth c) (.getHeight c))))

  See:
    (seesaw.core/with-widget)
    (seesaw.core/canvas)
    (seesaw.graphics)
    http://download.oracle.com/javase/6/docs/api/javax/swing/JComponent.html#paintComponent%28java.awt.Graphics%29 
 "
 [target & {:keys [paint] :as opts}]
 (let [info (-> target resolve meta :seesaw :class)
       cls  (or info target)]
  `(doto 
    ~(if info
      `(with-widget (paintable-proxy ~cls)
          ~(cons target (mapcat identity (dissoc opts :paint))))
      `(apply-default-opts (paintable-proxy ~cls) ~(dissoc opts :paint)))
    (@#'seesaw.core/paint-option-handler ~paint))))

(def ^{:private true} canvas-options {
  :paint paint-option-handler
})

(defn canvas
  [& opts]
  "Creates a paintable canvas, i.e. a JPanel with paintComponent overridden. 
  Painting is configured with the :paint property which is described in
  the docs for (seesaw.core/paintable)

  Notes:

    (seesaw.core/config!) can be used to change the :paint property at any time.
  
  Examples:
  
    (canvas :paint #(.drawString %2 \"I'm a canvas\" 10 10))

  See:
    (seesaw.core/paintable)
    (seesaw.graphics)
    (seesaw.examples.canvas)
    http://download.oracle.com/javase/6/docs/api/javax/swing/JComponent.html#paintComponent%28java.awt.Graphics%29 
  "
  (let [{:keys [paint] :as opts} opts
        ^javax.swing.JPanel p (paintable javax.swing.JPanel :paint paint)]
    (.setLayout p nil)
    (apply-options p (dissoc opts :paint) (merge default-options canvas-options))))

;*******************************************************************************
; Frame

(def ^{:private true} frame-on-close-map {
  :hide    JFrame/HIDE_ON_CLOSE
  :dispose JFrame/DISPOSE_ON_CLOSE
  :exit    JFrame/EXIT_ON_CLOSE
  :nothing JFrame/DO_NOTHING_ON_CLOSE
})

(def ^{:private true} frame-options {
  ::with       (fn [c v]) ; ignore ::with option inserted by (with-widget)
  :id           seesaw.selector/id-of!
  :class        seesaw.selector/class-of!
  :on-close     #(.setDefaultCloseOperation ^javax.swing.JFrame %1 (frame-on-close-map %2))
  :content      #(.setContentPane ^javax.swing.JFrame %1 (to-widget %2 true))
  :menubar      #(.setJMenuBar ^javax.swing.JFrame %1 %2)

  :title        #(.setTitle ^java.awt.Frame %1 (str %2))
  :resizable?   #(.setResizable ^java.awt.Frame %1 (boolean %2))

  :minimum-size #(.setMinimumSize ^java.awt.Window %1 (to-dimension %2))
  :size         #(.setSize ^java.awt.Window %1 (to-dimension %2))
  :visible?     #(.setVisible ^java.awt.Window %1 (boolean %2))
})

(defn frame
  "Create a JFrame. Options:

    :id       id of the window, used by (select).
    :title    the title of the window
    :width    initial width. Note that calling (pack!) will negate this setting
    :height   initial height. Note that calling (pack!) will negate this setting
    :size     initial size. Note that calling (pack!) will negate this setting
    :minimum-size minimum size of frame, e.g. [640 :by 480]
    :content  passed through (to-widget) and used as the frame's content-pane
    :visible?  whether frame should be initially visible (default false)
    :resizable? whether the frame can be resized (default true)
    :on-close   default close behavior. One of :exit, :hide, :dispose, :nothing

  returns the new frame.

  Examples:

    ; Create a frame, pack it and show it.
    (-> (frame :title \"HI!\" :content \"I'm a label!\")
      pack!
      show!)
      
    ; Create a frame with an initial size (note that pack! isn't called)
    (show! (frame :title \"HI!\" :content \"I'm a label!\" :width 500 :height 600))

  Notes:
    Unless :visible? is set to true, the frame will not be displayed until (show!)
    is called on it.

    Call (pack!) on the frame if you'd like the frame to resize itself to fit its
    contents. Sometimes this doesn't look like crap.

    This function is compatible with (seesaw.core/with-widget).
  
  See:
    (seesaw.core/show!)
    (seesaw.core/hide!)
    (seesaw.core/move!)
    http://download.oracle.com/javase/6/docs/api/javax/swing/JFrame.html 
  "
  [& {:keys [width height visible? size] 
      :or {width 100 height 100}
      :as opts}]
  (cond-doto ^JFrame (apply-options (construct JFrame opts) 
               (dissoc opts :width :height :visible?) frame-options)
    (not size) (.setSize width height)
    true       (.setVisible (boolean visible?))))

(defn- get-root
  "Basically the same as SwingUtilities/getRoot, except handles JPopupMenus 
  by following the invoker of the popup if it doesn't have a parent. This
  allows (to-root) to work correctly on action event objects fired from
  menus.
  
  Returns top-level Window (e.g. a JFrame), or nil if not found."
  [w]
  (cond
    (nil? w) w
    (instance? java.awt.Window w) w
    (instance? java.applet.Applet w) w
    (instance? javax.swing.JPopupMenu w) 
      (let [^javax.swing.JPopupMenu w w]
      (if-let [p (.getParent w)] 
        (get-root p) 
        (get-root (.getInvoker w))))
    :else (get-root (.getParent ^java.awt.Component w))))

(defn to-root
  "Get the frame or window that contains the given widget. Useful for APIs
  like JDialog that want a JFrame, when all you have is a widget or event.
  Note that w is run through (to-widget) first, so you can pass event object
  directly to this."
  [w]
  (get-root (to-widget w)))

; I want to phase out to-frame. For now, it's an alias of to-root.
(def to-frame to-root)

;*******************************************************************************
; Custom-Dialog

(def ^{:private true} dialog-modality-table {
  true         java.awt.Dialog$ModalityType/APPLICATION_MODAL
  false        java.awt.Dialog$ModalityType/MODELESS
  nil          java.awt.Dialog$ModalityType/MODELESS
  :application java.awt.Dialog$ModalityType/APPLICATION_MODAL
  :document    java.awt.Dialog$ModalityType/DOCUMENT_MODAL
  :toolkit     java.awt.Dialog$ModalityType/TOOLKIT_MODAL
})

(def ^{:private true} custom-dialog-options {
  :modal? #(.setModalityType ^java.awt.Dialog %1 (or (dialog-modality-table %2) (dialog-modality-table (boolean %2))))
  :parent #(.setLocationRelativeTo ^java.awt.Dialog%1 %2)

  ; These two override frame-options for purposes of type hinting and reflection
  :on-close     #(.setDefaultCloseOperation ^javax.swing.JDialog %1 (frame-on-close-map %2))
  :content #(.setContentPane ^javax.swing.JDialog %1 (to-widget %2 true))
  :menubar #(.setJMenuBar ^javax.swing.JDialog %1 %2)

  ; Ditto here. Avoid reflection
  :title        #(.setTitle ^java.awt.Dialog %1 (str %2))
  :resizable?   #(.setResizable ^java.awt.Dialog %1 (boolean %2))
})

(def ^{:private true} dialog-result-property ::dialog-result)

(defn- is-modal-dialog? [dlg] 
  (and (instance? java.awt.Dialog dlg) 
       (not= (.getModalityType ^java.awt.Dialog dlg) java.awt.Dialog$ModalityType/MODELESS)))

(defn- show-modal-dialog [dlg]
  {:pre [(is-modal-dialog? dlg)]}
  (let [dlg-result (atom nil)]
    (listen dlg
            :window-opened
            (fn [_] (put-meta! dlg dialog-result-property dlg-result))
            #{:window-closing :window-closed}
            (fn [_] (put-meta! dlg dialog-result-property nil)))
    (config! dlg :visible? true)
    @dlg-result))

(defn return-from-dialog
  "Return from the given dialog with the specified value. dlg may be anything
  that can be converted into a dialog as with (to-root). For example, an
  event, or a child widget of the dialog. Result is the value that will
  be returned from the blocking (dialog), (custom-dialog), or (show!)
  call.

  Examples:

    ; A button with an action listener that will cause the dialog to close
    ; and return :ok to the invoker.
    (button 
      :text \"OK\" 
      :listen [:action (fn [e] (return-from-dialog e :ok))])
  
  Notes:
    The dialog must be modal and created from within the DIALOG fn with
    :modal? set to true.

  See:
    (seesaw.core/dialog)
    (seesaw.core/custom-dialog)
  "
  [dlg result]
  ;(assert-ui-thread "return-from-dialog")
  (let [dlg         (to-root dlg)
        result-atom (get-meta dlg dialog-result-property)]
    (if result-atom
      (do 
        (reset! result-atom result)
        (invoke-now (dispose! dlg)))
      (throw (IllegalArgumentException. "Counld not find dialog meta data!")))))

(defn custom-dialog
  "Create a dialog and display it.

      (custom-dialog ... options ...)

  Besides the default & frame options, options can also be one of:

    :parent  The window which the new dialog should be positioned relatively to.
    :modal?  A boolean value indicating whether this dialog is to be a
              modal dialog.  If :modal? *and* :visible? are set to
              true (:visible? is true per default), the function will
              block with a dialog. The function will return once the user:
              a) Closes the window by using the system window
                 manager (e.g. by pressing the \"X\" icon in many OS's)
              b) A function from within an event calls (dispose!) on the dialog
              c) A function from within an event calls RETURN-FROM-DIALOG
                  with a return value.
              In the case of a) and b), this function returns nil. In the
              case of c), this function returns the value passed to
              RETURN-FROM-DIALOG. Default: true.


  Returns a JDialog. Use (seesaw.core/show!) to display the dialog.

  Notes:
    This function is compatible with (seesaw.core/with-widget).
 
  See:
    (seesaw.core/show!)
    (seesaw.core/return-from-dialog)
    http://download.oracle.com/javase/6/docs/api/javax/swing/JDialog.html
"
  [& {:keys [width height visible? modal? on-close size] 
      :or {width 100 height 100 visible? false}
      :as opts}]
  (let [^JDialog dlg (apply-options (construct JDialog opts) 
                           (merge {:modal? true} (dissoc opts :width :height :visible? :pack?))
                           (merge frame-options custom-dialog-options))]
    (when-not size (.setSize dlg width height))
    (if visible?
      (show! dlg)
      dlg)))


;*******************************************************************************
; Alert
(defn alert
  "Show a simple message alert dialog. Take an optional parent component, source,
  used for dialog placement, and a message which is passed through (str).

  Examples:

    (alert \"Hello!\")
    (alert e \"Hello!\")

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JOptionPane.html#showMessageDialog%28java.awt.Component,%20java.lang.Object%29
  "
  ([source message] 
    (JOptionPane/showMessageDialog (to-widget source) (str message)))
  ([message] (alert nil message)))

;*******************************************************************************
; Input
(def ^{:private true} input-type-map {
  :error    JOptionPane/ERROR_MESSAGE
  :info     JOptionPane/INFORMATION_MESSAGE
  :warning  JOptionPane/WARNING_MESSAGE
  :question JOptionPane/QUESTION_MESSAGE
  :plain    JOptionPane/PLAIN_MESSAGE
})

(defrecord InputChoice [value to-string]
  Object
  (toString [this] (to-string value)))

(defn- input-impl
  "
    showInputDialog(Component parentComponent, 
                    Object message, 
                    String title, 
                    int messageType, 
                    Icon icon, 
                    Object[] selectionValues, 
                    Object initialSelectionValue) 
  "
  [source message {:keys [title value type choices icon to-string] 
                   :or {type :plain to-string str}}]
  (let [source  (to-widget source)
        message (if (coll? message) (object-array message) (str message))
        choices (when choices (object-array (map #(InputChoice. % to-string) choices)))
        result  (JOptionPane/showInputDialog ^java.awt.Component source 
                                 message 
                                 title 
                                 (input-type-map type) 
                                 (make-icon icon)
                                 choices value)]
    (if (and result choices)
      (:value result)
      result)))

(defn input
  "Show an input dialog:
    
    (input [source] message & options)

  source  - optional parent component
  message - The message to show the user. May be a string, or list of strings, widgets, etc.
  options - additional options

  Additional options:

    :title     The dialog title
    :value     The initial, default value to show in the dialog
    :choices   List of values to choose from rather than freeform entry
    :type      :warning, :error, :info, :plain, or :question
    :icon      Icon to display (Icon, URL, etc)
    :to-string A function which creates the string representation of the values 
               in :choices. This let's you choose arbitrary clojure data structures
               without while keeping things looking nice. Defaults to str.

  Examples:

    ; Ask for a string input
    (input \"Bang the keyboard like a monkey\")

    ; Ask for a choice from a set
    (input \"Pick a color\" :choices [\"RED\" \"YELLO\" \"GREEN\"])

    ; Choose from a list of maps using a custom string function for the display.
    ; This will display only the city names, but the return value will be one of
    ; maps in the :choices list. Yay!
    (input \"Pick a city\" 
      :choices [{ :name \"New York\"  :population 8000000 }
                { :name \"Ann Arbor\" :population 100000 }
                { :name \"Twin Peaks\" :population 5201 }]
      :to-string :name)

  Returns the user input or nil if they hit cancel.

  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JOptionPane.html
  "
  [& args]
  (let [n (count args)
        f (first args)
        s (second args)]
    (cond
      (or (= n 0) (keyword? f))
        (throw (IllegalArgumentException. "input requires at least one non-keyword arg"))
      (= n 1)      (input-impl nil f {})
      (= n 2)      (input-impl f s {})
      (keyword? s) (input-impl nil f (drop 1 args))
      :else        (input-impl f  s (drop 2 args)))))


;*******************************************************************************
; dialog
(def ^:private dialog-option-type-map {
  :default       JOptionPane/DEFAULT_OPTION
  :yes-no        JOptionPane/YES_NO_OPTION
  :yes-no-cancel JOptionPane/YES_NO_CANCEL_OPTION
  :ok-cancel     JOptionPane/OK_CANCEL_OPTION
})

(def ^:private dialog-defaults {
  :parent         nil
  :content        "Please set the :content option."
  :option-type    :default
  :type           :plain
  :options        nil
  :default-option nil
  :success-fn     (fn [_] :success)
  :cancel-fn      (fn [_])
  :no-fn          (fn [_] :no)
})

(defn dialog
  "Display a JOptionPane. This is a dialog which displays some
  input/question to the user, which may be answered using several
  standard button configurations or entirely custom ones.

      (dialog ... options ...)

  Options can be any of:

    :content      May be a string or a component (or a panel with even more 
                  components) which is to be displayed.

    :option-type  In case :options is *not* specified, this may be one of 
                  :default, :yes-no, :yes-no-cancel, :ok-cancel to specify 
                  which standard button set is to be used in the dialog.

    :type        The type of the dialog. One of :warning, :error, :info, :plain, or :question.

    :options     Custom buttons/options can be provided using this argument. 
                 It must be a seq of \"to-widget\"'able objects which will be 
                 displayed as options the user can choose from. Note that in this
                 case, :success-fn, :cancel-fn & :no-fn will *not* be called. 
                 Use the handlers on those buttons & RETURN-FROM-DIALOG to close 
                 the dialog.

    :default-option  The default option instance which is to be selected. This should be an element
                     from the :options seq.

    :success-fn  A function taking the JOptionPane as its only
                 argument. It will be called when no :options argument
                 has been specified and the user has pressed any of the \"Yes\" or \"Ok\" buttons.
                 Default: a function returning :success.
 
    :cancel-fn   A function taking the JOptionPane as its only
                 argument. It will be called when no :options argument
                 has been specified and the user has pressed the \"Cancel\" button.
                 Default: a function returning nil.

    :no-fn       A function taking the JOptionPane as its only
                 argument. It will be called when no :options argument
                 has been specified and the user has pressed the \"No\" button.
                 Default: a function returning :no.

  Any remaining options will be passed to dialog.

  Examples:

    ; display a dialog with only an \"Ok\" button.
    (dialog :content \"You may now press Ok\")

    ; display a dialog to enter a users name and return the entered name.
    (dialog :content
     (flow-panel :items [\"Enter your name\" (text :id :name :text \"Your name here\")])
                 :options-type :ok-cancel
                 :success-fn (fn [p] (text (select (to-root p) [:#name]))))

  The dialog is not immediately shown. Use (seesaw.core/show!) to display the dialog.
  If the dialog is modal this will return the result of :success-fn, :cancel-fn or 
  :no-fn depending on what button the user pressed. 
  
  Alternatively if :options has been specified, returns the value which has been 
  passed to (seesaw.core/return-from-dialog).

  See:
    (seesaw.core/show!)
    (seesaw.core/return-from-dialog)
"
  [& {:as opts}]
  ;; (Object message, int messageType, int optionType, Icon icon, Object[] options, Object initialValue)
  (let [{:keys [content option-type type
                options default-option success-fn cancel-fn no-fn]} (merge dialog-defaults opts) 
        pane (JOptionPane. 
              content 
              (input-type-map type)
              (dialog-option-type-map option-type)
              nil                       ;icon
              (when options
                (into-array (map #(to-widget % true) options)))
              (or default-option (first options))) ; default selection
        remaining-opts (apply dissoc opts :visible? (keys dialog-defaults))
        dlg            (apply custom-dialog :visible? false :content pane (reduce concat remaining-opts))]
      ;; when there was no options specified, default options will be
      ;; used, so the success-fn cancel-fn & no-fn must be called
      (when-not options
        (.addPropertyChangeListener pane JOptionPane/VALUE_PROPERTY
          (reify java.beans.PropertyChangeListener
            (propertyChange [this e]
              (return-from-dialog e 
                (([success-fn no-fn cancel-fn] (.getNewValue e)) pane))))))
      (if (:visible? opts)
        (show! dlg)
        dlg)))


;*******************************************************************************
; Slider

(def ^{:private true} slider-options {
  :orientation #(.setOrientation ^javax.swing.JSlider %1 (orientation-table %2))
  :value #(let [v %2]
            (check-args (number? v) ":value must be a number.")
            (.setValue ^javax.swing.JSlider %1 v))
  :min #(do (check-args (number? %2) ":min must be a number.")
            (.setMinimum ^javax.swing.JSlider %1 %2))
  :max #(do (check-args (number? %2) ":max must be a number.")
            (.setMaximum ^javax.swing.JSlider %1 %2))
  :minor-tick-spacing #(do (check-args (number? %2) ":minor-tick-spacing must be a number.")
                           (.setPaintTicks ^javax.swing.JSlider %1 true)
                           (.setMinorTickSpacing ^javax.swing.JSlider %1 %2))
  :major-tick-spacing #(do (check-args (number? %2) ":major-tick-spacing must be a number.")
                           (.setPaintTicks ^javax.swing.JSlider %1 true)
                           (.setMajorTickSpacing ^javax.swing.JSlider %1 %2))
  :snap-to-ticks? #(.setSnapToTicks ^javax.swing.JSlider %1 (boolean %2))
  :paint-ticks? #(.setPaintTicks ^javax.swing.JSlider %1 (boolean %2))
  :paint-labels? #(.setPaintLabels ^javax.swing.JSlider %1 (boolean %2))
  :paint-track? #(.setPaintTrack ^javax.swing.JSlider %1 (boolean %2))
  :inverted? #(.setInverted ^javax.swing.JSlider %1 (boolean %2))
 
})

(defn slider
  "Show a slider which can be used to modify a value.

      (slider ... options ...)

  Besides the default options, options can also be one of:

    :orientation   The orientation of the slider. One of :horizontal, :vertical.
    :value         The initial numerical value that is to be set. 
    :min           The minimum numerical value which can be set.
    :max           The maximum numerical value which can be set.
    :minor-tick-spacing  The spacing between minor ticks. If set, will also set :paint-ticks? to true.
    :major-tick-spacing  The spacing between major ticks. If set, will also set :paint-ticks? to true.
    :snap-to-ticks?  A boolean value indicating whether the slider should snap to ticks.
    :paint-ticks?    A boolean value indicating whether to paint ticks.
    :paint-labels?   A boolean value indicating whether to paint labels for ticks.
    :paint-track?    A boolean value indicating whether to paint the track.
    :inverted?       A boolean value indicating whether to invert the slider (to go from high to low).

  Returns a JSlider.

  Examples:

    ; ask & return single file
    (slider :value 10 :min -50 :max 50)

  Notes:
    This function is compatible with (seesaw.core/with-widget).
 
  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JSlider.html
"
  { :seesaw {:class 'javax.swing.JSlider }}
  [& {:keys [orientation value min max minor-tick-spacing major-tick-spacing
             snap-to-ticks? paint-ticks? paint-labels? paint-track? inverted?]
      :as kw}] 
  (let [sl (construct javax.swing.JSlider kw)]
    (apply-options sl kw (merge default-options slider-options))))


;*******************************************************************************
; Progress Bar
(def ^{:private true} progress-bar-options {
  :orientation #(.setOrientation ^javax.swing.JProgressBar %1 (orientation-table %2))
  :value #(do (check-args (number? %2) ":value must be a number") (.setValue ^javax.swing.JProgressBar %1 %2))
  :min #(do (check-args (number? %2) ":min must be a number.")
            (.setMinimum ^javax.swing.JProgressBar %1 %2))
  :max #(do (check-args (number? %2) ":max must be a number.")
            (.setMaximum ^javax.swing.JProgressBar %1 %2))
  :paint-string? #(.setStringPainted ^javax.swing.JProgressBar %1 (boolean %2))
  :indeterminate? #(.setIndeterminate ^javax.swing.JProgressBar %1 (boolean %2))
})

(defn progress-bar
  "Show a progress-bar which can be used to display the progress of long running tasks.

      (progress-bar ... options ...)

  Besides the default options, options can also be one of:

    :orientation   The orientation of the progress-bar. One of :horizontal, :vertical. Default: :horizontal.
    :value         The initial numerical value that is to be set. Default: 0.
    :min           The minimum numerical value which can be set. Default: 0.
    :max           The maximum numerical value which can be set. Default: 100.
    :paint-string? A boolean value indicating whether to paint a string containing
                   the progress' percentage. Default: false.
    :indeterminate? A boolean value indicating whether the progress bar is to be in
                    indeterminate mode (for when the exact state of the task is not
                    yet known). Default: false.

  Examples:

    ; vertical progress bar from 0 to 100 starting with inital value at 15.
    (progress-bar :orientation :vertical :min 0 :max 100 :value 15)

  Returns a JProgressBar.

  Notes:
    This function is compatible with (seesaw.core/with-widget).
 
  See:
    http://download.oracle.com/javase/6/docs/api/javax/swing/JProgressBar.html

"
  { :seesaw {:class 'javax.swing.JProgressBar }}
  [& {:keys [orientation value min max] :as opts}]
  (let [sl (construct javax.swing.JProgressBar opts)]
    (apply-options sl opts (merge default-options progress-bar-options))))



;*******************************************************************************
; Selectors

(defn select
  "Select a widget using the given selector expression. Selectors are *always*
   expressed as a vector. root is the root of the widget hierarchy to select
   from, usually either a (frame) or other container.

    (select root [:#id])          Look up widget by id. A single widget is 
                                  always returned.

    (select root [:tag])          Look up widgets by \"tag\". In Seesaw tag is
                                  treated as the exact simple class name of a
                                  widget, so :JLabel would match both 
                                  javax.swing.JLabel *and* com.me.JLabel.
                                  Be careful!

    (select root [:<class-name>]) Look up widgets by *fully-qualified* class name. 
                                  Matches sub-classes as well. Always returns a
                                  sequence of widgets.

    (select root [:<class-name!>]) Same as above, but class must match exactly.

    (select root [:*])             Root and all the widgets under it

  Notes:
    This function will return a single widget *only* in the case where the selector
    is a single identifier, e.g. [:#my-id]. In *all* other cases, a sequence of
    widgets is returned. This is for convenience. Select-by-id is the common case
    where a single widget is almost always desired.

  Examples:

    To find a widget by id from an event handler, use (to-root) on the event to get 
    the root and then select on the id:

      (fn [e]
        (let [my-widget (select (to-root e) [:#my-widget])]
          ...))

    Disable all JButtons (excluding subclasses) in a hierarchy:

      (config! (select root [:<javax.swing.JButton>]) :enabled? false)

    More:

      ; All JLabels, no sub-classes allowed
      (select root [:<javax.swing.JLabel!>])

      ; All JSliders that are descendants of a JPanel with id foo
      (select root [:JPanel#foo :JSlider])

      ; All JSliders (and sub-classes) that are immediate children of a JPanel with id foo
      (select root [:JPanel#foo :> :<javax.swing.JSlider>])

      ; All widgets with class foo. Set the class of a widget with the :class option
      (flow-panel :class :my-class) or (flow-panel :class #{:class1 :class2})
      (select root [:.my-class])
      (select root [:.class1.class2])

      ; Select all text components with class input
      (select root [:<javax.swing.text.JTextComponent>.input])

      ; Select all descendants of all panels with class container
      (select root [:JPanel.container :*])

  See:
    (seesaw.selector/select)
    https://github.com/cgrand/enlive
  "
  ([root selector]
    (check-args (vector? selector) "selector must be vector")
    (let [root (to-widget root)
          result (seesaw.selector/select root selector)
          id? (and (nil? (second selector)) (seesaw.selector/id-selector? (first selector)))]
      (if id? (first result) result))))

;*******************************************************************************
; Widget layout manipulation

(defprotocol LayoutManipulation
  (add!* [layout target widget constraint])
  (get-constraint [layout container widget]))

(extend-protocol LayoutManipulation
  java.awt.LayoutManager
    (add!* [layout target widget constraint]
      (add-widget target widget))
    (get-constraint [layout container widget] nil)

  java.awt.BorderLayout
    (add!* [layout target widget constraint]
      (add-widget target widget (border-layout-dirs constraint)))
    (get-constraint [layout container widget]
      (.getConstraints layout widget)))

(defn- add!-impl 
  [container subject & more]
  (let [^java.awt.Container container (to-widget container)
        [widget constraint] (if (vector? subject) subject [subject nil])
        layout (.getLayout container)]
    (add!* layout container widget constraint)
    (when more
      (apply add!-impl container more))
    container))

(defn add! [container subject & more]
  "Add one or more widgets to a widget container. The container and each widget
  argument are passed through (to-widget) as usual. Each widget can be a single
  widget, or a widget/constraint pair with a layout-specific constraint.

  The container is properly revalidated and repainted after removal.

  Examples:

    ; Add a label and a checkbox to a panel
    (add! (vertical-panel) \"Hello\" (button ...))

    ; Add a label and a checkbox to a border panel with layout constraints
    (add! (border-panel) [\"Hello\" :north] [(button ...) :center])

  Returns the target container *after* it's been passed through (to-widget).
  "
  (handle-structure-change (apply add!-impl container subject more)))

(defn- remove!-impl
  [container subject & more]
  (let [^java.awt.Container container (to-widget container)]
    (.remove container (to-widget subject))
    (when more
      (apply remove!-impl container more))
    container))

(defn remove!
  "Remove one or more widgets from a container. container and each widget
  are passed through (to-widget) as usual, but no new widgets are created.

  The container is properly revalidated and repainted after removal.

  Examples:

    (def lbl (label \"HI\"))
    (def p (border-panel :north lbl))
    (remove! p lbl)

  Returns the target container *after* it's been passed through (to-widget).
  "
  [container subject & more]
  (handle-structure-change (apply remove!-impl container subject more)))

(defn- ^Integer index-of-component
  [^java.awt.Container container widget]
  (loop [comps (.getComponents container) idx 0]
    (cond
      (not comps)              nil
      (= widget (first comps)) idx
      :else (recur (next comps) (inc idx)))))

(defn- replace!-impl
  [^java.awt.Container container old-widget new-widget]
  (let [idx        (index-of-component container old-widget)]
    (when idx
      (let [constraint (get-constraint (.getLayout container) container old-widget)]
        (doto container
          (.remove idx)
          (.add    new-widget constraint idx))))
    container))
  
(defn replace!
  "Replace old-widget with new-widget from container. container and each widget
  are passed through (to-widget) as usual. Note that the layout constraints of 
  old-widget are retained for the new widget. This is different from the behavior
  you'd get with just remove/add in Swing.

  The container is properly revalidated and repainted after replacement.

  Examples:

    ; Replace a label with a new label.
    (def lbl (label \"HI\"))
    (def p (border-panel :north lbl))
    (replace! p lbl \"Goodbye\")

  Returns the target container *after* it's been passed through (to-widget).
  "
  [container old-widget new-widget]
  (handle-structure-change 
    (replace!-impl (to-widget container) (to-widget old-widget) (to-widget new-widget true))))

