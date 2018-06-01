libraryDependencies ++= Seq(
  "net.ruippeixotog" %% "scala-scraper" % "2.1.0",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.slick" %% "slick" % "3.2.3",
  "org.xerial" % "sqlite-jdbc" % "3.23.1",
)

fork := true

scalacOptions ++= Seq(
  "-target:jvm-1.8",
  "-encoding", "utf8",
  "-unchecked",
  "-deprecation",
  "-feature",
  "-explaintypes",

  "-Xfuture",

  "-Xlog-free-terms",
  "-Xlog-free-types",
  //"-Xlog-implicits",

  "-Xverify",
  "-Xlint:_",

  "-Yno-adapted-args",
  //"-Yrangepos",

  "-Ywarn-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-inaccessible",
  "-Ywarn-infer-any",
  "-Ywarn-nullary-override",
  "-Ywarn-nullary-unit",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard"
)
