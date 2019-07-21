package org.enso.syntax.text

import org.enso.flexer.ParserBase
import org.enso.syntax.text.AST_Mod._
import org.enso.{flexer => Flexer}
import org.scalatest._

class ParserSpec extends FlatSpec with Matchers {

  def parse(input: String) = {
    val parser = new Parser()
    parser.run(input)
  }

  def assertModule(input: String, result: AST): Assertion = {
    val tt = parse(input)
    tt match {
      case Flexer.Success(value, offset) =>
        assert(value == result)
        assert(value.show() == input)
      case _ => fail(s"Parsing failed, consumed ${tt.offset} chars")
    }
  }

  def assertExpr(input: String, result: AST): Assertion = {
    val tt = parse(input)
    tt match {
      case Flexer.Success(value, offset) =>
        val module = value.asInstanceOf[Module]
        module.lines match {
          case Nil =>
            module.firstLine.elem match {
              case None => fail("Empty expression")
              case Some(e) =>
                assert(e == result)
                assert(value.show() == input)
            }
          case _ => fail("Multi-line block")
        }
      case _ => fail(s"Parsing failed, consumed ${tt.offset} chars")
    }
  }

  implicit class TestString(input: String) {
    def parseTitle(str: String): String = {
      val maxChars = 20
      val escape   = (str: String) => str.replace("\n", "\\n")
      val str2     = escape(str)
      val str3 =
        if (str2.length < maxChars) str2
        else str2.take(maxChars) + "..."
      s"parse `$str3`"
    }

    private val testBase = it should parseTitle(input)

    def ?==(out: AST)    = testBase in { assertExpr(input, out) }
    def ?==(out: Module) = testBase in { assertModule(input, out) }
  }

  /////////////////
  // Identifiers //
  /////////////////

  "_"      ?== "_"
  "Name"   ?== "Name"
  "name"   ?== "name"
  "name'"  ?== "name'"
  "name''" ?== "name''"
  "name'a" ?== Identifier.InvalidSuffix("name'", "a")
  "name_"  ?== "name_"
  "name_'" ?== "name_'"
  "name'_" ?== Identifier.InvalidSuffix("name'", "_")
  "name`"  ?== "name" $ Unrecognized("`")

  ///////////////
  // Operators //
  ///////////////

  "++"   ?== "++"
  "="    ?== "="
  "=="   ?== "=="
  ":"    ?== ":"
  ","    ?== ","
  "."    ?== "."
  ".."   ?== ".."
  "..."  ?== "..."
  ">="   ?== ">="
  "<="   ?== "<="
  "/="   ?== "/="
  "+="   ?== Modifier("+")
  "-="   ?== Modifier("-")
  "==="  ?== Identifier.InvalidSuffix("==", "=")
  "...." ?== Identifier.InvalidSuffix("...", ".")
  ">=="  ?== Identifier.InvalidSuffix(">=", "=")
  "+=="  ?== Identifier.InvalidSuffix("+", "==")

  /////////////////
  // Expressions //
  /////////////////

  "a b"           ?== ("a" $_ "b")
  "a +  b"        ?== ("a" $_ "+") $__ "b"
  "a + b + c"     ?== ("a" $_ "+" $_ "b") $_ "+" $_ "c"
  "a , b , c"     ?== "a" $_ "," $_ ("b" $_ "," $_ "c")
  "a + b * c"     ?== "a" $_ "+" $_ ("b" $_ "*" $_ "c")
  "a * b + c"     ?== ("a" $_ "*" $_ "b") $_ "+" $_ "c"
  "a+ b"          ?== ("a" $ "+") $$_ "b"
  "a +b"          ?== "a" $_ ("+" $ "b")
  "a+ +b"         ?== ("a" $ "+") $$_ ("+" $ "b")
  "*a+"           ?== ("*" $ "a") $ "+"
  "+a*"           ?== "+" $ ("a" $ "*")
  "+ <$> a <*> b" ?== (Section("+") $_ "<$>" $_ "a") $_ "<*>" $_ "b"
//  "a+b"    ?==
//  "()"        ?== "(" $ ")" // Group()
//  "(())"      ?== "(" $ "(" $ ")" $ ")" // Group(Group())
//  "(()"       ?== "(" $ "(" $ ")" // Group.Unclosed(Group())
//  "(("        ?== "(" $ "(" // Group.Unclosed(Group.Unclosed())
//  "( "        ?== "(" // Group.Unclosed()
//  ")"         ?== ")" // Group.UnmatchedClose
//  ")("        ?== ")" $ "(" // Group.UnmatchedClose $ Group.Unclosed()
//  "a ( b c )" ?== "a" $_ "(" $_ "b" $_ "c" $_ ")" // ("a" $_ Group(1, "b" $_ "c", 1))
//  "(a (b c))" ?== "(" $ "a" $_ "(" $ "b" $_ "c" $ ")" $ ")" // Group("a" $_ Group("b" $_ "c"))

