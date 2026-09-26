# Playground

A page in the Lab on [ww86.eu](https://ww86.eu/lab/index.html) where anyone can paste HOCON and
see what the formatter makes of it, with nothing installed and nothing sent to a server. This
repository ships the engine; the page lives in the site's repository,
[ww86.eu](https://github.com/kastoestoramadus/ww86.eu).

## The engine

`sbt web/bundle` writes `web/target/bundle/hocon-formatter.js`; every GitHub release carries the
same file as `hocon-formatter.js`. It is a classic script, not an ES module, so a page that loads
it also works opened from `file://`, as every ww86.eu page must. Closure-compiled, it is 440 KB,
130 KB gzipped; in Node it loads in about 30 ms and formats a 281-line file in about 13 ms.

It defines one global:

```js
HoconFormatter.version        // "0.1.0"
HoconFormatter.format(text)   // one of:
// { verdict: "needsFormatting",  formatted: "…" }
// { verdict: "alreadyFormatted", formatted: text }
// { verdict: "refused", refusal: "notHocon", reason: "not valid HOCON: …" }
```

`refusal` is one of `notHocon`, `brokenOutput`, `lostComment`, `lostInclude`, `unstableOutput`
(`notUtf8` cannot happen to a JavaScript string). These names are the API: `HoconFormatterJsSpec`
pins them, on the Closure-compiled script, where every name that is not exported gets renamed.

## Why a lab directory rather than a site widget

ww86.eu has two kinds of lab page: generated pages whose widget is part of the site's one Scala.js
script, like `digits`, and self-contained directories under `lab/`, copied as they are. The
playground is the second kind:

- The site is on Scala 3.3 LTS, which cannot read this Scala 3.8 core. Moving the whole site to
  3.8 for one page is out of proportion.
- The site's script loads on every page; 130 KB of formatter belongs on one.
- A `lab/` directory needs no build step: the script is a file next to `index.html`.

If the site moves to Scala 3.8 anyway, the page can become a Laminar widget that calls the core
directly, without the JavaScript API.

## The page

`lab/hocon-formatter/` in ww86.eu: `index.html` (markup, styles, a few dozen lines of script) and
`hocon-formatter.js` from a release, unchanged.

1. **Intro**: what HOCON is and what a formatter adds, in two sentences; the promise that a file
   is refused rather than corrupted.
2. **Two panes**, input and output, formatting as you type with a debounce of about 150 ms. Under
   the output, one status line:
   - `needsFormatting`: "Formatted", with how many lines changed;
   - `alreadyFormatted`: "Already formatted";
   - `refused`: "Left unchanged", the reason, and one sentence per `refusal` name explaining it,
     linking to [limitations](limitations.md).
3. **Examples**, one button each, each showing one property: a messy config; includes that survive
   (the README's example); comments kept; not HOCON at all (an nginx config); a known sconfig
   defect (`a : [1]` then `a += 2`).
4. **Use it**: the command line, pre-commit and the build-tool plugins, linking to
   [usage](usage.md).
5. **Footer**: `HoconFormatter.version`, a link to the source at that tag, GPL-3.0.

The site's rules apply (its `AGENTS.md` and `lab/README.md`): relative links only, English copy,
`lang` and a meta description, a link back to `../index.html`, and a `LabItem` in `Catalog.scala`
whose `tech` lists Scala 3 and Scala.js, plus "drafted with Claude" if it was.

## Steps

In this repository:

1. Release (see [releasing](releasing.md)), so the script is attached. Until then, `sbt web/bundle`.

In ww86.eu:

2. `mkdir lab/hocon-formatter`, then fetch the script:
   `curl -fL -o lab/hocon-formatter/hocon-formatter.js https://github.com/kastoestoramadus/hocon-formatter/releases/download/v<version>/hocon-formatter.js`
3. Write `index.html`, loading `<script src="hocon-formatter.js"></script>` before its own script.
4. Add the `LabItem`, run `sbt buildSite`, and try the page both served
   (`python3 -m http.server -d target/site 4001`) and from `file://`.
5. Open the pull request and link its preview,
   `https://ww86.eu/preview/pr-<N>/lab/hocon-formatter/index.html`.

A new formatter version is step 2 again.

## Later

- **Share by link**: the input in the URL fragment, which never reaches a server, so a refused
  input can be passed on as one link.
- **A diff** of input and output instead of the output alone.
- **"Report this refusal"**: a prefilled GitHub issue, only on an explicit click, since pasted
  configs can be private.
