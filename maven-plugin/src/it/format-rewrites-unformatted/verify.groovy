def file = { String path -> new File(basedir, path) }
// setup.groovy stamped every file with this time.
def written = { String path -> file(path).lastModified() != 1_000_000_000_000L }

assert file('src/main/resources/application.conf').text == '''\
server {
  host: localhost
  port: 8080
}
'''
// Directly under src/: `**` has to match zero directories, and .hocon is included by default.
assert file('src/top.hocon').text == 'name: top\n'

// Formatted already, so rewriting it would only churn its timestamp.
assert !written('src/main/resources/formatted.conf')
// Outside the default includes.
assert !written('outside/untouched.conf')

def log = file('build.log').text
assert log.contains('2 reformatted, 1 already formatted, 0 refused')
