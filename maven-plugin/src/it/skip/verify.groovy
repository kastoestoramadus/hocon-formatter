// check would have failed the build, and format would have rewritten this file, replacing the
// time setup.groovy stamped it with.
assert new File(basedir, 'src/main/resources/application.conf').lastModified() == 1_000_000_000_000L

def log = new File(basedir, 'build.log').text
assert log.count('HOCON formatting skipped') == 2
