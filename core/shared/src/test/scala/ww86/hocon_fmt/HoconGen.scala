package ww86.hocon_fmt

import org.scalacheck.Gen

/** Random HOCON documents, generated as a tree that knows its own comments and includes and then
  * written the ways people write HOCON: `:`, `=` or nothing before `{`, both comment markers,
  * quoted and dotted keys, ragged spacing.
  *
  * Knowing the comments and includes up front is what lets a property check the output without
  * parsing it with the code under test. Only constructs the formatter supports are generated, so a
  * failing property is a formatter bug or a new sconfig defect, not a malformed input.
  */
object HoconGen {

  enum Node {
    case Field(key: String, separator: String, value: Value)
    case Comment(marker: String, text: String)
    case Include(statement: String)
  }

  enum Value {
    case Scalar(text: String)
    case Array(items: List[Value])
    case Object(nodes: List[Node])
  }

  final case class Document(nodes: List[Node]) {

    def text: String = render(nodes, indent = "") + "\n"

    def comments: List[String] = collect(nodes) { case Node.Comment(_, text) => text }

    def includes: List[String] = collect(nodes) { case Node.Include(statement) => statement }

    /** sconfig drops a comment with no field after it, which the formatter must refuse; documents
      * without one are those it has no reason to refuse.
      */
    def everyCommentPrecedesAField: Boolean = lists(nodes).forall { level =>
      val lastField = level.lastIndexWhere(_.isInstanceOf[Node.Field])
      level.zipWithIndex.forall { case (node, i) => !node.isInstanceOf[Node.Comment] || i < lastField }
    }

    /** A one-field object holding a substitution, inside an array: sconfig renders it without its
      * braces (see SconfigDefectsSpec), so the formatter rightly refuses it.
      */
    def hitsKnownSconfigDefect: Boolean = arrayItems(nodes).exists {
      case Value.Object(inner) => inner.count(_.isInstanceOf[Node.Field]) == 1 && holdsSubstitution(inner)
      case _                   => false
    }

    override def toString: String = s"\n$text"
  }

  // ---- rendering -------------------------------------------------------------------------------

  def render(nodes: List[Node], indent: String): String = nodes.map(render(_, indent)).mkString("\n")

  def render(node: Node, indent: String): String = node match {
    case Node.Field(key, separator, value) => s"$indent$key$separator${render(value, indent)}"
    case Node.Comment(marker, text)        => s"$indent$marker $text"
    case Node.Include(statement)           => s"$indent$statement"
  }

  def render(value: Value, indent: String): String = value match {
    case Value.Scalar(text)                   => text
    case Value.Array(items)                   => items.map(render(_, indent)).mkString("[", ", ", "]")
    case Value.Object(nodes) if nodes.isEmpty => "{}"
    case Value.Object(nodes)                  => s"{\n${render(nodes, indent + "  ")}\n$indent}"
  }

  def lists(nodes: List[Node]): List[List[Node]] =
    nodes :: nodes.flatMap {
      case Node.Field(_, _, value) => listsIn(value)
      case _                       => Nil
    }

  def listsIn(value: Value): List[List[Node]] = value match {
    case Value.Object(nodes) => lists(nodes)
    case Value.Array(items)  => items.flatMap(listsIn)
    case Value.Scalar(_)     => Nil
  }

  def arrayItems(nodes: List[Node]): List[Value] = {
    def in(value: Value): List[Value] = value match {
      case Value.Array(items)  => items ++ items.flatMap(in)
      case Value.Object(inner) => arrayItems(inner)
      case Value.Scalar(_)     => Nil
    }
    nodes.flatMap {
      case Node.Field(_, _, value) => in(value)
      case _                       => Nil
    }
  }

  def holdsSubstitution(nodes: List[Node]): Boolean = {
    def in(value: Value): Boolean = value match {
      case Value.Scalar(text)  => text.startsWith("${")
      case Value.Array(items)  => items.exists(in)
      case Value.Object(inner) => holdsSubstitution(inner)
    }
    nodes.exists {
      case Node.Field(_, _, value) => in(value)
      case _                       => false
    }
  }

  def collect[A](nodes: List[Node])(pick: PartialFunction[Node, A]): List[A] =
    lists(nodes).flatten.collect(pick)

  // ---- generators ------------------------------------------------------------------------------

  val reserved = Set("include", "true", "false", "null", "yes", "no", "on", "off")

  val word: Gen[String] =
    Gen.choose(1, 8).flatMap(Gen.listOfN(_, Gen.alphaLowerChar)).map(_.mkString).map { w =>
      if (reserved(w)) w + "x" else w
    }

  val key: Gen[String] = Gen.frequency(
    6 -> word,
    1 -> Gen.listOfN(2, word).map(_.mkString(".")),
    1 -> Gen.listOfN(2, word).map(words => words.mkString("\"", " ", "\""))
  )

