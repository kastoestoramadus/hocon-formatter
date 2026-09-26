def file = { String path -> new File(basedir, path) }
// setup.groovy stamped every file with this time.
def written = { String path -> file(path).lastModified() != 1_000_000_000_000L }

assert file('conf/application.conf').text == 'a: 1\n'
assert !written('conf/generated/generated.conf')
// Matched by the default includes, which the configured ones replace.
assert !written('src/main/resources/default.conf')
