package org.enso.syntax.text.precedence

import org.enso.data.List1
import org.enso.data.Tree
import org.enso.data.Shifted
import org.enso.syntax.text.AST
import org.enso.syntax.text.AST.Mixfix.Segment
import org.enso.syntax.text.AST._
import org.enso.syntax.text.ast.Repr

import scala.annotation.tailrec

object Mixfix {

  val Mixfix = AST.Mixfix

  def exprList(ast: AST): Shifted.List1[AST] = {
    @tailrec
    def go(ast: AST, out: AST.Stream): Shifted.List1[AST] = ast match {
      case App(fn, off, arg) => go(fn, Shifted(off, arg) :: out)
      case ast               => Shifted.List1(ast, out)
    }
    go(ast, List())
  }

  //////////////////
  //// Registry ////
  //////////////////

  final case class Registry() {
    var tree = Tree[AST, List1[Mixfix.Segment.Pattern.Class]]()

    override def toString: String =
      tree.toString

    def insert(t: Mixfix.Definition): Unit =
      tree += t.segments.toList.map(_._1) -> t.segments.map(_._2)
  }

  object Registry {
    type T = Tree[AST, List1[Mixfix.Segment.Pattern.Class]]
    def apply(ts: Mixfix.Definition*): Registry = {
      val registry = new Registry()
      ts.foreach(registry.insert)
      registry
    }
  }

  /////////////////
  //// Context ////
  /////////////////

  case class Context(tree: Registry.T, parent: Option[Context]) {
    def get(t: AST): Option[Registry.T] =
      tree.get(t)

    def isEmpty: Boolean =
      tree.isEmpty

    @tailrec
    final def parentCheck(t: AST): Boolean = {
      parent match {
        case None => false
        case Some(p) =>
          p.get(t) match {
            case None    => p.parentCheck(t)
            case Some(_) => true
          }
      }
    }
  }

  object Context {
    def apply():                 Context = Context(Tree(), None)
    def apply(tree: Registry.T): Context = Context(tree, None)
  }

  /////////////////
  //// Builder ////
  /////////////////

  class SegmentBuilder(val ast: AST) {
    import Mixfix._
    import Mixfix.Segment.Pattern

    var offset: Int         = 0
    var revBody: AST.Stream = List()

    def buildAST() = revBody match {
      case Nil           => None
      case seg1 :: seg2_ => Some(Operator.rebuild(List1(seg1, seg2_)))
    }

    def buildAST2(revLst: AST.Stream): Option[Shifted[AST]] =
      revLst match {
        case Nil           => None
        case seg1 :: seg2_ => Some(Operator.rebuild(List1(seg1, seg2_)))
      }

    def build(
      tp: Pattern.Class,
      last: Boolean
    ): Shifted[Segment.Class] = {
//      resolveStep(tp, revBody.reverse)

//      val segment2 = resolve(ast,tp,revBody.reverse)

      val optAst = buildAST()
      val segment = tp match {
        case t: Pattern.Empty =>
          val empty = Segment(t, ast, ())
          if (last) empty
          else optAst.map(Segment.Empty.NonEmpty(ast, _)).getOrElse(empty)
        case t: Pattern.Expr0 =>
          Segment(t, ast, optAst)
        case t: Pattern.Expr =>
          optAst.map(Segment(t, ast, _)).getOrElse(Segment.Expr.Empty(ast))
      }
      Shifted(offset, segment)
    }

    def resolveList[T](
      p: Pattern[T],
      stream: AST.Stream
    ): (List[T], AST.Stream) = {
      @tailrec
      def go(stream: AST.Stream, out: List[T]): (List[T], AST.Stream) =
        resolveStep(p, stream) match {
          case None               => (out, stream)
          case Some((t, stream2)) => go(stream2, t :: out)
        }
      go(stream, Nil)
    }

    def resolveStep[T](
      p: Pattern[T],
      stream: AST.Stream
    ): Option[(T, AST.Stream)] = p match {
      case Pattern.Empty() => Some(((), stream))
      case Pattern.Expr0() => Some((buildAST2(stream.reverse), Nil))
      case Pattern.Expr()  => buildAST2(stream.reverse).map((_, Nil))
      case Pattern.List(p2) =>
        resolveStep(p2, stream) match {
          case None => None
          case Some((head, stream2)) =>
            val (tail, stream3) = resolveList(p2, stream2)
            Some(List1(head, tail), stream3)
        }
    }

