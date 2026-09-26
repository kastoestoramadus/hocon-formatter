// Written here rather than committed, so no editor or checkout can quietly re-encode it.
new File(basedir, 'src/main/resources/latin1.conf').bytes = 'name = "café"\n'.getBytes('ISO-8859-1')

// A timestamp no build could produce: a file still carrying it afterwards was never written.
basedir.eachFileRecurse(groovy.io.FileType.FILES) { it.lastModified = 1_000_000_000_000L }