  ////////////
  // Layout //
  ////////////

  ""      ?== Module(Line())
  "\n"    ?== Module(Line(), Line())
  "  \n " ?== Module(Line(2), Line(1))
  "\n\n"  ?== Module(Line(), Line(), Line())
  //  test module "(a)"  ==? GroupBegin  :: Var("a") :: GroupEnd
  //  test module "[a]"  ==? ListBegin   :: Var("a") :: ListEnd
  //  test module "{a}"  ==? RecordBegin :: Var("a") :: RecordEnd

  /////////////
  // Numbers //
  /////////////

  "7"     ?== 7
  "07"    ?== Number("07")
  "10_7"  ?== Number(10, 7)
  "16_ff" ?== Number(16, "ff")
  "16_"   ?== Number.DanglingBase("16")
  "7.5"   ?== InfixApp(7, 0, Operator("."), 0, 5)

  ////////////////////
  // UTF Surrogates //
  ////////////////////

  "\uD800\uDF1E" ?== Unrecognized("\uD800\uDF1E")

  /////////////////
  // Large Input //
  /////////////////

  "BIG_INPUT_" * ParserBase.BUFFERSIZE ?== "BIG_INPUT_" * ParserBase.BUFFERSIZE

  //////////
  // Text //
  //////////

//  "'"       ?== Text.Unclosed(Text())
  "''"   ?== Text()
  "'''"  ?== Text.Unclosed(Text(Text.TripleQuote))
  "''''" ?== Text.Unclosed(Text(Text.TripleQuote, "'"))
//  "'''''"   ?== Text.Unclosed(Text(Text.TripleQuote, "''")) // FIXME
  "''''''"  ?== Text(Text.TripleQuote)
  "'''''''" ?== Text(Text.TripleQuote) $ Text.Unclosed(Text())
  "'a'"     ?== Text("a")
  "'a"      ?== Text.Unclosed(Text("a"))
  "'a'''"   ?== Text("a") $ Text()
  "'''a'''" ?== Text(Text.TripleQuote, "a")
  "'''a'"   ?== Text.Unclosed(Text(Text.TripleQuote, "a'"))
//  "'''a''"  ?== Text.Unclosed(Text(Text.TripleQuote, "a''")) // FIXME

  //// Escapes ////

  Text.Segment.Escape.Character.codes.foreach(i => s"'\\$i'" ?== Text(i))
  Text.Segment.Escape.Control.codes.foreach(i => s"'\\$i'"   ?== Text(i))

  "'\\\\'"   ?== Text(Text.Segment.Escape.Slash)
  "'\\''"    ?== Text(Text.Segment.Escape.Quote)
  "'\\\"'"   ?== Text(Text.Segment.Escape.RawQuote)
  "'\\"      ?== Text.Unclosed(Text("\\"))
  "'\\c'"    ?== Text(Text.Segment.Escape.Invalid("c"))
  "'\\cd'"   ?== Text(Text.Segment.Escape.Invalid("c"), "d")
  "'\\123d'" ?== Text(Text.Segment.Escape.Number(123), "d")

  //// Interpolation ////

  "'a`b`c'" ?== Text("a", Text.Segment.Interpolated(Some("b")), "c")
  "'a`b 'c`d`e' f`g'" ?== {
    val bd = "b" $_ Text("c", Text.Segment.Interpolated(Some("d")), "e") $_ "f"
    Text("a", Text.Segment.Interpolated(Some(bd)), "g")
  }
//  "'`a(`'" ?== Text(Text.Segment.Interpolated(Some("a" $ Group.Unclosed())))
  //  // Comments
  //  expr("#"              , Comment)
  //  expr("#c"             , Comment :: CommentBody("c"))
  //  expr("#c\na"          , Comment :: CommentBody("c") :: EOL :: Var("a"))
  //  expr("#c\n a"         , Comment :: CommentBody("c") :: EOL :: CommentBody(" a"))
  //  expr(" #c\n a"        , Comment :: CommentBody("c") :: EOL :: Var("a"))
  //  expr(" #c\n  a"       , Comment :: CommentBody("c") :: EOL :: CommentBody("  a"))
  //  expr("a#c"            , Var("a") :: Comment :: CommentBody("c"))
  //  expr("a # c"          , Var("a") :: Comment :: CommentBody(" c"))
  //  expr("a#"             , Var("a") :: Comment)
  //  expr("a#\nb"          , Var("a") :: Comment :: EOL :: Var("b"))
  //  expr("a#\n b"         , Var("a") :: Comment :: EOL :: CommentBody(" b"))
  //
  //  // Disabled
  //  expr("a #= b"         , Var("a") :: DisabledAssignment :: Var("b"))
  //
}