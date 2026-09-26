// A timestamp no build could produce: a file still carrying it afterwards was never written.
basedir.eachFileRecurse(groovy.io.FileType.FILES) { it.lastModified = 1_000_000_000_000L }
