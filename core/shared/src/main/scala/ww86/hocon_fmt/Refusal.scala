package ww86.hocon_fmt

/** Why the formatter left a text alone. Every case means the file must not be written. */
enum Refusal {

  /** Decoding the bytes leniently would replace the invalid ones, and writing that back would
    * destroy them.
    */
  case NotUtf8

  /** The input is not HOCON that sconfig can read. */
  case NotHocon(detail: String)

  /** sconfig rendered text it cannot read back: one of the defects in `SconfigDefectsSpec`. */
  case BrokenOutput(detail: String)

  /** sconfig drops a comment that no field follows. A comment carries no meaning, so no other
    * check notices; losing one still loses what someone wrote down.
    */
  case LostComment(text: String)

  /** Formatting the output again would change it, so the file would never settle. sconfig renders
    * an unresolved merge as a comment banner that parses but grows on every pass.
    */
  case UnstableOutput

  def reason: String = this match {
    case NotUtf8              => "not valid UTF-8"
    case NotHocon(detail)     => s"not valid HOCON: $detail"
    case BrokenOutput(detail) => s"the output would not parse again: $detail"
    case LostComment(text)    => s"a comment would be lost: $text"
    case UnstableOutput       => "a second formatting pass would change the output again"
  }
}
