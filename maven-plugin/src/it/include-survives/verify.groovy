def lines = new File(basedir, 'src/main/resources/application.conf').readLines()

// Only the includes are pinned; the rest of the layout is the formatter's business.
assert lines.contains('include "other.conf"')
assert lines.contains('  include required(file("local.conf"))')
assert lines.contains('  name: demo')

def log = new File(basedir, 'build.log').text
assert log.contains('1 reformatted, 1 already formatted, 0 refused')
assert log.contains('0 not formatted, 2 already formatted, 0 refused')