    def resolve(
      head: AST,
      p: Pattern[Repr.Provider],
      stream: AST.Stream
    ): Segment.Class =
      resolveStep(p, stream) match {
        //        case None => Segment.Unmatched(p, head, stream)
        case Some((body, stream2)) =>
          stream2 match {
            case Nil => Segment(p, head, body)
            //            case Nil => Segment(p, head, body)
            //            case _   => Segment.Unsaturated(p, head, body, stream2)
          }
      }

//    val lst: List[Repr.Provider] = List(Pattern.Expr(), Pattern.Empty)

//    val testt = lst.map(resolve(AST.Blank, _, List()))

//    final case class Segment[T: Repr.Of](
//      tp: Segment.Pattern[T],
//      head: AST,
//      body: T
//    ) extends Segment.Class {
//      val repr = R + head + body
//    }

//    sealed trait Pat[T] {
//      implicit val repr: Repr.Of[T]
//    }

//    case class Printer[-T]()
//    case class Pattern[+T](implicit val printer: Printer[T])

    abstract class Pat[T]()(implicit repr: Repr.Of[T]) {
      def resStep(
        stream: AST.Stream
      ): Option[(T, AST.Stream)] = resolveStep(this, stream)

      def resolve(
        head: AST,
        stream: AST.Stream
      ): Seg[T] =
        resolveStep(this, stream) match {
          //        case None => Segment.Unmatched(p, head, stream)
          case Some((body, stream2)) =>
            stream2 match {
              case Nil => Seg(this, head, body)
              //            case Nil => Segment(p, head, body)
              //            case _   => Segment.Unsaturated(p, head, body, stream2)
            }
        }

    }

    object Pat {

      import org.enso.data

      type Class = Pat[_]
      final case class Expr0() extends Pat[scala.Option[Shifted[AST]]]
      private type T[S] = Pat[S]
      case class Empty()                      extends T[Unit]
      case class Expr()                       extends T[Shifted[AST]]
      case class Option[S: Repr.Of](el: T[S]) extends T[scala.Option[S]]
      case class List[S: Repr.Of](el: T[S])   extends T[data.List1[S]]
//      case class App[L, R](l: T[L], r: T[R])  extends T[(L, R)]
    }

    final case class Seg[T: Repr.Of](
      tp: Pat[T],
      head: AST,
      body: T
    )

    def resolveList[T](
      p: Pat[T],
      stream: AST.Stream
    ): (List[T], AST.Stream) = {
      @tailrec
      def go(stream: AST.Stream, out: List[T]): (List[T], AST.Stream) =
        resolveStep(p, stream) match {
          case None               => (out, stream)
          case Some((t, stream2)) => go(stream2, t :: out)
        }
      go(stream, Nil)
    }

    def resolveStep[T](
      p: Pat[T],
      stream: AST.Stream
    ): Option[(T, AST.Stream)] = p match {
      case Pat.Empty() => Some(((), stream))
      case Pat.Expr0() => Some((buildAST2(stream.reverse), Nil))
      case Pat.Expr()  => buildAST2(stream.reverse).map((_, Nil))
      case Pat.List(p2) =>
        resolveStep(p2, stream) match {
          case None => None
          case Some((head, stream2)) =>
            val (tail, stream3) = resolveList(p2, stream2)
            Some(List1(head, tail), stream3)
        }
    }

//    def resolve(
//      head: AST,
//      p: Pat[_],
//      stream: AST.Stream
//    ): Segment.Class =
//      resolveStep(p, stream) match {
//        //        case None => Segment.Unmatched(p, head, stream)
//        case Some((body, stream2)) =>
//          stream2 match {
//            case Nil => p.seg(head, body)
//            //            case Nil => Segment(p, head, body)
//            //            case _   => Segment.Unsaturated(p, head, body, stream2)
//          }
//      }

//    abstract class PatternWithRepr[T]()(
//      implicit val repr: Repr.Of[T]
//    ) {
////      def resolveList(stream: AST.Stream): (List[T], AST.Stream) = {
////        @tailrec
////        def go(stream: AST.Stream, out: List[T]): (List[T], AST.Stream) =
////          resolveStep(stream) match {
////            case None               => (out, stream)
////            case Some((t, stream2)) => go(stream2, t :: out)
////          }
////        go(stream, Nil)
////      }
////
//      def resolveStep(stream: AST.Stream): Option[(T, AST.Stream)] =
//        this match {
//          case Pat.Empty() => Some(((), stream))
////          case Pat.Expr0() => Some((buildAST2(stream.reverse), Nil))
////          case Pat.Expr()  => buildAST2(stream.reverse).map((_, Nil))
////          case Pat.List(p2) =>
////            resolveStep(p2, stream) match {
////              case None => None
////              case Some((head, stream2)) =>
////                val (tail, stream3) = resolveList(p2, stream2)
////                Some(List1(head, tail), stream3)
////            }
//        }
////      def segment(head: AST, body: T): Seg[T] = Seg(pat, head, body)
//    }

//    val ttt = PatternWithRepr(Pattern.Expr())
//    println(ttt.repr)
//
//    def resolve(
//      head: AST,
//      p: PatternWithRepr[_],
//      stream: AST.Stream
//    ): Segment.Class =
//      resolveStep(p.pat, stream) match {
////        case None => Segment.Unmatched(p, head, stream)
//        case Some((body, stream2)) =>
//          stream2 match {
//            case Nil => p.segment(head, body.asInstanceOf)
////            case Nil => Segment(p, head, body)
////            case _   => Segment.Unsaturated(p, head, body, stream2)
//          }
//      }

//    def resolve(head: AST, p: Pattern[_], stream: AST.Stream): Segment.Class =
//      p match {
//        case s: Repr.Provider => resolve_x(head, s, stream)
//      }

