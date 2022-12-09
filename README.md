# pipeline-extras

Fixed version of `pipeline*` (no off-by-two error in `pipeline-async` [ASYNC-163](https://clojure.atlassian.net/browse/ASYNC-163)), as well as unordered versions of all pipeline functions.
Requires but doesn't include [clojure.core.async](https://github.com/clojure/core.async) as a dependency.
Should work on both JVM Clojure and ClojureScript.

## Building

To just build a jar:

    clojure -T:build jar

To build and install jar to a local `.m2` repo:

    clojure -T:build install

## Testing

To run tests:

    clojure -X:test/kaocha

Coverage:

    clojure -X:test/cloverage

## License

Copyright © 2022 Andrey Listopadov

Distributed under the Eclipse Public License version 1.0.
