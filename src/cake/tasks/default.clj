(ns cake.tasks.default
  (:use cake.core)
  (:require [cake.tasks help jar test compile deps icing release swank file version bake]))

(deftask default #{help})