  /** Text a string or a comment might hold, including what the formatter must not mistake for
    * code: quotes, comment markers, and the word include followed by a target.
    */
  def textOf(pieces: Gen[String]): Gen[String] =
    Gen.choose(0, 6).flatMap(Gen.listOfN(_, pieces)).map(_.mkString.trim)

  val quoted: Gen[String] =
    textOf(
      Gen.frequency(
        8 -> word.map(_ + " "),
        1 -> Gen.const("\\\""),
        1 -> Gen.const("# "),
        1 -> Gen.const("// "),
        1 -> Gen.const("include \\\"x.conf\\\" ")
      )
    ).map(s => s"\"$s\"")

  val scalar: Gen[Value] = Gen
    .frequency(
      3 -> Gen.choose(-1000, 100000).map(_.toString),
      1 -> Gen.choose(0, 99).map(n => s"$n.5"),
      1 -> Gen.oneOf("true", "false", "null"),
      3 -> quoted,
      2 -> word,
      1 -> Gen.const("${?HOCON_GEN_UNSET}")
    )
    .map(Value.Scalar(_))

  val comment: Gen[Node] = for {
    marker <- Gen.oneOf("#", "//")
    text   <- textOf(
              Gen.frequency(
                8 -> word.map(_ + " "),
                1 -> Gen.const("\" "),
                1 -> Gen.const("{ "),
                1 -> Gen.const("} "),
                1 -> Gen.const("include \"x.conf\" ")
              )
            )
  } yield Node.Comment(marker, text)

  val include: Gen[Node] = word.flatMap { name =>
    Gen
      .oneOf(
        s"\"$name.conf\"",
        s"required(\"$name.conf\")",
        s"file(\"$name.conf\")",
        s"classpath(\"$name.conf\")",
        s"url(\"https://example.com/$name.conf\")"
      )
      .map(target => Node.Include(s"include $target"))
  }

  def value(depth: Int): Gen[Value] =
    if (depth <= 0) scalar
    else Gen.frequency(6 -> scalar, 1 -> Gen.lzy(array(depth - 1)), 2 -> Gen.lzy(obj(depth - 1, includes = true)))

  def array(depth: Int): Gen[Value] = Gen.choose(0, 4).flatMap(Gen.listOfN(_, value(depth))).map(Value.Array(_))

  def obj(depth: Int, includes: Boolean): Gen[Value] =
    Gen.choose(0, 4).flatMap(Gen.listOfN(_, node(depth, includes))).map(Value.Object(_))

  def field(depth: Int, includes: Boolean): Gen[Node] = for {
    k         <- key
    v         <- if (includes) value(depth) else value(depth).map(withoutIncludes)
    separator <- v match {
                   case Value.Object(_) => Gen.oneOf(" ", " : ", ":", " = ", "  =  ")
                   case _               => Gen.oneOf(" : ", ":", " = ", "=", "  :  ")
                 }
  } yield Node.Field(k, separator, v)

  def node(depth: Int, includes: Boolean): Gen[Node] =
    if (includes) Gen.frequency(8 -> field(depth, includes), 2 -> comment, 1 -> include)
    else Gen.frequency(8          -> field(depth, includes), 2 -> comment)

  def withoutIncludes(value: Value): Value = value match {
    case Value.Object(nodes) =>
      Value.Object(nodes.collect {
        case Node.Field(k, s, v) => Node.Field(k, s, withoutIncludes(v))
        case c: Node.Comment     => c
      })
    case Value.Array(items) => Value.Array(items.map(withoutIncludes))
    case scalar             => scalar
  }

  /** With `distinctKeys`, no field replaces or merges into another. Otherwise a later definition of
    * a key may replace an object, and everything written inside it, which is the formatter's
    * business to notice.
    */
  def documents(includes: Boolean, distinctKeys: Boolean = false): Gen[Document] =
    Gen.choose(0, 8).flatMap(Gen.listOfN(_, node(2, includes))).map { nodes =>
      Document(if (distinctKeys) withDistinctKeys(nodes) else nodes)
    }

  /** Renames fields so no two at one level share the first segment of their path. */
  def withDistinctKeys(nodes: List[Node]): List[Node] =
    nodes.zipWithIndex.map {
      case (Node.Field(key, separator, value), i) =>
        val (first, rest) = firstSegment(key)
        Node.Field(s"${first}_$i$rest", separator, distinctKeysIn(value))
      case (other, _) => other
    }

  def distinctKeysIn(value: Value): Value = value match {
    case Value.Object(nodes) => Value.Object(withDistinctKeys(nodes))
    case Value.Array(items)  => Value.Array(items.map(distinctKeysIn))
    case scalar              => scalar
  }

  // A quoted key is one segment, closing quote and all; the suffix goes inside the quotes.
  def firstSegment(key: String): (String, String) =
    if (key.startsWith("\"")) (key.dropRight(1), "\"")
    else key.span(_ != '.')
}
