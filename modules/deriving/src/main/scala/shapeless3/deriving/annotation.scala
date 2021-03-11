/*
 * Copyright (c) 2015-19 Alexandre Archambault
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shapeless3.deriving

import scala.deriving._
import scala.quoted._

/**
 * Evidence that type `T` has annotation `A`, and provides an instance of the annotation.
 *
 * If type `T` has an annotation of type `A`, then an implicit `Annotation[A, T]` can be found, and its `apply` method
 * provides an instance of the annotation.
 *
 * Example:
 * {{{
 *   case class First(i: Int)
 *
 *   @First(3) trait Something
 *
 *
 *   val somethingFirst = Annotation[First, Something].apply()
 *   assert(somethingFirst == First(3))
 * }}}
 *
 * @tparam A: annotation type
 * @tparam T: annotated type
 *
 * @author Alexandre Archambault
 */
trait Annotation[A, T] extends Serializable {
  def apply(): A
}

object Annotation {
  def apply[A,T](implicit annotation: Annotation[A, T]): Annotation[A, T] = annotation

  def mkAnnotation[A, T](annotation: A): Annotation[A, T] =
    new Annotation[A, T] {
      def apply() = annotation
    }

  inline def mkAnnotation[A, T]: Annotation[A, T] = ${ AnnotationMacros.mkAnnotation }

  inline given [A, T]: Annotation[A, T] = mkAnnotation[A, T]
}

/**
 * Provides the annotations of type `A` of the fields or constructors of case class-like or sum type `T`.
 *
 * If type `T` is case class-like, this type class inspects the parameters in the first parameter list of the primary constructor
 * and provides their annotations of type `A`. If type `T` is a sum type, its constructor types are inspected for annotations.
 *
 * Type `Out` is a tuple having the same number of elements as `T` (number of parameters of `T` if `T` is case class-like,
 * or number of constructors of `T` if it is a sum type). It is made of `None.type` (no annotation on corresponding
 * field or constructor) and `Some[A]` (corresponding field or constructor is annotated).
 *
 * Method `apply` provides a tuple of type `Out` made of `None` (corresponding field or constructor not annotated)
 * or `Some(annotation)` (corresponding field or constructor has annotation `annotation`).
 *
 * Note that annotation types must be concrete for this type class to take them into account.
 *
 * Example:
 * {{{
 *   case class First(s: String)
 *
 *   case class CC(i: Int, @First("a") s: String)
 *
 *   sealed trait Base
 *   @First("b") case class BaseI(i: Int) extends Base
 *   case class BaseS(s: String) extends Base
 *
 *
 *   val ccFirsts = Annotations[First, CC]
 *   val baseFirsts = Annotations[First, Base]
 *
 *   // ccFirsts.Out is  (None.type, Some[First], None.type)
 *   // ccFirsts.apply() is
 *   //   (None, Some(First("a")), None)
 *
 *   // baseFirsts.Out is  (Some[First], None.type)
 *   // baseFirsts.apply() is
 *   //   (Some(First("b")), None)
 * }}}
 *
 * @tparam A: annotation type
 * @tparam T: case class-like or sum type, whose constructor parameters or constructors are annotated
 *
 * @author Alexandre Archambault
 */
trait Annotations[A,T] extends Serializable {
  type Out <: Tuple

  def apply(): Out
}

object Annotations {
  def apply[A, T](implicit annotations: Annotations[A,T]): Aux[A, T, annotations.Out] = annotations

  type Aux[A, T, Out0 <: Tuple] = Annotations[A, T] { type Out = Out0 }

  def mkAnnotations[A, T, Out0 <: Tuple](annotations: Out0): Aux[A, T, Out0] =
    new Annotations[A, T] {
      type Out = Out0
      def apply() = annotations
    }

  transparent inline implicit def mkAnnotations[A, T]: Annotations[A, T] =
    ${ AnnotationMacros.mkAnnotations[A, T] }
}

object AnnotationMacros {
  def mkAnnotation[A: Type, T: Type](using Quotes): Expr[Annotation[A, T]] = {
    import quotes.reflect._

    val annotTpe = TypeRepr.of[A]
    val annotFlags = annotTpe.typeSymbol.flags
    if (annotFlags.is(Flags.Abstract) || annotFlags.is(Flags.Trait)) {
      report.error(s"Bad annotation type ${annotTpe.show} is abstract")
      '{???}
    } else {
      val annoteeTpe = TypeRepr.of[T]
      // TODO try to use `getAnnotation` for performance
      annoteeTpe.typeSymbol.annotations.find(_.tpe <:< annotTpe) match {
        case Some(tree) => '{ Annotation.mkAnnotation[A, T](${tree.asExprOf[A]}) }
        case None =>
          report.error(s"No Annotation of type ${annotTpe.show} for type ${annoteeTpe.show}")
          '{???}
      }
    }
  }

  def mkAnnotations[A: Type, T: Type](using q: Quotes): Expr[Annotations[A, T]] = {
    import quotes.reflect._

    val annotTpe = TypeRepr.of[A]
    val annotFlags = annotTpe.typeSymbol.flags
    if (annotFlags.is(Flags.Abstract) || annotFlags.is(Flags.Trait)) {
      report.throwError(s"Bad annotation type ${annotTpe.show} is abstract")
    } else {
      val r = new ReflectionUtils(q)
      import r._

      def mkAnnotations(annotTrees: Seq[Expr[Any]]): Expr[Annotations[A, T]] =
        Expr.ofTupleFromSeq(annotTrees) match {
          case '{ $t: tup } => '{ Annotations.mkAnnotations[A, T, tup & Tuple]($t) }
        }

      def findAnnotation[A: Type](annoteeSym: Symbol): Expr[Option[A]] =
        // TODO try to use `getAnnotation` for performance
        annoteeSym.annotations.find(_.tpe <:< TypeRepr.of[A]) match {
          case Some(tree) => '{ Some(${tree.asExprOf[A]}) }
          case None       => '{ None }
        }

      val annoteeTpe = TypeRepr.of[T]
      annoteeTpe.classSymbol match {
        case Some(annoteeCls) if annoteeCls.flags.is(Flags.Case) =>
          val valueParams = annoteeCls.primaryConstructor.paramSymss
            .find(_.headOption.fold(false)( _.isTerm)).getOrElse(Nil)
          mkAnnotations(valueParams.map { vparam => findAnnotation[A](vparam) })
        case Some(annoteeCls) =>
          Mirror(annoteeTpe) match {
            case Some(rm) =>
              mkAnnotations(rm.MirroredElemTypes.map { child => findAnnotation[A](child.typeSymbol) })
            case None =>
              report.throwError(s"No Annotations for sum type ${annoteeTpe.show} with no Mirror")
          }
        case None =>
          report.throwError(s"No Annotations for non-class ${annoteeTpe.show}")
      }
    }
  }
}