    override def toString: String =
      s"SegmentBuilder($offset, $revBody)"
  }

  class MixfixBuilder(ast: AST) {
    var context: Context                                    = Context()
    var mixfix: Option[List1[Mixfix.Segment.Pattern.Class]] = None
    var current: SegmentBuilder                             = new SegmentBuilder(ast)
    var revSegments: List[SegmentBuilder]                   = List()
  }

  def partition(t: AST): AST = {

    var builder: MixfixBuilder = new MixfixBuilder(Blank)
    builder.mixfix = Some(List1(Mixfix.Segment.Pattern.Expr(), Nil))
    var builderStack: List[MixfixBuilder] = Nil

    def pushBuilder(ast: AST, off: Int): Unit = {
//      println(s"pushBuilder($off)")
      builderStack +:= builder
      builder                = new MixfixBuilder(ast)
      builder.current.offset = off
    }

    def popBuilder(): Unit = {
//      println("popBuilder")
      builder      = builderStack.head
      builderStack = builderStack.tail
    }

    def pushSegment(ast: AST, off: Int): Unit = {
//      println(s"pushSegment($off)")
      builder.revSegments ::= builder.current
      builder.current        = new SegmentBuilder(ast)
      builder.current.offset = off
    }

    import Mixfix._

    val hardcodedRegistry = Registry(
      Mixfix.Definition(
        Opr("(") -> Mixfix.Segment.Pattern.Expr0(),
        Opr(")") -> Mixfix.Segment.Pattern.Empty()
      ),
      Mixfix.Definition(
        Var("if") -> Mixfix.Segment.Pattern.Expr(),
        Var("then") -> Mixfix.Segment.Pattern.Expr()
      ),
      Mixfix.Definition(
        Var("if") -> Mixfix.Segment.Pattern.Expr(),
        Var("then") -> Mixfix.Segment.Pattern.Expr(),
        Var("else") -> Mixfix.Segment.Pattern.Expr()
      ),
      Mixfix.Definition(
        Var("import") -> Mixfix.Segment.Pattern.Expr()
      )
    )

    val root = Context(hardcodedRegistry.tree)

    def close(): AST.Stream = {
//      println(s"\n\n-----------------------------------\n\n")
      val revSegments = builder.current :: builder.revSegments
//      println(s"revSegments =")
//      pprint.pprintln(revSegments, width    = 50, height = 10000)
//      pprint.pprintln(builder.mixfix, width = 50, height = 10000)
      val result = {
        builder.mixfix match {
          case None =>
            val segments = revSegments.reverseMap { segBldr =>
              val optAst = segBldr.buildAST()
              Shifted(segBldr.offset, Unmatched.Segment(segBldr.ast, optAst))
            }

            val possiblePaths = builder.context.tree.dropValues()
            val mx = segments match {
              case s :: ss =>
                Shifted(
                  s.off,
                  Mixfix.Unmatched(Shifted.List1(s.el, ss), possiblePaths)
                )
            }
            List(mx)

          case Some(ts) =>
            val revSegTypes = ts.toList.reverse
            val revSegDefs  = revSegTypes.zip(revSegments)

            def reverseMap[T, S](t: List[T])(f: (T, Boolean) => S): List[S] = {
              @tailrec
              def go[T, S](
                f: (T, Boolean) => S,
                lst: List[T],
                out: List[S]
              ): List[S] = {
                lst match {
                  case Nil     => out
                  case l :: ls => go(f, ls, f(l, false) :: out)
                }
              }
              t match {
                case Nil     => Nil
                case l :: ls => go(f, ls, f(l, true) :: Nil)
              }

            }

            val segments = reverseMap(revSegDefs) {
              case ((tp, segBldr), t) => segBldr.build(tp, t)
            }

            val mx = segments match {
              case s :: ss => Shifted(s.off, Mixfix(Shifted.List1(s.el, ss)))
            }

            val suffix: AST.Stream = revSegDefs.head match {
              case (tp, segBldr) =>
                tp match {
                  case t: Segment.Pattern.Empty => segBldr.revBody
                  case _                        => List()
                }
            }
            suffix :+ mx
        }
      }

//      println("Close Result:")
//      pprint.pprintln(result, width = 50, height = 10000)
      result
    }

    def close2() = {
//      println("close2")
      val subAst = close()
      popBuilder()
      builder.current.revBody = subAst ++ builder.current.revBody
    }

    @tailrec
    def go(input: AST.Stream): AST = {
      input match {
        case Nil =>
          if (builderStack.isEmpty) {
//            println("End of input (not in stack)")
            close() match {
              case Shifted(
                    _,
                    Mixfix(
                      Shifted.List1(
                        Mixfix.Segment(
                          Segment.Pattern.Expr(),
                          _,
                          body: Shifted[AST]
                        ),
                        Nil
                      )
                    )
                  ) :: _ =>
                body.el
              case _ => throw new Error("Impossible happened.")
            }

          } else {
//            println("End of input (in stack)")
            close2()
            go(input)
          }
        case t1 :: t2_ =>
//          println(s"> $t1")

          builder.context.get(t1.el) match {
            case Some(tr) =>
//              println(">> New segment")
              pushSegment(t1.el, t1.off)
//              builder.mixfix  = builder.mixfix.map(Some(_)).getOrElse(tr.value)
              builder.mixfix  = tr.value.map(Some(_)).getOrElse(builder.mixfix)
              builder.context = builder.context.copy(tree = tr)
              go(t2_)

            case None =>
              root.get(t1.el) match {
                case Some(tr) =>
//                  println(">> Root")
                  val context = builder.context
                  pushBuilder(t1.el, t1.off)
                  builder.mixfix  = tr.value
                  builder.context = Context(tr, Some(context))
                  go(t2_)
                case None =>
//                  println(s"PARENT CHECK (${builder.current.ast}, ${t1.el})")
                  val currentClosed = builder.context.isEmpty
                  val parentPrecWin = (builder.current.ast, t1.el) match {
                    case (_: Opr, _) => false
                    case (_, _: Opr) => true
                    case _           => false
                  }
                  val parentBreak = builder.context.parentCheck(t1.el)
                  (currentClosed || parentPrecWin) && parentBreak match {
                    case true =>
//                      println("Parent close")
                      close2()
                      go(input)
                    case false =>
//                      println(">> Add token")
                      builder.current.revBody ::= t1
                      go(t2_)
                  }
              }
          }

      }
    }

//      go(Context(registry.tree, None), exprList(t).toList(), List())
//    println("START")
    val elst = exprList(t).toList()
//    pprint.pprintln(elst, width = 50, height = 10000)
    go(elst)
  }

//    def partition(t: AST) = {
//
//      def go(context:Context, lst: SpacedList[AST], out: List[Spaced[AST]]): List[Spaced[AST]]= {
//        context.get(lst.head) match {
//          case None => lst.tail match {
//            case Nil => out
//            case t :: ts => go(context, SpacedList(t,ts), lst.head :: out)
//          }
//          case _ => ???
//        }
//      }
//
//      go(Context(registry.tree, None), exprList(t), List())
//    }

  // format: off


  
  def run(module:Module): Module =
    module.map(_.map(partition))
}