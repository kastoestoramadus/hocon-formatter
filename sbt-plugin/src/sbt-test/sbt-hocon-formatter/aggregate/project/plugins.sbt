sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("io.github.kastoestoramadus" % "sbt-hocon-formatter" % version)
  case None          => sys.error("Run through `scripted`, which passes the plugin version as -Dplugin.version.")
}
