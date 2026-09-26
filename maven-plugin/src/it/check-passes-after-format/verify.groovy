def file = { String path -> new File(basedir, path) }

assert file('src/main/resources/application.conf').text == 'a: 1\n'

def log = file('build.log').text
assert log.contains(':check (default) @ check-passes-after-format')
assert log.contains('0 not formatted, 1 already formatted, 0 refused')
