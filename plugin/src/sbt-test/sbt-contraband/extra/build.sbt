name := "example"

libraryDependencies += "com.eed3si9n" %% "sjson-new-core" % "0.6.1-dnw"

resolvers += Resolver.mavenLocal

enablePlugins(ContrabandPlugin, JsonCodecPlugin)
