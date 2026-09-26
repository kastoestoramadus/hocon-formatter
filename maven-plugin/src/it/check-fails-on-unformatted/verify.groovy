def file = { String path -> new File(basedir, path) }

// Still carrying the time setup.groovy stamped them with, so none was written.
basedir.eachFileRecurse(groovy.io.FileType.FILES) {
  if (it.name.endsWith('.conf')) assert it.lastModified() == 1_000_000_000_000L
}

def log = file('build.log').text
// Both are reported, so the goal did not stop at the first one.
assert log.contains('[ERROR] Not formatted: src/main/resources/first.conf')
assert log.contains('[ERROR] Not formatted: src/main/resources/nested/second.conf')
assert !log.contains('Not formatted: src/main/resources/formatted.conf')
assert log.contains('2 not formatted, 1 already formatted, 0 refused')
