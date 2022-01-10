package com.ing.rebel.specification

import com.ing.rebel.RebelKeyable

import scala.reflect.macros.whitebox

// Used to enable implicits in trait
object Macros {
  def keyable[Key: c.WeakTypeTag](c: whitebox.Context): c.Expr[RebelKeyable[Key]] = {
    import c.universe._
    val key = c.prefix.tree.tpe.member(TypeName("Key")).typeSignature
    c.Expr(q"implicitly[RebelKeyable[$key]]").asInstanceOf[c.Expr[RebelKeyable[Key]]]
  }}

